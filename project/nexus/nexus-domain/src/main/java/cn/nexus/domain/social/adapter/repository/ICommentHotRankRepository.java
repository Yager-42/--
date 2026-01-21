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
}

