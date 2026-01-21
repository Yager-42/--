package cn.nexus.domain.social.adapter.repository;

/**
 * 评论置顶仓储接口：一帖仅一条置顶。
 *
 * <p>注意：置顶不参与分页，读侧必须返回 pinned 字段，items 不包含置顶。</p>
 *
 * @author codex
 * @since 2026-01-14
 */
public interface ICommentPinRepository {

    /**
     * 查询某帖的置顶评论 ID。
     *
     * @param postId 帖子 ID {@link Long}
     * @return 置顶的一级评论 ID {@link Long}，无则返回 {@code null}
     */
    Long getPinnedCommentId(Long postId);

    /**
     * 置顶（upsert）：把某条一级评论设置为该帖唯一置顶。
     *
     * @param postId    帖子 ID {@link Long}
     * @param commentId 一级评论 ID {@link Long}
     * @param nowMs     当前毫秒时间戳 {@link Long}
     */
    void pin(Long postId, Long commentId, Long nowMs);

    /**
     * 取消置顶：清理该帖的置顶记录。
     *
     * @param postId 帖子 ID {@link Long}
     */
    void clear(Long postId);

    /**
     * 若该帖当前置顶等于 commentId，则清理（用于“删评论”链路避免脏置顶）。
     *
     * @param postId    帖子 ID {@link Long}
     * @param commentId 评论 ID {@link Long}
     */
    void clearIfPinned(Long postId, Long commentId);
}

