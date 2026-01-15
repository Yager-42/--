package cn.nexus.domain.social.adapter.repository;

import cn.nexus.domain.social.model.valobj.ReactionBatchStateVO;
import cn.nexus.domain.social.model.valobj.ReactionStateVO;
import cn.nexus.domain.social.model.valobj.ReactionTargetVO;
import cn.nexus.domain.social.model.valobj.ReactionToggleResultVO;

/**
 * 点赞/态势仓储：承载 Redis 原子写与读侧回源策略的抽象。
 *
 * <p>注意：这里的“reaction”当前只落地 LIKE（点赞）这一种态势，其它类型后续再扩展。</p>
 */
public interface IReactionRepository {

    /**
     * 点赞/取消点赞（写链路）：在 Redis 内用 Lua 原子完成幂等判断、计数更新、touch 记录与窗口调度标记。
     *
     * @param userId        当前用户 ID（从请求上下文注入）
     * @param targetType    目标类型：POST/COMMENT
     * @param targetId      目标 ID
     * @param action        ADD/REMOVE
     * @param syncTtlSeconds like:win / like:touch 的 TTL（秒），必须 >= delaySeconds * 2
     * @return toggle 结果：delta/currentCount/needSchedule
     */
    ReactionToggleResultVO toggle(Long userId, String targetType, Long targetId, String action, long syncTtlSeconds);

    /**
     * 获取点赞状态（单条）：likeCount + likedByMe。
     */
    ReactionStateVO getState(Long userId, String targetType, Long targetId);

    /**
     * 获取点赞状态（批量）：一次返回 N 个 targets 的 likeCount + likedByMe。
     */
    ReactionBatchStateVO getBatchState(Long userId, java.util.List<ReactionTargetVO> targets);

    /**
     * 延迟 flush：将 Redis 的聚合态同步到 MySQL，并原子 finalize 窗口状态机。
     *
     * @param targetType        目标类型：POST/COMMENT
     * @param targetId          目标 ID
     * @param syncTtlSeconds    like:win / like:touch 的 TTL（秒）
     * @param flushLockSeconds  flush 分布式锁 TTL（秒）
     * @return 是否需要重排队（winKey=1 -> 重置为 0 并返回 true；winKey=0 -> 删除并返回 false）
     */
    boolean flush(String targetType, Long targetId, long syncTtlSeconds, long flushLockSeconds);
}
