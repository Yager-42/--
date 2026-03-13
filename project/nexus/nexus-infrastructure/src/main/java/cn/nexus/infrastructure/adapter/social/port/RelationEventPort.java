package cn.nexus.infrastructure.adapter.social.port;

import cn.nexus.domain.social.adapter.port.IRelationEventPort;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * 关系事件发布：只负责把已持久化的 outbox 事件发到 MQ。
 */
@Component
@RequiredArgsConstructor
public class RelationEventPort implements IRelationEventPort {

    private static final String EXCHANGE = "social.relation";

    private final RabbitTemplate rabbitTemplate;

    @Override
    public void onFollow(Long eventId, Long sourceId, Long targetId, String status) {
        rabbitTemplate.convertAndSend(EXCHANGE, "relation.follow", new RelationFollowEvent(eventId, sourceId, targetId, status));
    }

    @Override
    public void onBlock(Long eventId, Long sourceId, Long targetId) {
        rabbitTemplate.convertAndSend(EXCHANGE, "relation.block", new RelationBlockEvent(eventId, sourceId, targetId));
    }
}
