package cn.nexus.domain.social.adapter.port;

/**
 * 关系计数缓存端口：负责关注数、粉丝数这类轻量读模型。
 *
 * @author rr
 * @author codex
 * @since 2025-12-26
 */
public interface IRelationCachePort {

    /**
     * 读取用户关注数。
     *
     * @param sourceId 用户 ID，类型：{@link Long}
     * @return 关注数，类型：{@code long}
     */
    long getFollowingCount(Long sourceId);

    /**
     * 读取用户粉丝数。
     *
     * @param targetId 用户 ID，类型：{@link Long}
     * @return 粉丝数，类型：{@code long}
     */
    long getFollowerCount(Long targetId);

    /**
     * 增量更新关注数缓存。
     *
     * @param sourceId 用户 ID，类型：{@link Long}
     * @param delta 增量值，类型：{@code long}
     */
    void incrFollowing(Long sourceId, long delta);

    /**
     * 增量更新粉丝数缓存。
     *
     * @param targetId 用户 ID，类型：{@link Long}
     * @param delta 增量值，类型：{@code long}
     */
    void incrFollower(Long targetId, long delta);

    /**
     * 删除指定用户的关系计数缓存。
     *
     * @param userId 用户 ID，类型：{@link Long}
     */
    void evict(Long userId);
}
