package cn.nexus.domain.social.adapter.port;

import java.util.List;

/**
 * 关注/粉丝邻接缓存端口。
 */
public interface IRelationAdjacencyCachePort {

    void addFollow(Long sourceId, Long targetId);

    void removeFollow(Long sourceId, Long targetId);

    List<Long> listFollowing(Long sourceId, int limit);

    List<Long> listFollowers(Long targetId, int limit);

    /**
     * 重建某个用户的关注集合缓存（source -> targets），用于回源修复。
     */
    void rebuildFollowing(Long sourceId);

    /**
     * 重建某个用户的粉丝集合缓存（target -> sources），支持热门用户分桶。
     */
    void rebuildFollowers(Long targetId);

    /**
     * 删除指定用户相关的邻接缓存，便于重新拉取。
     */
    void evict(Long userId);
}
