package cn.nexus.domain.social.adapter.port;

import cn.nexus.types.event.interaction.InteractionNotifyEvent;

/**
 * 通知统一事件发布端口：domain 只依赖端口，不直接依赖 MQ。
 *
 * @author codex
 * @since 2026-01-21
 */
public interface IInteractionNotifyEventPort {

    /**
     * 发布通知事件（旁路，不允许影响主链路）。
     */
    void publish(InteractionNotifyEvent event);
}

