package cn.nexus.trigger.listener.social;

import cn.nexus.domain.social.service.RelationCounterProjectionProcessor;
import cn.nexus.infrastructure.mq.reliable.ReliableMqConsumerRecordService;
import cn.nexus.trigger.mq.config.RelationMqConfig;
import cn.nexus.types.event.relation.RelationCounterProjectEvent;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 关系计数投影消费者：手动 ack，成功后才确认。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RelationCounterProjectConsumer {

    private static final String CONSUMER_NAME = "RelationCounterProjectConsumer";

    private final RelationCounterProjectionProcessor processor;
    private final ReliableMqConsumerRecordService consumerRecordService;

    @RabbitListener(queues = RelationMqConfig.Q_FOLLOW, containerFactory = "relationManualAckListenerContainerFactory")
    public void onFollow(RelationCounterProjectEvent event, Message message, Channel channel) throws Exception {
        consume(event, message, channel);
    }

    @RabbitListener(queues = RelationMqConfig.Q_BLOCK, containerFactory = "relationManualAckListenerContainerFactory")
    public void onBlock(RelationCounterProjectEvent event, Message message, Channel channel) throws Exception {
        consume(event, message, channel);
    }

    private void consume(RelationCounterProjectEvent event, Message message, Channel channel) throws Exception {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        String eventId = eventIdOf(event);
        try {
            if (!consumerRecordService.start(eventId, CONSUMER_NAME, "{}")) {
                channel.basicAck(deliveryTag, false);
                return;
            }
            processor.process(event);
            consumerRecordService.markDone(eventId, CONSUMER_NAME);
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            consumerRecordService.markFail(eventId, CONSUMER_NAME, e.getMessage());
            log.error("relation counter projection consume failed, eventId={}", eventId, e);
            channel.basicNack(deliveryTag, false, false);
        }
    }

    private String eventIdOf(RelationCounterProjectEvent event) {
        if (event == null) {
            return "relation-counter:unknown";
        }
        if (event.getEventId() != null && !event.getEventId().isBlank()) {
            return event.getEventId();
        }
        if (event.getRelationEventId() != null) {
            return "relation-counter:" + event.getRelationEventId();
        }
        return "relation-counter:unknown";
    }
}
