package cn.nexus.infrastructure.adapter.social.port;

import cn.nexus.domain.social.adapter.port.IRelationEventPort;
import cn.nexus.domain.social.adapter.port.IRelationEventInboxPort;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 关系事件发布，仅使用 MQ fanout。
 */
@Component
@RequiredArgsConstructor
public class RelationEventPort implements IRelationEventPort {

    private final RabbitTemplate rabbitTemplate;
    private final IRelationEventInboxPort relationEventInboxPort;
    private static final String EXCHANGE = "social.relation";
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void onFollow(Long sourceId, Long targetId, String status) {
        RelationFollowEvent evt = new RelationFollowEvent(sourceId, targetId, status);
        relationEventInboxPort.save("FOLLOW", fingerprint(sourceId, targetId, status), toPayload(evt));
        rabbitTemplate.convertAndSend(EXCHANGE, "relation.follow", evt);
    }

    @Override
    public void onFriendEstablished(Long sourceId, Long targetId) {
        RelationFriendEvent evt = new RelationFriendEvent(sourceId, targetId);
        relationEventInboxPort.save("FRIEND", fingerprint(sourceId, targetId, null), toPayload(evt));
        rabbitTemplate.convertAndSend(EXCHANGE, "relation.friend", evt);
    }

    @Override
    public void onBlock(Long sourceId, Long targetId) {
        RelationBlockEvent evt = new RelationBlockEvent(sourceId, targetId);
        relationEventInboxPort.save("BLOCK", fingerprint(sourceId, targetId, null), toPayload(evt));
        rabbitTemplate.convertAndSend(EXCHANGE, "relation.block", evt);
    }

    private String fingerprint(Long sourceId, Long targetId, String status) {
        return sourceId + ":" + targetId + ":" + (status == null ? "" : status);
    }

    private String toPayload(Object evt) {
        try {
            return objectMapper.writeValueAsString(evt);
        } catch (Exception e) {
            return evt.toString();
        }
    }
}
