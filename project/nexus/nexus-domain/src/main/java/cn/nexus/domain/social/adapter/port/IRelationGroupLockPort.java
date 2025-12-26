package cn.nexus.domain.social.adapter.port;

import cn.nexus.domain.social.model.valobj.RelationGroupVO;

/**
 * 分组操作锁与幂等存储端口，确保跨实例幂等与并发安全。
 */
public interface IRelationGroupLockPort {

    /**
     * 尝试获取用户级别的分组操作锁。
     */
    boolean tryLock(Long userId, String action, long ttlSeconds);

    /**
     * 释放分组操作锁。
     */
    void unlock(Long userId, String action);

    /**
     * 按幂等 token 读取已完成的结果。
     */
    RelationGroupVO loadResult(String token);

    /**
     * 保存幂等 token 对应的结果，设置过期时间。
     */
    void saveResult(String token, RelationGroupVO vo, long ttlSeconds);
}
