package cn.nexus.domain.social.adapter.repository;

/**
 * FOLLOW 时间线“已读”记录仓储（Redis SET）。
 *
 * <p>Key：{@code feed:follow:seen:{userId}}</p>
 */
public interface IFeedFollowSeenRepository {

    /**
     * 标记已读（SADD）。
     *
     * @return true=本次成功新增；false=已存在或参数非法
     */
    boolean markSeen(Long userId, Long postId);

    /**
     * 判断是否已读（SISMEMBER）。
     */
    boolean isSeen(Long userId, Long postId);

    /**
     * 设置过期时间（TTL）。
     */
    void expire(Long userId, int ttlDays);
}

