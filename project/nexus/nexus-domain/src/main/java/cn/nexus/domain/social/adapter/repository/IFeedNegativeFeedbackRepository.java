package cn.nexus.domain.social.adapter.repository;

/**
 * Feed 负反馈仓储接口：用于读侧过滤（Redis SET/HASH）。
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
     * 记录负反馈的帖子类型（业务类目/主题维度；例如“xx游戏/情感/技术”等）。
     *
     * <p>注意：这里的“类型”不是 {@code content_post.media_type}（媒体形态：纯文/图文/视频）。</p>
     *
     * @param userId   用户 ID {@link Long}
     * @param postType 帖子类型（业务类目/主题） {@link String}
     */
    void addPostType(Long userId, String postType);

    /**
     * 撤销负反馈的帖子类型。
     *
     * @param userId   用户 ID {@link Long}
     * @param postType 帖子类型（业务类目/主题） {@link String}
     */
    void removePostType(Long userId, String postType);

    /**
     * 查询用户负反馈的帖子类型集合（用于读侧过滤）。
     *
     * @param userId 用户 ID {@link Long}
     * @return 帖子类型集合 {@link java.util.Set} {@link String}
     */
    java.util.Set<String> listPostTypes(Long userId);

    /**
     * 记录“用户对某个 post 点选的类型”（用于撤销时反查）。
     *
     * @param userId   用户 ID {@link Long}
     * @param postId   内容 ID（postId） {@link Long}
     * @param postType 点选的帖子类型（业务类目/主题） {@link String}
     */
    void saveSelectedPostType(Long userId, Long postId, String postType);

    /**
     * 获取“用户对某个 post 点选的类型”（用于撤销时反查）。
     *
     * @param userId 用户 ID {@link Long}
     * @param postId 内容 ID（postId） {@link Long}
     * @return 点选的帖子类型；不存在返回 null
     */
    String getSelectedPostType(Long userId, Long postId);

    /**
     * 移除“用户对某个 post 点选的类型”（用于撤销时清理）。
     *
     * @param userId 用户 ID {@link Long}
     * @param postId 内容 ID（postId） {@link Long}
     */
    void removeSelectedPostType(Long userId, Long postId);
}
