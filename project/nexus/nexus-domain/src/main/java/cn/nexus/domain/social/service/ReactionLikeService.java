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
 * 点赞子域服务实现：POST 维持现状；COMMENT 改成 DB 真相 + Redis 加速。
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

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReactionResultVO applyReaction(Long userId, ReactionTargetVO target, ReactionActionEnumVO action, String requestId) {
        requireNonNull(userId, "userId");
        requireTarget(target);
        requireNonNull(action, "action");
        requireLikeOnly(target);

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
        int affected = desiredState == 1 ? reactionRepository.insertIgnore(target, userId) : reactionRepository.deleteOne(target, userId);
        int delta = desiredState == 1 ? (affected > 0 ? 1 : 0) : (affected > 0 ? -1 : 0);
        if (delta != 0) {
            reactionRepository.incrCount(target, (long) delta);
        }
        long currentCount = reactionRepository.getCount(target);

        afterCommit(() -> {
            try {
                reactionCachePort.setState(userId, target, desiredState == 1);
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
     * 兼容保留：旧 COMMENT 延迟同步链路的收口方法。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void syncTarget(ReactionTargetVO target) {
        requireTarget(target);
        requireLikeOnly(target);

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
            InteractionNotifyEvent event = new InteractionNotifyEvent();
            event.setEventType(EventType.LIKE_ADDED);
            event.setEventId(requestId);
            event.setRequestId(requestId);
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
            RecommendFeedbackEvent event = new RecommendFeedbackEvent();
            event.setEventId(requestId);
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
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
