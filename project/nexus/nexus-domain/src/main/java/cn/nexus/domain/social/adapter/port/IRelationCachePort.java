package cn.nexus.domain.social.adapter.port;

/**
 * 关系计数缓存端口。
 */
public interface IRelationCachePort {

    long getFollowingCount(Long sourceId);

    long getFollowerCount(Long targetId);

    void incrFollowing(Long sourceId, long delta);

    void incrFollower(Long targetId, long delta);

    void evict(Long userId);
}
