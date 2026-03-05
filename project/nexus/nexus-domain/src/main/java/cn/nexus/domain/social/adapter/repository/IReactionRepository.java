package cn.nexus.domain.social.adapter.repository;

import cn.nexus.domain.social.model.valobj.ReactionTargetVO;

import java.util.List;

/**
 * 点赞仓储接口（事实表 + 计数表）。
 *
 * @author codex
 * @since 2026-01-20
 */
public interface IReactionRepository {

    /**
     * 批量 upsert 事实（点赞存在）。
     *
     * @param target  点赞目标 {@link ReactionTargetVO}
     * @param userIds 用户 ID 列表 {@link List}<{@link Long}>
     */
    void batchUpsert(ReactionTargetVO target, List<Long> userIds);

    /**
     * 批量删除事实（取消点赞）。
     *
     * @param target  点赞目标 {@link ReactionTargetVO}
     * @param userIds 用户 ID 列表 {@link List}<{@link Long}>
     */
    void batchDelete(ReactionTargetVO target, List<Long> userIds);

    /**
     * 覆盖写入计数（派生值）。
     *
     * @param target 点赞目标 {@link ReactionTargetVO}
     * @param count  最新计数 {@code long}
     */
    void upsertCount(ReactionTargetVO target, long count);

    /**
     * 增量更新计数（派生值）。
     *
     * <p>注意：delta 可为负数，底层实现应保证最终不会落成负数。</p>
     */
    void incrCount(ReactionTargetVO target, long delta);

    /**
     * 幂等应用计数增量（delta 模型）。
     *
     * <p>当 MQ 发生重复投递/重复消费时，必须保证同一个事件只影响一次计数。</p>
     *
     * @param target  点赞目标 {@link ReactionTargetVO}
     * @param eventId 上游事件 ID（幂等键）
     * @param delta   增量（可为负数）
     * @return {@code true} 表示本次增量已落库；{@code false} 表示重复/无效被忽略
     */
    boolean applyCountDeltaOnce(ReactionTargetVO target, String eventId, long delta);

    /**
     * 查询用户是否对 target 存在事实（DB 真相）。
     */
    boolean exists(ReactionTargetVO target, Long userId);
}

