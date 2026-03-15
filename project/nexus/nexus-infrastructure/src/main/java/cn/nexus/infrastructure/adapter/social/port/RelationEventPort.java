package cn.nexus.infrastructure.adapter.social.port;

import cn.nexus.domain.social.adapter.port.IRelationEventPort;
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

    private static final String EXCHANGE = "social.relation";

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
    public void onFollow(Long eventId, Long sourceId, Long targetId, String status) {
        rabbitTemplate.convertAndSend(EXCHANGE, "relation.follow", new RelationFollowEvent(eventId, sourceId, targetId, status));
    }

    /**
     * 执行 onBlock 逻辑。
     *
     * @param eventId eventId 参数。类型：{@link Long}
     * @param sourceId sourceId 参数。类型：{@link Long}
     * @param targetId 目标 ID。类型：{@link Long}
     */
    @Override
    public void onBlock(Long eventId, Long sourceId, Long targetId) {
        rabbitTemplate.convertAndSend(EXCHANGE, "relation.block", new RelationBlockEvent(eventId, sourceId, targetId));
    }
}
