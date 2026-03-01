package cn.nexus.domain.social.service;

/**
 * Outbox 重建服务：把作者最近一段时间内的“已发布内容索引”重建进 Redis Outbox。
 */
public interface IFeedOutboxRebuildService {

    void forceRebuild(Long authorId);
}

