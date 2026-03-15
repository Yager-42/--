package cn.nexus.infrastructure.adapter.social.port;

import cn.nexus.domain.social.adapter.port.IInteractionNotifyEventPort;
import cn.nexus.infrastructure.mq.reliable.ReliableMqOutboxService;
import cn.nexus.types.event.interaction.InteractionNotifyEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 通知统一事件发布端口实现：使用 RabbitMQ 直接投递。
 *
 * @author rr
 * @author codex
 * @since 2026-01-21
 */
@Component
@RequiredArgsConstructor
public class InteractionNotifyEventPort implements IInteractionNotifyEventPort {

    private static final String EXCHANGE = "social.interaction";
    private static final String RK_INTERACTION_NOTIFY = "interaction.notify";

    private final ReliableMqOutboxService reliableMqOutboxService;

    /**
     * 发布事件。
     *
     * @param event 事件对象。类型：{@link InteractionNotifyEvent}
     */
    @Override
    public void publish(InteractionNotifyEvent event) {
        reliableMqOutboxService.save(event.getEventId(), EXCHANGE, RK_INTERACTION_NOTIFY, event);
    }
}
