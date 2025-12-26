package cn.nexus.domain.social.adapter.port;

/**
 * 关系计数与缓存端口。
 */
public interface IRelationCachePort {

    long getFollowCount(Long sourceId);

    void incrFollow(Long sourceId);

    void decrFollow(Long sourceId);
}
