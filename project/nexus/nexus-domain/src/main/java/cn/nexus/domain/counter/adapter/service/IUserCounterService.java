package cn.nexus.domain.counter.adapter.service;

import cn.nexus.domain.counter.model.valobj.UserCounterType;
import cn.nexus.domain.counter.model.valobj.UserRelationCounterVO;

/**
 * User counter service contract aligned with zhiguang-style semantics.
 */
public interface IUserCounterService {

    long getCount(Long userId, UserCounterType counterType);

    long incrementFollowings(Long userId, long delta);

    long incrementFollowers(Long userId, long delta);

    long incrementPosts(Long userId, long delta);

    long incrementLikesReceived(Long userId, long delta);

    void setCount(Long userId, UserCounterType counterType, long count);

    void evict(Long userId, UserCounterType counterType);

    void rebuildAllCounters(Long userId);

    UserRelationCounterVO readRelationCountersWithVerification(Long userId);
}
