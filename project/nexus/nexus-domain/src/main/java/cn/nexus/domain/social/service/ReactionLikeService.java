package cn.nexus.domain.social.service;

import cn.nexus.domain.counter.adapter.port.IUserCounterPort;
import cn.nexus.domain.counter.model.valobj.UserCounterType;
import cn.nexus.domain.social.adapter.port.IPostAuthorPort;
import cn.nexus.domain.social.adapter.port.IReactionCachePort;
import cn.nexus.domain.social.adapter.port.IReactionCommentLikeChangedMqPort;
import cn.nexus.domain.social.adapter.port.IReactionEventLogMqPort;
import cn.nexus.domain.social.adapter.port.IReactionLikeUnlikeMqPort;
import cn.nexus.domain.social.adapter.port.IReactionNotifyMqPort;
import cn.nexus.domain.social.adapter.port.IReactionRecommendFeedbackMqPort;
import cn.nexus.domain.social.adapter.port.ISocialIdPort;
import cn.nexus.domain.social.adapter.repository.ICommentRepository;
import cn.nexus.domain.social.model.valobj.ReactionActionEnumVO;
import cn.nexus.domain.social.model.valobj.ReactionApplyResultVO;
import cn.nexus.domain.social.model.valobj.CommentBriefVO;
import cn.nexus.domain.social.model.valobj.ReactionResultVO;
import cn.nexus.domain.social.model.valobj.ReactionStateVO;
import cn.nexus.domain.social.model.valobj.ReactionTargetTypeEnumVO;
import cn.nexus.domain.social.model.valobj.ReactionTargetVO;
import cn.nexus.domain.social.model.valobj.ReactionTypeEnumVO;
import cn.nexus.domain.social.model.valobj.ReactionEventLogRecordVO;
import cn.nexus.types.enums.ResponseCode;
import cn.nexus.types.event.interaction.EventType;
import cn.nexus.types.event.interaction.CommentLikeChangedEvent;
import cn.nexus.types.event.interaction.InteractionNotifyEvent;
import cn.nexus.types.event.interaction.LikeUnlikePostEvent;
import cn.nexus.types.event.recommend.RecommendFeedbackEvent;
import cn.nexus.types.exception.AppException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 点赞子域服务。
 *
 * @author rr
 * @author codex
 * @since 2026-01-20
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReactionLikeService implements IReactionLikeService {

    private static final String EVT_COMMENT_LIKE_CHANGED_PREFIX = "comment_like_changed:";

    private final IReactionCachePort reactionCachePort;
    private final ICommentRepository commentRepository;
    private final ISocialIdPort socialIdPort;
    private final IReactionCommentLikeChangedMqPort reactionCommentLikeChangedMqPort;
    private final IReactionNotifyMqPort reactionNotifyMqPort;
    private final IReactionRecommendFeedbackMqPort reactionRecommendFeedbackMqPort;
    private final IReactionLikeUnlikeMqPort reactionLikeUnlikeMqPort;
    private final IPostAuthorPort postAuthorPort;
    private final IReactionEventLogMqPort reactionEventLogMqPort;
    private final IUserCounterPort userCounterPort;

    /**
     * 统一点赞/取消点赞入口。
     *
     * @param userId 操作人 ID，类型：{@link Long}
     * @param target 点赞目标，类型：{@link ReactionTargetVO}
     * @param action 动作类型，类型：{@link ReactionActionEnumVO}
     * @param requestId 请求幂等号；为空时自动生成，类型：{@link String}
     * @return 点赞执行结果，类型：{@link ReactionResultVO}
     */
    @Override
    public ReactionResultVO applyReaction(Long userId, ReactionTargetVO target, ReactionActionEnumVO action, String requestId) {
        requireNonNull(userId, "userId");
        requireTarget(target);
        requireNonNull(action, "action");
        requireLikeOnly(target);
        return applyUnifiedLike(userId, target, action, requestId);
    }

    private ReactionResultVO applyUnifiedLike(Long userId, ReactionTargetVO target, ReactionActionEnumVO action, String requestId) {
        String rid = (requestId == null || requestId.isBlank()) ? ("rid-" + socialIdPort.nextId()) : requestId.trim();
        long nowMs = socialIdPort.now();
        int desiredState = action.desiredState();
        ReactionApplyResultVO apply = reactionCachePort.applyAtomic(userId, target, desiredState);
        if (apply == null || apply.getDelta() == null || apply.getCurrentCount() == null) {
            throw new AppException(ResponseCode.UN_ERROR.getCode(), "reaction cache apply failed");
        }
        int delta = apply.getDelta();
        long currentCount = apply.getCurrentCount();
        boolean firstPending = apply.isFirstPending();

        publishEventLogBestEffort(rid, userId, target, desiredState, delta, nowMs);

        if (target.getTargetType() == ReactionTargetTypeEnumVO.POST) {
            publishPostSideEffects(rid, userId, target, delta, nowMs);
        } else if (target.getTargetType() == ReactionTargetTypeEnumVO.COMMENT) {
            publishCommentSideEffects(rid, userId, target, delta, nowMs);
        }

        log.info(buildEventJson(rid, userId, target, action, desiredState, delta, currentCount, firstPending));
        return ReactionResultVO.builder()
                .requestId(rid)
                .currentCount(currentCount)
                .delta(delta)
                .success(true)
                .build();
    }

    /**
     * 查询当前用户的点赞状态。
     *
     * @param userId 当前用户 ID，类型：{@link Long}
     * @param target 点赞目标，类型：{@link ReactionTargetVO}
     * @return 点赞状态结果，类型：{@link ReactionStateVO}
     */
    @Override
    public ReactionStateVO queryState(Long userId, ReactionTargetVO target) {
        requireNonNull(userId, "userId");
        requireTarget(target);
        requireLikeOnly(target);

        boolean state = reactionCachePort.getState(userId, target);
        long cnt = reactionCachePort.getCount(target);
        return ReactionStateVO.builder().state(state).currentCount(cnt).build();
    }

    private void publishPostSideEffects(String requestId, Long userId, ReactionTargetVO target, int delta, long nowMs) {
        Long postId = target == null ? null : target.getTargetId();
        Long creatorId = postId == null ? null : postAuthorPort.getPostAuthorId(postId);
        if (postId == null || creatorId == null || delta == 0) {
            return;
        }
        incrementLikeReceivedBestEffort(requestId, creatorId, delta);

        LikeUnlikePostEvent event = new LikeUnlikePostEvent();
        event.setEventId(requestId);
        event.setUserId(userId);
        event.setPostId(postId);
        event.setPostCreatorId(creatorId);
        event.setCreateTime(nowMs);

        if (delta > 0) {
            event.setType(1);
            reactionLikeUnlikeMqPort.publishLike(event);
            publishNotifyLikeAdded(requestId, userId, target);
            return;
        }

        event.setType(0);
        reactionLikeUnlikeMqPort.publishUnlike(event);
        publishRecommendUnlike(requestId, userId, target);
    }

    private void publishCommentSideEffects(String requestId, Long userId, ReactionTargetVO target, int delta, long nowMs) {
        if (target == null || target.getTargetId() == null || delta == 0) {
            return;
        }
        Long rootCommentId = target.getTargetId();
        CommentBriefVO commentBrief = loadCommentBrief(rootCommentId);
        if (commentBrief != null && commentBrief.getUserId() != null) {
            incrementLikeReceivedBestEffort(requestId, commentBrief.getUserId(), delta);
        }
        Long postId = commentBrief == null ? null : commentBrief.getPostId();
        if (postId != null) {
            publishCommentLikeChanged(requestId, rootCommentId, postId, delta, nowMs);
        }
        if (delta > 0) {
            publishNotifyLikeAdded(requestId, userId, target);
        }
    }

    private void requireTarget(ReactionTargetVO target) {
        requireNonNull(target, "target");
        requireNonNull(target.getTargetType(), "targetType");
        requireNonNull(target.getTargetId(), "targetId");
        requireNonNull(target.getReactionType(), "reactionType");
    }

    private void requireLikeOnly(ReactionTargetVO target) {
        if (target.getReactionType() != ReactionTypeEnumVO.LIKE) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "only LIKE is supported");
        }
    }

    private void requireNonNull(Object v, String name) {
        if (v == null) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "illegal parameter: " + name);
        }
    }

    private String buildEventJson(String requestId,
                                  Long userId,
                                  ReactionTargetVO target,
                                  ReactionActionEnumVO action,
                                  int desiredState,
                                  int delta,
                                  long currentCount,
                                  boolean firstPending) {
        long ts = socialIdPort.now();
        return "{"
                + "\"event\":\"reaction_like\","
                + "\"ts\":" + ts + ","
                + "\"requestId\":\"" + safe(requestId) + "\","
                + "\"userId\":" + userId + ","
                + "\"targetType\":\"" + target.getTargetType().getCode() + "\","
                + "\"targetId\":" + target.getTargetId() + ","
                + "\"reactionType\":\"" + target.getReactionType().getCode() + "\","
                + "\"action\":\"" + action.getCode() + "\","
                + "\"desiredState\":" + desiredState + ","
                + "\"delta\":" + delta + ","
                + "\"currentCount\":" + currentCount + ","
                + "\"firstPending\":" + firstPending
                + "}";
    }

    private String safe(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void publishNotifyLikeAdded(String requestId, Long fromUserId, ReactionTargetVO target) {
        try {
            // ReliableMqOutbox 鐨?event_id 鏄叏灞€鍞竴锛氬悓涓€涓?requestId 鍙兘鍚屾椂瑙﹀彂澶氭潯涓氬姟娑堟伅锛圠ikeUnlike + Notify锛夈€?
            // 濡傛灉澶嶇敤 requestId 浣滀负 eventId锛屼細瀵艰嚧绗簩鏉℃秷鎭 INSERT IGNORE 闈欓粯涓㈠純銆?
            String rid = requestId == null ? "" : requestId.trim();
            String eventId = rid.isBlank() ? ("notify:like:" + socialIdPort.nextId()) : ("notify:like:" + rid);
            InteractionNotifyEvent event = new InteractionNotifyEvent();
            event.setEventType(EventType.LIKE_ADDED);
            event.setEventId(eventId);
            event.setRequestId(rid.isBlank() ? null : rid);
            event.setFromUserId(fromUserId);
            event.setTargetType(target == null || target.getTargetType() == null ? null : target.getTargetType().getCode());
            event.setTargetId(target == null ? null : target.getTargetId());
            if (target != null && target.getTargetType() == ReactionTargetTypeEnumVO.POST) {
                event.setPostId(target.getTargetId());
            }
            event.setTsMs(socialIdPort.now());
            reactionNotifyMqPort.publish(event);
        } catch (Exception e) {
            log.warn("publish InteractionNotifyEvent LIKE_ADDED failed, requestId={}, fromUserId={}, target={}", requestId, fromUserId, target, e);
        }
    }

    private void publishRecommendUnlike(String requestId, Long fromUserId, ReactionTargetVO target) {
        if (target == null || target.getTargetType() != ReactionTargetTypeEnumVO.POST) {
            return;
        }
        try {
            // unlike 涓?recommend feedback 鏄袱鏉＄嫭绔嬫秷鎭細閬垮厤涓?LikeUnlike 鐨?outbox eventId 鍐茬獊銆?
            String rid = requestId == null ? "" : requestId.trim();
            String eventId = rid.isBlank() ? ("recommend:unlike:" + socialIdPort.nextId()) : ("recommend:unlike:" + rid);
            RecommendFeedbackEvent event = new RecommendFeedbackEvent();
            event.setEventId(eventId);
            event.setFromUserId(fromUserId);
            event.setPostId(target.getTargetId());
            event.setFeedbackType("unlike");
            event.setTsMs(socialIdPort.now());
            reactionRecommendFeedbackMqPort.publish(event);
        } catch (Exception e) {
            log.warn("publish RecommendFeedbackEvent unlike failed, requestId={}, fromUserId={}, target={}", requestId, fromUserId, target, e);
        }
    }

    private CommentBriefVO loadCommentBrief(Long commentId) {
        if (commentId == null) {
            return null;
        }
        try {
            return commentRepository.getBrief(commentId);
        } catch (Exception e) {
            // Best-effort lookup: comment might be deleted/hidden or DAO might transiently fail.
            if (log.isDebugEnabled()) {
                log.debug("load comment postId failed, commentId={}", commentId, e);
            }
        }
        return null;
    }

    private void incrementLikeReceivedBestEffort(String requestId, Long ownerUserId, long delta) {
        if (ownerUserId == null || delta == 0) {
            return;
        }
        try {
            userCounterPort.increment(ownerUserId, UserCounterType.LIKE_RECEIVED, delta);
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("increment like received failed, requestId={}, ownerUserId={}, delta={}",
                        requestId, ownerUserId, delta, e);
            }
        }
    }

    private void publishCommentLikeChanged(String requestId, Long rootCommentId, Long postId, long delta, long nowMs) {
        try {
            String rid = requestId == null ? "" : requestId.trim();
            String eventId = rid.isBlank()
                    ? (EVT_COMMENT_LIKE_CHANGED_PREFIX + "gen:" + socialIdPort.nextId())
                    : (EVT_COMMENT_LIKE_CHANGED_PREFIX + rid);
            CommentLikeChangedEvent event = new CommentLikeChangedEvent();
            event.setEventId(eventId);
            event.setRootCommentId(rootCommentId);
            event.setPostId(postId);
            event.setDelta(delta);
            event.setTsMs(nowMs);
            reactionCommentLikeChangedMqPort.publish(event);
        } catch (Exception e) {
            log.warn("publish CommentLikeChangedEvent failed, rootCommentId={}, postId={}, delta={}", rootCommentId, postId, delta, e);
        }
    }

    private void publishEventLogBestEffort(String requestId,
                                           Long userId,
                                           ReactionTargetVO target,
                                           int desiredState,
                                           int delta,
                                           long nowMs) {
        if (delta == 0 || target == null || target.getTargetType() == null || target.getReactionType() == null) {
            return;
        }
        try {
            reactionEventLogMqPort.publish(ReactionEventLogRecordVO.builder()
                    .eventId(requestId)
                    .targetType(target.getTargetType().getCode())
                    .targetId(target.getTargetId())
                    .reactionType(target.getReactionType().getCode())
                    .userId(userId)
                    .desiredState(desiredState)
                    .delta(delta)
                    .eventTime(nowMs)
                    .build());
        } catch (Exception e) {
            log.warn("publish reaction event log failed, requestId={}, userId={}, target={}, delta={}", requestId, userId, target, delta, e);
        }
    }
}
