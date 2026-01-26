package cn.nexus.domain.social.adapter.port;

import java.util.List;

/**
 * 推荐系统端口：把推荐引擎当成外部依赖（Phase 3）。
 *
 * <p>Domain 只负责“候选 -> 过滤 -> 回表 -> 组装”，不直接写 HTTP。</p>
 *
 * @author codex
 * @since 2026-01-26
 */
public interface IRecommendationPort {

    /**
     * 拉取推荐列表（RECOMMEND）。
     *
     * @param userId 用户 ID
     * @param n      期望返回的候选数量
     * @return postId 列表（按推荐顺序）
     */
    List<Long> recommend(Long userId, int n);

    /**
     * 拉取热门列表（POPULAR）。
     *
     * @param userId 用户 ID（用于过滤 read 等，可为空）
     * @param n      期望返回的候选数量
     * @param offset 偏移量（用于分页）
     * @return postId 列表（按热门顺序）
     */
    List<Long> popular(Long userId, int n, int offset);

    /**
     * 拉取相似列表（NEIGHBORS）。
     *
     * @param postId 种子内容 ID
     * @param n      期望返回的候选数量
     * @return postId 列表（按相似度顺序）
     */
    List<Long> neighbors(Long postId, int n);

    /**
     * 写入/更新 Item（内容 -> 推荐引擎）。
     *
     * @param postId       内容 ID
     * @param labels       标签列表（已按业务规则归一化）
     * @param timestampMs  时间戳（毫秒）
     */
    void upsertItem(Long postId, List<String> labels, Long timestampMs);

    /**
     * 写入 Feedback（用户行为 -> 推荐引擎）。
     *
     * @param userId       用户 ID
     * @param postId       内容 ID
     * @param feedbackType 行为类型（如 read/like/unlike/comment）
     * @param timestampMs  时间戳（毫秒）
     */
    void insertFeedback(Long userId, Long postId, String feedbackType, Long timestampMs);

    /**
     * 删除 Item（内容下架/删除时同步推荐池）。
     *
     * @param postId 内容 ID
     */
    void deleteItem(Long postId);
}
