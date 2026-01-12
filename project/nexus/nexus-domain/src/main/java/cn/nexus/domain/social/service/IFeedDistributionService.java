package cn.nexus.domain.social.service;

import cn.nexus.types.event.PostPublishedEvent;

/**
 * Feed 分发服务：处理内容发布后的写扩散（fanout）。
 *
 * <p>注意：该服务属于领域层编排，不应直接依赖 MQ/Redis/DAO 客户端实现。</p>
 *
 * @author codex
 * @since 2026-01-12
 */
public interface IFeedDistributionService {

    /**
     * 执行 fanout：将 postId 写入发布者与其粉丝的 InboxTimeline。
     *
     * @param event 内容发布事件
     */
    void fanout(PostPublishedEvent event);
}

