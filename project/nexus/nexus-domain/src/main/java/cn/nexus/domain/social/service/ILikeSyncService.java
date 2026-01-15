package cn.nexus.domain.social.service;

/**
 * 点赞延迟落库服务：消费延迟队列消息触发 flush。
 */
public interface ILikeSyncService {

    /**
     * 执行一次 flush，并返回是否需要重排队。
     *
     * @param targetType 目标类型：POST/COMMENT
     * @param targetId   目标 ID
     * @return 是否需要重排队
     */
    boolean flush(String targetType, Long targetId);
}

