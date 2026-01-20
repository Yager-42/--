package cn.nexus.domain.social.service;

import cn.nexus.domain.social.adapter.port.IReactionCachePort;
import cn.nexus.domain.social.adapter.port.IReactionDelayPort;
import cn.nexus.domain.social.adapter.port.ISocialIdPort;
import cn.nexus.domain.social.adapter.repository.IReactionRepository;
import cn.nexus.domain.social.model.valobj.*;
import cn.nexus.types.enums.ResponseCode;
import cn.nexus.types.exception.AppException;
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

    /**
     * 在线写：只做 Redis 原子更新 + 必要时投递延迟消息 + 打结构化日志。
     */
    @Override
    public ReactionResultVO applyReaction(Long userId, ReactionTargetVO target, ReactionActionEnumVO action, String requestId) {
        requireNonNull(userId, "userId");
        requireTarget(target);
        requireNonNull(action, "action");
        requireLikeOnly(target);

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

        log.info(buildEventJson(rid, userId, target, action, desiredState, delta, currentCount, firstPending));
        return ReactionResultVO.builder().requestId(rid).currentCount(currentCount).success(true).build();
    }

    /**
     * 读接口：以 Redis 为准查询 state + 当前计数。
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
}
