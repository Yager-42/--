package cn.nexus.infrastructure.adapter.social.port;

import cn.nexus.domain.social.adapter.port.IRelationEventPort;
import cn.nexus.domain.social.model.valobj.RelationCounterRouting;
import cn.nexus.types.event.relation.RelationCounterProjectEvent;
import com.rabbitmq.client.ConfirmCallback;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * 关系事件发布端口实现：把已持久化的 Outbox 事件投递到关系交换机。
 *
 * @author rr
 * @author codex
 * @since 2025-12-26
 */
@Component
@RequiredArgsConstructor
public class RelationEventPort implements IRelationEventPort {

    private final RabbitTemplate rabbitTemplate;

    /**
     * 执行 onFollow 逻辑。
     *
     * @param eventId eventId 参数。类型：{@link Long}
     * @param sourceId sourceId 参数。类型：{@link Long}
     * @param targetId 目标 ID。类型：{@link Long}
     * @param status status 参数。类型：{@link String}
     */
    @Override
    public boolean publishCounterProjection(Long eventId,
                                            String eventType,
                                            Long sourceId,
                                            Long targetId,
                                            String status,
                                            String projectionKey,
                                            Long projectionVersion) {
        if (eventId == null || eventType == null || eventType.isBlank()) {
            return false;
        }
        RelationCounterProjectEvent event = new RelationCounterProjectEvent();
        event.setEventId("relation-counter:" + eventId);
        event.setRelationEventId(eventId);
        event.setEventType(eventType);
        event.setSourceId(sourceId);
        event.setTargetId(targetId);
        event.setStatus(status);
        event.setProjectionKey(projectionKey);
        event.setProjectionVersion(projectionVersion);
        String normalizedType = eventType.trim().toUpperCase();
        String routingKey = routingKeyOf(normalizedType);
        if (routingKey == null) {
            return false;
        }
        try {
            rabbitTemplate.invoke(operations -> {
                operations.convertAndSend(RelationCounterRouting.EXCHANGE, routingKey, event);
                return null;
            }, ackCallback(), nackCallback());
            rabbitTemplate.waitForConfirmsOrDie(5000L);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String routingKeyOf(String normalizedType) {
        if ("FOLLOW".equals(normalizedType)) {
            return RelationCounterRouting.RK_FOLLOW;
        }
        if ("BLOCK".equals(normalizedType)) {
            return RelationCounterRouting.RK_BLOCK;
        }
        if ("POST".equals(normalizedType)) {
            return RelationCounterRouting.RK_POST;
        }
        return null;
    }

    private ConfirmCallback ackCallback() {
        return (sequence, multiple) -> {
            // no-op
        };
    }

    private ConfirmCallback nackCallback() {
        return (sequence, multiple) -> {
            throw new IllegalStateException("relation publish confirm nacked");
        };
    }
}
