package cn.nexus.domain.social.adapter.port;

import cn.nexus.domain.social.model.valobj.ReactionApplyResultVO;
import cn.nexus.domain.social.model.valobj.ReactionTargetVO;

import java.util.Map;

/**
 * 点赞缓存端口（Redis）。
 *
 * <p>Domain 不直接依赖 Redis 实现细节，通过端口隔离。</p>
 *
 * @author codex
 * @since 2026-01-20
 */
public interface IReactionCachePort {

    /**
     * 在线写入：Redis 原子更新（Bitmap 去重 + Count 计数 + ops 记录 + sync 标记）。
     *
     * @param userId     用户 ID {@link Long}
     * @param target     点赞目标 {@link ReactionTargetVO}
     * @param desiredState 想要的最终状态，1=点赞，0=取消 {@code int}
     * @param syncTtlSec syncKey 过期时间（秒）{@code int}
     * @return {@link ReactionApplyResultVO}
     */
    ReactionApplyResultVO applyAtomic(Long userId, ReactionTargetVO target, int desiredState, int syncTtlSec);

    /**
     * 将 opsKey 快照到 processingKey（或 processingKey 已存在则直接复用快照）。
     *
     * @param target 点赞目标 {@link ReactionTargetVO}
     * @return {@code boolean} true=processingKey 可读取，false=没有任何 ops
     */
    boolean snapshotOps(ReactionTargetVO target);

    /**
     * 读取 processingKey 的快照。
     *
     * @param target 点赞目标 {@link ReactionTargetVO}
     * @return userId -> desiredState(0/1)
     */
    Map<Long, Integer> readOpsSnapshot(ReactionTargetVO target);

    /**
     * 删除 processingKey 快照。
     *
     * @param target 点赞目标 {@link ReactionTargetVO}
     */
    void clearOpsSnapshot(ReactionTargetVO target);

    /**
     * 读取近实时计数（热点时走 L1 缓存）。
     *
     * @param target 点赞目标 {@link ReactionTargetVO}
     * @return {@code long}
     */
    long getCount(ReactionTargetVO target);

    /**
     * 读取 Redis 原始计数（不走 L1）：用于同步链路“对齐 DB”。
     *
     * @param target 点赞目标 {@link ReactionTargetVO}
     * @return {@code long}
     */
    long getCountFromRedis(ReactionTargetVO target);

    /**
     * 查询用户是否点过赞（以 Redis bitmap 为准）。
     *
     * @param userId 用户 ID {@link Long}
     * @param target 点赞目标 {@link ReactionTargetVO}
     * @return {@code boolean}
     */
    boolean getState(Long userId, ReactionTargetVO target);

    /**
     * 获取动态窗口（毫秒），不存在则返回 defaultMs。
     *
     * @param target    点赞目标 {@link ReactionTargetVO}
     * @param defaultMs 默认窗口毫秒 {@code long}
     * @return {@code long}
     */
    long getWindowMs(ReactionTargetVO target, long defaultMs);

    /**
     * 标记 sync pending（用于并发写入时再触发一次同步）。
     *
     * @param target 点赞目标 {@link ReactionTargetVO}
     * @param ttlSec TTL 秒 {@code int}
     */
    void setSyncPending(ReactionTargetVO target, int ttlSec);

    /**
     * 清理 sync 标记。
     *
     * @param target 点赞目标 {@link ReactionTargetVO}
     */
    void clearSyncFlag(ReactionTargetVO target);

    /**
     * 记录最后同步时间。
     *
     * @param target      点赞目标 {@link ReactionTargetVO}
     * @param epochMillis 毫秒时间戳 {@code long}
     */
    void setLastSyncTime(ReactionTargetVO target, long epochMillis);

    /**
     * 判断是否还有新的 opsKey（同步期间继续产生的新写入）。
     *
     * @param target 点赞目标 {@link ReactionTargetVO}
     * @return {@code boolean}
     */
    boolean existsOps(ReactionTargetVO target);
}
