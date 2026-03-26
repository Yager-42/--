package cn.nexus.domain.social.service;

import cn.nexus.domain.social.adapter.port.IInteractionNotifyEventPort;
import cn.nexus.domain.social.adapter.port.ILikeUnlikeEventPort;
import cn.nexus.domain.social.adapter.port.IPostAuthorPort;
import cn.nexus.domain.social.adapter.port.IPostLikeCachePort;
import cn.nexus.domain.social.adapter.port.IReactionCachePort;
import cn.nexus.domain.social.adapter.port.IReactionDelayPort;
import cn.nexus.domain.social.adapter.port.IRecommendFeedbackEventPort;
import cn.nexus.domain.social.adapter.port.ISocialIdPort;
import cn.nexus.domain.social.adapter.repository.IReactionRepository;
import cn.nexus.domain.social.adapter.repository.IUserBaseRepository;
import cn.nexus.domain.social.model.valobj.ReactionActionEnumVO;
import cn.nexus.domain.social.model.valobj.ReactionLikerVO;
import cn.nexus.domain.social.model.valobj.ReactionLikersVO;
import cn.nexus.domain.social.model.valobj.ReactionResultVO;
import cn.nexus.domain.social.model.valobj.ReactionStateVO;
import cn.nexus.domain.social.model.valobj.ReactionTargetTypeEnumVO;
import cn.nexus.domain.social.model.valobj.ReactionTargetVO;
import cn.nexus.domain.social.model.valobj.ReactionTypeEnumVO;
import cn.nexus.domain.social.model.valobj.ReactionUserEdgeVO;
import cn.nexus.domain.social.model.valobj.UserBriefVO;
import cn.nexus.domain.social.model.valobj.like.PostLikeApplyResultVO;
import cn.nexus.domain.social.model.valobj.like.PostLikeApplyStatusEnumVO;
import cn.nexus.domain.social.model.valobj.like.PostLikeCacheStateVO;
import cn.nexus.types.enums.ResponseCode;
import cn.nexus.types.event.interaction.EventType;
import cn.nexus.types.event.interaction.InteractionNotifyEvent;
import cn.nexus.types.event.interaction.LikeUnlikePostEvent;
import cn.nexus.types.event.recommend.RecommendFeedbackEvent;
import cn.nexus.types.exception.AppException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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

    private static final int SYNC_TTL_SEC = 600;
    private static final long DEFAULT_WINDOW_MS = 300_000L;

    private final IReactionCachePort reactionCachePort;
    private final IReactionDelayPort reactionDelayPort;
    private final IReactionRepository reactionRepository;
    private final ISocialIdPort socialIdPort;
    private final IInteractionNotifyEventPort interactionNotifyEventPort;
    private final IRecommendFeedbackEventPort recommendFeedbackEventPort;
    private final IPostLikeCachePort postLikeCachePort;
    private final ILikeUnlikeEventPort likeUnlikeEventPort;
    private final IPostAuthorPort postAuthorPort;
    private final IUserBaseRepository userBaseRepository;

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
    @Transactional(rollbackFor = Exception.class)
    public ReactionResultVO applyReaction(Long userId, ReactionTargetVO target, ReactionActionEnumVO action, String requestId) {
        requireNonNull(userId, "userId");
        requireTarget(target);
        requireNonNull(action, "action");
        requireLikeOnly(target);

        // `POST` 和 `COMMENT` 走不同的一致性策略：前者偏缓存，后者偏数据库真相。
        if (target.getTargetType() == ReactionTargetTypeEnumVO.POST) {
            return applyPostLike(userId, target, action, requestId);
        }
        return applyCommentLike(userId, target, action, requestId);
    }

    private ReactionResultVO applyPostLike(Long userId, ReactionTargetVO target, ReactionActionEnumVO action, String requestId) {
        String rid = (requestId == null || requestId.isBlank()) ? ("rid-" + socialIdPort.nextId()) : requestId.trim();
        long nowMs = socialIdPort.now();
        Long postId = target == null ? null : target.getTargetId();
        Long creatorId = postId == null ? null : postAuthorPort.getPostAuthorId(postId);
        if (postId == null || creatorId == null) {
            throw new AppException(ResponseCode.NOT_FOUND.getCode(), ResponseCode.NOT_FOUND.getInfo());
        }

        // 先把帖子和作者的计数缓存预热出来，后面的增减才能尽量走热路径。
        try {
            ReactionTargetVO cntTarget = ReactionTargetVO.builder()
                    .targetType(ReactionTargetTypeEnumVO.POST)
                    .targetId(postId)
                    .reactionType(ReactionTypeEnumVO.LIKE)
                    .build();
            reactionCachePort.getCountFromRedis(cntTarget);
        } catch (Exception ignored) {
        }
        try {
            ReactionTargetVO creatorTarget = ReactionTargetVO.builder()
                    .targetType(ReactionTargetTypeEnumVO.USER)
                    .targetId(creatorId)
                    .reactionType(ReactionTypeEnumVO.LIKE)
                    .build();
            reactionCachePort.getCountFromRedis(creatorTarget);
        } catch (Exception ignored) {
        }

        PostLikeApplyResultVO apply;
        if (action.desiredState() == 1) {
            apply = postLikeCachePort.tryLike(userId, postId, nowMs);
            if (apply != null && apply.getStatus() != null && apply.getStatus() == PostLikeApplyStatusEnumVO.NEED_DB_CHECK.getCode()) {
                boolean exists = reactionRepository.exists(target, userId);
                if (!exists) {
                    apply = postLikeCachePort.forceLike(userId, postId, nowMs);
                } else {
                    apply = PostLikeApplyResultVO.builder().status(PostLikeApplyStatusEnumVO.ALREADY.getCode()).delta(0).currentCount(apply.getCurrentCount()).build();
                }
            }
        } else {
            apply = postLikeCachePort.tryUnlike(userId, postId, nowMs);
            if (apply != null && apply.getStatus() != null && apply.getStatus() == PostLikeApplyStatusEnumVO.NEED_DB_CHECK.getCode()) {
                boolean exists = reactionRepository.exists(target, userId);
                if (exists) {
                    apply = postLikeCachePort.forceUnlike(userId, postId, nowMs);
                } else {
                    apply = PostLikeApplyResultVO.builder().status(PostLikeApplyStatusEnumVO.ALREADY.getCode()).delta(0).currentCount(apply.getCurrentCount()).build();
                }
            }
        }

        int delta = apply == null || apply.getDelta() == null ? 0 : apply.getDelta();
        long currentCount = apply == null || apply.getCurrentCount() == null ? 0L : apply.getCurrentCount();

        if (delta != 0) {
            // 作者维度的获赞总数和帖子点赞数不是一张表，这里顺手把作者侧缓存也推进去。
            try {
                postLikeCachePort.applyCreatorLikeDelta(creatorId, delta);
            } catch (Exception ignored) {
            }
        }

        if (delta == 1) {
            LikeUnlikePostEvent event = new LikeUnlikePostEvent();
            event.setEventId(rid);
            event.setUserId(userId);
            event.setPostId(postId);
            event.setPostCreatorId(creatorId == null ? 0L : creatorId);
            event.setType(1);
            event.setCreateTime(nowMs);
            likeUnlikeEventPort.publishLike(event);
            publishNotifyLikeAdded(rid, userId, target);
        }

        if (delta == -1) {
            LikeUnlikePostEvent event = new LikeUnlikePostEvent();
            event.setEventId(rid);
            event.setUserId(userId);
            event.setPostId(postId);
            event.setPostCreatorId(creatorId == null ? 0L : creatorId);
            event.setType(0);
            event.setCreateTime(nowMs);
            likeUnlikeEventPort.publishUnlike(event);
            publishRecommendUnlike(rid, userId, target);
        }

        log.info(buildEventJson(rid, userId, target, action, action.desiredState(), delta, currentCount, false));
        return ReactionResultVO.builder()
                .requestId(rid)
                .currentCount(currentCount)
                .delta(delta)
                .success(true)
                .build();
    }

    private ReactionResultVO applyCommentLike(Long userId, ReactionTargetVO target, ReactionActionEnumVO action, String requestId) {
        String rid = (requestId == null || requestId.isBlank()) ? ("rid-" + socialIdPort.nextId()) : requestId.trim();
        int desiredState = action.desiredState();
        // 评论点赞以数据库为真相：先落边，再改聚合计数，最后事务后刷新缓存。
        int affected = desiredState == 1 ? reactionRepository.insertIgnore(target, userId) : reactionRepository.deleteOne(target, userId);
        int delta = desiredState == 1 ? (affected > 0 ? 1 : 0) : (affected > 0 ? -1 : 0);
        if (delta != 0) {
            reactionRepository.incrCount(target, (long) delta);
        }
        long currentCount = reactionRepository.getCount(target);

        afterCommit(() -> {
            try {
                if (delta == 1) {
                    reactionCachePort.setState(userId, target, true);
                } else if (delta == -1 && reactionCachePort.bitmapShardExists(userId, target)) {
                    reactionCachePort.setState(userId, target, false);
                }
                reactionCachePort.setCount(target, currentCount);
            } catch (Exception e) {
                log.warn("comment reaction redis refresh failed, userId={}, target={}", userId, target, e);
            }
        });

        if (delta == 1) {
            publishNotifyLikeAdded(rid, userId, target);
        }

        log.info(buildEventJson(rid, userId, target, action, desiredState, delta, currentCount, false));
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

        if (target.getTargetType() == ReactionTargetTypeEnumVO.POST) {
            PostLikeCacheStateVO cache = postLikeCachePort.cacheState(userId, target.getTargetId());
            if (cache != null && cache.getLiked() != null) {
                long cnt = cache.getCurrentCount() == null ? 0L : cache.getCurrentCount();
                return ReactionStateVO.builder().state(Boolean.TRUE.equals(cache.getLiked())).currentCount(Math.max(0L, cnt)).build();
            }
            boolean exists = reactionRepository.exists(target, userId);
            long cnt = cache == null || cache.getCurrentCount() == null ? 0L : cache.getCurrentCount();
            return ReactionStateVO.builder().state(exists).currentCount(Math.max(0L, cnt)).build();
        }

        boolean state;
        if (reactionCachePort.bitmapShardExists(userId, target)) {
            state = reactionCachePort.getState(userId, target);
        } else {
            state = reactionRepository.exists(target, userId);
            if (state) {
                reactionCachePort.setState(userId, target, true);
            }
        }
        long cnt = reactionCachePort.getCount(target);
        return ReactionStateVO.builder().state(state).currentCount(cnt).build();
    }

    /**
     * 查询点赞人列表。
     *
     * @param target 点赞目标，类型：{@link ReactionTargetVO}
     * @param cursor 翻页游标，类型：{@link String}
     * @param limit 页大小，类型：{@link Integer}
     * @return 点赞人分页结果，类型：{@link ReactionLikersVO}
     */
    @Override
    public ReactionLikersVO queryLikers(ReactionTargetVO target, String cursor, Integer limit) {
        requireTarget(target);
        requireLikeOnly(target);
        if (target.getTargetType() != ReactionTargetTypeEnumVO.COMMENT) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "点赞列表当前仅支持 COMMENT");
        }
        int normalizedLimit = limit == null || limit <= 0 ? 20 : Math.min(limit, 50);
        List<ReactionUserEdgeVO> edges = reactionRepository.pageUserEdgesByTarget(target, cursor, normalizedLimit);
        if (edges == null || edges.isEmpty()) {
            return ReactionLikersVO.builder().items(List.of()).nextCursor(null).build();
        }

        Set<Long> userIds = new LinkedHashSet<>();
        for (ReactionUserEdgeVO edge : edges) {
            if (edge != null && edge.getUserId() != null) {
                userIds.add(edge.getUserId());
            }
        }
        Map<Long, UserBriefVO> briefMap = new HashMap<>();
        for (UserBriefVO brief : userBaseRepository.listByUserIds(new ArrayList<>(userIds))) {
            if (brief != null && brief.getUserId() != null) {
                briefMap.put(brief.getUserId(), brief);
            }
        }

        List<ReactionLikerVO> items = new ArrayList<>(edges.size());
        for (ReactionUserEdgeVO edge : edges) {
            if (edge == null || edge.getUserId() == null) {
                continue;
            }
            UserBriefVO brief = briefMap.get(edge.getUserId());
            items.add(ReactionLikerVO.builder()
                    .userId(edge.getUserId())
                    .nickname(brief == null ? null : brief.getNickname())
                    .avatarUrl(brief == null ? null : brief.getAvatarUrl())
                    .likedAt(edge.getLikedAt())
                    .build());
        }
        ReactionLikerVO last = items.isEmpty() ? null : items.get(items.size() - 1);
        String nextCursor = last == null || last.getLikedAt() == null || last.getUserId() == null
                ? null
                : last.getLikedAt() + ":" + last.getUserId();
        return ReactionLikersVO.builder().items(items).nextCursor(nextCursor).build();
    }

    /**
     * 兼容保留：收口旧的评论点赞延迟同步链路。
     *
     * @param target 同步目标，类型：{@link ReactionTargetVO}
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void syncTarget(ReactionTargetVO target) {
        requireTarget(target);
        requireLikeOnly(target);

        // 没有快照就直接清标记返回，避免空转调度。
        boolean hasSnapshot = reactionCachePort.snapshotOps(target);
        if (!hasSnapshot) {
            reactionCachePort.clearSyncFlag(target);
            return;
        }

        Map<Long, Integer> ops = reactionCachePort.readOpsSnapshot(target);
        List<Long> addUserIds = new ArrayList<>();
        List<Long> removeUserIds = new ArrayList<>();
        if (ops != null && !ops.isEmpty()) {
            for (Map.Entry<Long, Integer> e : ops.entrySet()) {
                Long userId = e.getKey();
                Integer desired = e.getValue();
                if (userId == null || desired == null) {
                    continue;
                }
                if (desired == 1) {
                    addUserIds.add(userId);
                } else {
                    removeUserIds.add(userId);
                }
            }
        }

        // 先按快照回放增删边，再把计数总值刷回数据库。
        if (!addUserIds.isEmpty()) {
            reactionRepository.batchUpsert(target, addUserIds);
        }
        if (!removeUserIds.isEmpty()) {
            reactionRepository.batchDelete(target, removeUserIds);
        }

        long cnt = reactionCachePort.getCountFromRedis(target);
        reactionRepository.upsertCount(target, cnt);

        reactionCachePort.clearOpsSnapshot(target);
        reactionCachePort.setLastSyncTime(target, socialIdPort.now());
        reactionCachePort.clearSyncFlag(target);

        if (!reactionCachePort.existsOps(target)) {
            return;
        }

        // 快照清完后如果窗口里又进了新操作，就重新挂一次延迟任务继续收敛。
        reactionCachePort.setSyncPending(target, SYNC_TTL_SEC);
        long delayMs = reactionCachePort.getWindowMs(target, DEFAULT_WINDOW_MS);
        reactionDelayPort.sendDelay(target, delayMs);
    }

    private void requireTarget(ReactionTargetVO target) {
        requireNonNull(target, "target");
        requireNonNull(target.getTargetType(), "targetType");
        requireNonNull(target.getTargetId(), "targetId");
        requireNonNull(target.getReactionType(), "reactionType");
    }

    private void requireLikeOnly(ReactionTargetVO target) {
        if (target.getReactionType() != ReactionTypeEnumVO.LIKE) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "当前仅支持 LIKE");
        }
    }

    private void requireNonNull(Object v, String name) {
        if (v == null) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "非法参数：" + name);
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
            // ReliableMqOutbox 的 event_id 是全局唯一：同一个 requestId 可能同时触发多条业务消息（LikeUnlike + Notify）。
            // 如果复用 requestId 作为 eventId，会导致第二条消息被 INSERT IGNORE 静默丢弃。
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
            interactionNotifyEventPort.publish(event);
        } catch (Exception e) {
            log.warn("publish InteractionNotifyEvent LIKE_ADDED failed, requestId={}, fromUserId={}, target={}", requestId, fromUserId, target, e);
        }
    }

    private void publishRecommendUnlike(String requestId, Long fromUserId, ReactionTargetVO target) {
        if (target == null || target.getTargetType() != ReactionTargetTypeEnumVO.POST) {
            return;
        }
        try {
            // unlike 与 recommend feedback 是两条独立消息：避免与 LikeUnlike 的 outbox eventId 冲突。
            String rid = requestId == null ? "" : requestId.trim();
            String eventId = rid.isBlank() ? ("recommend:unlike:" + socialIdPort.nextId()) : ("recommend:unlike:" + rid);
            RecommendFeedbackEvent event = new RecommendFeedbackEvent();
            event.setEventId(eventId);
            event.setFromUserId(fromUserId);
            event.setPostId(target.getTargetId());
            event.setFeedbackType("unlike");
            event.setTsMs(socialIdPort.now());
            recommendFeedbackEventPort.publish(event);
        } catch (Exception e) {
            log.warn("publish RecommendFeedbackEvent unlike failed, requestId={}, fromUserId={}, target={}", requestId, fromUserId, target, e);
        }
    }

    private void afterCommit(Runnable action) {
        if (action == null) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            /**
             * 执行 afterCommit 逻辑。
             *
             */
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
