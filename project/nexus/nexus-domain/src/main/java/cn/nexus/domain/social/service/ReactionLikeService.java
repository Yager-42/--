package cn.nexus.domain.social.service;

import cn.nexus.domain.social.adapter.port.IReactionCachePort;
import cn.nexus.domain.social.adapter.port.IReactionDelayPort;
import cn.nexus.domain.social.adapter.port.ILikeUnlikeEventPort;
import cn.nexus.domain.social.adapter.port.IPostAuthorPort;
import cn.nexus.domain.social.adapter.port.IPostLikeCachePort;
import cn.nexus.domain.social.adapter.port.IInteractionNotifyEventPort;
import cn.nexus.domain.social.adapter.port.IRecommendFeedbackEventPort;
import cn.nexus.domain.social.adapter.port.ISocialIdPort;
import cn.nexus.domain.social.adapter.repository.IReactionRepository;
import cn.nexus.domain.social.model.valobj.*;
import cn.nexus.domain.social.model.valobj.like.PostLikeApplyResultVO;
import cn.nexus.domain.social.model.valobj.like.PostLikeApplyStatusEnumVO;
import cn.nexus.domain.social.model.valobj.like.PostLikeCacheStateVO;
import cn.nexus.types.enums.ResponseCode;
import cn.nexus.types.exception.AppException;
import cn.nexus.types.event.interaction.EventType;
import cn.nexus.types.event.interaction.InteractionNotifyEvent;
import cn.nexus.types.event.interaction.LikeUnlikePostEvent;
import cn.nexus.types.event.recommend.RecommendFeedbackEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 点赞子域服务实现：在线写入 Redis + 延迟落库 MySQL + 状态查询。
 *
 * <p>重要语义：success=true 仅代表“Redis 原子更新接住并返回”，不代表 DB 已一致。</p>
 *
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

    /**
     * 在线写：只做 Redis 原子更新 + 必要时投递延迟消息 + 打结构化日志。
     */
    @Override
    public ReactionResultVO applyReaction(Long userId, ReactionTargetVO target, ReactionActionEnumVO action, String requestId) {
        requireNonNull(userId, "userId");
        requireTarget(target);
        requireNonNull(action, "action");
        requireLikeOnly(target);

        if (target.getTargetType() == ReactionTargetTypeEnumVO.POST) {
            return applyPostLike(userId, target, action, requestId);
        }

        String rid = (requestId == null || requestId.isBlank()) ? ("rid-" + socialIdPort.nextId()) : requestId.trim();
        int desiredState = action.desiredState();

        ReactionApplyResultVO res = reactionCachePort.applyAtomic(userId, target, desiredState, SYNC_TTL_SEC);
        boolean firstPending = res != null && res.isFirstPending();
        long currentCount = res == null || res.getCurrentCount() == null ? 0L : res.getCurrentCount();
        int delta = res == null || res.getDelta() == null ? 0 : res.getDelta();

        if (firstPending) {
            long delayMs = reactionCachePort.getWindowMs(target, DEFAULT_WINDOW_MS);
            reactionDelayPort.sendDelay(target, delayMs);
        }

        // 仅 delta=+1 才代表“真的新增点赞”，通知旁路只吃这个黄金信号。
        if (delta == 1) {
            publishNotifyLikeAdded(rid, userId, target);
        }
        // 撤销点赞：C 通道反馈（unlike），避免污染通知语义。
        if (delta == -1) {
            publishRecommendUnlike(rid, userId, target);
        }

        log.info(buildEventJson(rid, userId, target, action, desiredState, delta, currentCount, firstPending));
        return ReactionResultVO.builder()
                .requestId(rid)
                .currentCount(currentCount)
                .delta(delta)
                .success(true)
                .build();
    }

    private ReactionResultVO applyPostLike(Long userId, ReactionTargetVO target, ReactionActionEnumVO action, String requestId) {
        String rid = (requestId == null || requestId.isBlank()) ? ("rid-" + socialIdPort.nextId()) : requestId.trim();
        long nowMs = socialIdPort.now();
        Long postId = target == null ? null : target.getTargetId();
        Long creatorId = postId == null ? null : postAuthorPort.getPostAuthorId(postId);
        if (postId == null || creatorId == null) {
            throw new AppException(ResponseCode.NOT_FOUND.getCode(), ResponseCode.NOT_FOUND.getInfo());
        }

        // 防御：Redis cntKey 丢失时先用 DB count 表回填基线，避免从 0 开始导致“计数跳变”。
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

        // 派生计数：作者收到的点赞数（与关系落库/计数落库链路解耦）。
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

    /**
     * 读接口：以 Redis 为准查询 state + 当前计数。
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

        boolean state = reactionCachePort.getState(userId, target);
        long cnt = reactionCachePort.getCount(target);
        return ReactionStateVO.builder().state(state).currentCount(cnt).build();
    }

    /**
     * 延迟落库：ops 快照 + 批量写事实 + 覆盖写计数 + 清标记；并发产生新 ops 则再投递一次。
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

        // 同步期间又产生了新 ops：再触发一次延迟同步（避免丢更新）。
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

    /**
     * 手工拼 JSON：避免 domain 层引入额外序列化依赖。
     */
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
            // 用 rid 作为 eventId，便于排障与幂等去重（如需要）。
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
}
