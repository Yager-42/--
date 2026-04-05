package cn.nexus.domain.social.adapter.port;

import cn.nexus.domain.social.model.valobj.ReactionApplyResultVO;
import cn.nexus.domain.social.model.valobj.ReactionTargetVO;
import java.util.List;
import java.util.Map;

/**
 * 点赞缓存端口（Redis）。
 *
 * <p>该端口只描述“点赞相关状态”在缓存层的读写语义，不暴露具体的 Key 设计与脚本实现细节。</p>
 *
 * @author {$authorName}
 * @since 2026-01-20
 */
public interface IReactionCachePort {

    /**
     * 原子应用一次点赞状态变更，并返回变更后的计数与增量信息。
     *
     * <p>{@code desiredState} 约定：1 表示点赞，0 表示取消点赞。</p>
     *
     * @param userId 用户 ID {@link Long}
     * @param target 点赞目标 {@link ReactionTargetVO}
     * @param desiredState 期望状态（1 点赞 / 0 取消） {@code int}
     * @param syncTtlSec 同步标记 TTL（秒） {@code int}
     * @return 原子应用结果 {@link ReactionApplyResultVO}
     */
    ReactionApplyResultVO applyAtomic(Long userId, ReactionTargetVO target, int desiredState, int syncTtlSec);

    /**
     * 对 ops 变更集合做快照（用于异步落库）：将 opsKey 原子切换到 processingKey。
     *
     * @param target 点赞目标 {@link ReactionTargetVO}
     * @return 是否成功创建快照 {@code boolean}
     */
    boolean snapshotOps(ReactionTargetVO target);

    /**
     * 读取 ops 快照。
     *
     * @param target 点赞目标 {@link ReactionTargetVO}
     * @return 变更快照（userId -&gt; desiredState） {@link Map}
     */
    Map<Long, Integer> readOpsSnapshot(ReactionTargetVO target);

    /**
     * 清理 ops 快照（processingKey）。
     *
     * @param target 点赞目标 {@link ReactionTargetVO}
     */
    void clearOpsSnapshot(ReactionTargetVO target);

    /**
     * 获取点赞计数（优先缓存，必要时回源并回填）。
     *
     * @param target 点赞目标 {@link ReactionTargetVO}
     * @return 点赞数量 {@code long}
     */
    long getCount(ReactionTargetVO target);

    /**
     * 批量获取点赞计数。
     *
     * @param targets 点赞目标列表 {@link List}
     * @return 计数结果（key 为 target.hashTag()） {@link Map}
     */
    Map<String, Long> batchGetCount(List<ReactionTargetVO> targets);

    /**
     * 从 Redis 获取计数：miss 会回源 DB 并回填（用于保证异步落库/聚合链路拿到真值）。
     *
     * <p>注意：该方法名历史原因保留，语义与 {@link #getCount(ReactionTargetVO)} 类似，但不走热点 L1 缓存。</p>
     *
     * @param target 点赞目标 {@link ReactionTargetVO}
     * @return 点赞数量 {@code long}
     */
    long getCountFromRedis(ReactionTargetVO target);

    /**
     * 查询用户对某目标的点赞状态。
     *
     * @param userId 用户 ID {@link Long}
     * @param target 点赞目标 {@link ReactionTargetVO}
     * @return 是否点赞 {@code boolean}
     */
    boolean getState(Long userId, ReactionTargetVO target);

    /**
     * 判断用户所在的 bitmap 分片是否存在（用于快速判断是否需要回源）。
     *
     * @param userId 用户 ID {@link Long}
     * @param target 点赞目标 {@link ReactionTargetVO}
     * @return 是否存在 {@code boolean}
     */
    boolean bitmapShardExists(Long userId, ReactionTargetVO target);

    /**
     * 设置用户对某目标的点赞状态（通常用于补偿/修复，不走在线强一致主链路）。
     *
     * @param userId 用户 ID {@link Long}
     * @param target 点赞目标 {@link ReactionTargetVO}
     * @param state 点赞状态 {@code boolean}
     */
    void setState(Long userId, ReactionTargetVO target, boolean state);

    /**
     * 设置点赞计数（通常用于补偿/修复）。
     *
     * @param target 点赞目标 {@link ReactionTargetVO}
     * @param count 点赞数量 {@code long}
     */
    void setCount(ReactionTargetVO target, long count);

    /**
     * 获取计数窗口大小（毫秒）。
     *
     * @param target 点赞目标 {@link ReactionTargetVO}
     * @param defaultMs 默认窗口大小（毫秒） {@code long}
     * @return 窗口大小（毫秒） {@code long}
     */
    long getWindowMs(ReactionTargetVO target, long defaultMs);

    /**
     * 设置同步待处理标记（syncKey），供异步任务扫描。
     *
     * @param target 点赞目标 {@link ReactionTargetVO}
     * @param ttlSec 标记 TTL（秒） {@code int}
     */
    void setSyncPending(ReactionTargetVO target, int ttlSec);

    /**
     * 清理同步标记（syncKey）。
     *
     * @param target 点赞目标 {@link ReactionTargetVO}
     */
    void clearSyncFlag(ReactionTargetVO target);

    /**
     * 记录最近一次同步时间（毫秒）。
     *
     * @param target 点赞目标 {@link ReactionTargetVO}
     * @param epochMillis 时间戳（毫秒） {@code long}
     */
    void setLastSyncTime(ReactionTargetVO target, long epochMillis);

    /**
     * 判断是否存在 ops 变更集合（用于决定是否需要触发快照/同步）。
     *
     * @param target 点赞目标 {@link ReactionTargetVO}
     * @return 是否存在 {@code boolean}
     */
    boolean existsOps(ReactionTargetVO target);
}
