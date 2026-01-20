package cn.nexus.domain.social.adapter.port;

import cn.nexus.domain.social.model.valobj.ReactionTargetVO;

/**
 * 点赞延迟消息端口（RabbitMQ）。
 *
 * <p>Domain 只依赖接口；具体 MQ 实现在 trigger 层复用现有延迟队列模式。</p>
 *
 * @author codex
 * @since 2026-01-20
 */
public interface IReactionDelayPort {

    /**
     * 投递一次延迟同步消息（首次 pending 使用）。
     *
     * @param target  点赞目标 {@link ReactionTargetVO}
     * @param delayMs 延迟毫秒 {@code long}
     */
    void sendDelay(ReactionTargetVO target, long delayMs);

    /**
     * 投递到死信队列（最小告警/留痕）。
     *
     * @param rawMessage 原始消息 {@link String}
     */
    void sendToDLQ(String rawMessage);
}

