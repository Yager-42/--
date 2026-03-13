package cn.nexus.domain.social.adapter.repository;

import java.util.List;

/**
 * 评论热榜仓储接口：封装 Redis ZSET。
 *
 * <p>Key：comment:hot:{postId}，member=commentId（一级评论），score=热度分数。</p>
 *
 * @author codex
 * @since 2026-01-14
 */
public interface ICommentHotRankRepository {
    void upsert(Long postId, Long rootCommentId, double score);

    void remove(Long postId, Long rootCommentId);

    List<Long> topIds(Long postId, int limit);

    /**
     * 清空某帖的热榜 Key（用于冷启动/重建）。
     *
     * @param postId 帖子 ID
     */
    void clear(Long postId);

    /**
     * 仅保留 TopK（按 score 从高到低），其余全部删除。
     *
     * @param postId  帖子 ID
     * @param keepTop 保留条数（<=0 则不处理）
     */
    void trimToTop(Long postId, int keepTop);
}
