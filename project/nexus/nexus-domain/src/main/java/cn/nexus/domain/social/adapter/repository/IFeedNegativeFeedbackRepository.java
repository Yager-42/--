package cn.nexus.domain.social.adapter.repository;

/**
 * Feed 负反馈仓储接口：用于读侧过滤（Redis SET）。
 *
 * @author codex
 * @since 2026-01-12
 */
public interface IFeedNegativeFeedbackRepository {

    /**
     * 记录负反馈（Phase 1：仅存 targetId，不持久化 type/reasonCode/extraTags）。
     *
     * @param userId     用户 ID
     * @param targetId   负反馈目标 ID（通常为 postId）
     * @param type       负反馈类型（占位）
     * @param reasonCode 原因码（占位）
     */
    void add(Long userId, Long targetId, String type, String reasonCode);

    /**
     * 撤销负反馈。
     *
     * @param userId   用户 ID
     * @param targetId 目标 ID
     */
    void remove(Long userId, Long targetId);

    /**
     * 判断是否包含负反馈（用于读侧过滤）。
     *
     * @param userId   用户 ID
     * @param targetId 目标 ID
     * @return true=存在负反馈，false=不存在
     */
    boolean contains(Long userId, Long targetId);
}

