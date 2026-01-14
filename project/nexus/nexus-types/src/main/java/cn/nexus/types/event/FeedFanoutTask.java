package cn.nexus.types.event;

import java.io.Serializable;

/**
 * Feed fanout 切片任务：用于将“单条发布事件的超大 fanout”拆成多个可并行消费的小任务。
 *
 * <p>为什么需要它？</p>
 * <ul>
 *     <li>避免单条 {@link PostPublishedEvent} 在一次消费里完成全量 fanout，导致消费耗时过长。</li>
 *     <li>失败重试只重试某一片（offset+limit），而不是从 0 重新 fanout 一遍。</li>
 *     <li>幂等性：写入 Redis ZSET 时以 postId 作为 member，重复写不会产生重复条目。</li>
 * </ul>
 *
 * @param postId        内容 ID（写入 inbox 的 member）
 * @param authorId      作者用户 ID（用于分页拉粉丝）
 * @param publishTimeMs 发布时间毫秒时间戳（写入 inbox 的 score）
 * @param offset        粉丝分页 offset（从 0 开始）
 * @param limit         粉丝分页 limit（单片大小）
 * @author codex
 * @since 2026-01-14
 */
public record FeedFanoutTask(Long postId,
                             Long authorId,
                             Long publishTimeMs,
                             Integer offset,
                             Integer limit) implements Serializable {
    private static final long serialVersionUID = 1L;
}
