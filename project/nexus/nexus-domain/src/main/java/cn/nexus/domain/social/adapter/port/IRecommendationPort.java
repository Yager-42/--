package cn.nexus.domain.social.adapter.port;

import java.util.List;

/**
 * 推荐系统端口。
 *
 * @author rr
 * @author codex
 * @since 2026-01-26
 */
public interface IRecommendationPort {

    List<Long> recommend(Long userId, int n);

    List<Long> nonPersonalized(String name, Long userId, int n, int offset);

    List<Long> sessionRecommend(String sessionId, List<Long> currentItemIds, int n);

    List<Long> itemToItem(String name, Long itemId, int n);

    void upsertItem(Long postId, List<String> labels, Long timestampMs);

    void insertFeedback(Long userId, Long postId, String feedbackType, Long timestampMs);

    void deleteItem(Long postId);
}
