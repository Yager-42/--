package cn.nexus.infrastructure.adapter.social.port;

import cn.nexus.domain.social.adapter.port.IRelationEventPort;
import cn.nexus.domain.social.model.valobj.RelationCounterRouting;
import cn.nexus.infrastructure.mq.reliable.ReliableMqOutboxService;
import cn.nexus.types.event.relation.RelationCounterProjectEvent;
import lombok.RequiredArgsConstructor;
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

    private final ReliableMqOutboxService reliableMqOutboxService;

    /**
     * 执行 onFollow 逻辑。
     *
     * @param eventId eventId 参数。类型：{@link Long}
     * @param sourceId sourceId 参数。类型：{@link Long}
     * @param targetId 目标 ID。类型：{@link Long}
     * @param status status 参数。类型：{@link String}
     */
    @Override
    public boolean publishCounterProjection(Long eventId, String eventType, Long sourceId, Long targetId, String status) {
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
        String normalizedType = eventType.trim().toUpperCase();
        String routingKey = routingKeyOf(normalizedType);
        if (routingKey == null) {
            return false;
        }
        try {
            reliableMqOutboxService.save(event.getEventId(), RelationCounterRouting.EXCHANGE, routingKey, event);
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
        return null;
    }
}
