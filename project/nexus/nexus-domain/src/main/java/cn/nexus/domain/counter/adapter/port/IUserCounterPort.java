package cn.nexus.domain.counter.adapter.port;

import cn.nexus.domain.counter.model.valobj.UserCounterType;

/**
 * 用户聚合计数端口。
 *
 * @author codex
 * @since 2026-04-02
 */
public interface IUserCounterPort {

    long getCount(Long userId, UserCounterType counterType);

    long increment(Long userId, UserCounterType counterType, long delta);

    void setCount(Long userId, UserCounterType counterType, long count);

    void evict(Long userId, UserCounterType counterType);
}
