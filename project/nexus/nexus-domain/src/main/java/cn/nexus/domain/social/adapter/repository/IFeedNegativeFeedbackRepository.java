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

    /**
     * 记录负反馈的内容类型（Phase 2：以 content_post.media_type 作为“内容类型”维度）。
     *
     * @param userId    用户 ID {@link Long}
     * @param mediaType 内容媒体类型 {@link Integer}
     */
    void addContentType(Long userId, Integer mediaType);

    /**
     * 撤销负反馈的内容类型。
     *
     * @param userId    用户 ID {@link Long}
     * @param mediaType 内容媒体类型 {@link Integer}
     */
    void removeContentType(Long userId, Integer mediaType);

    /**
     * 查询用户负反馈的内容类型集合（用于读侧过滤）。
     *
     * @param userId 用户 ID {@link Long}
     * @return 内容媒体类型集合 {@link java.util.Set} {@link Integer}
     */
    java.util.Set<Integer> listContentTypes(Long userId);
}
