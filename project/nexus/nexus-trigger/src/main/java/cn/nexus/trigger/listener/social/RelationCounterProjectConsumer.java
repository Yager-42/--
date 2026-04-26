package cn.nexus.trigger.listener.social;

import cn.nexus.domain.social.service.RelationCounterProjectionProcessor;
import cn.nexus.infrastructure.mq.reliable.ReliableMqConsumerRecordService;
import cn.nexus.infrastructure.mq.reliable.ReliableMqConsumerRecordService.StartResult;
import cn.nexus.trigger.mq.config.RelationMqConfig;
import cn.nexus.types.event.relation.RelationCounterProjectEvent;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
    private final TransactionTemplate transactionTemplate;
    private final PlatformTransactionManager transactionManager;

    @RabbitListener(queues = RelationMqConfig.Q_FOLLOW, containerFactory = "relationManualAckListenerContainerFactory")
    public void onFollow(RelationCounterProjectEvent event, Message message, Channel channel) throws Exception {
        consume(event, message, channel);
    }

    @RabbitListener(queues = RelationMqConfig.Q_BLOCK, containerFactory = "relationManualAckListenerContainerFactory")
    public void onBlock(RelationCounterProjectEvent event, Message message, Channel channel) throws Exception {
        consume(event, message, channel);
    }

    @RabbitListener(queues = RelationMqConfig.Q_POST, containerFactory = "relationManualAckListenerContainerFactory")
    public void onPost(RelationCounterProjectEvent event, Message message, Channel channel) throws Exception {
        consume(event, message, channel);
    }

    private void consume(RelationCounterProjectEvent event, Message message, Channel channel) throws Exception {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        String eventId = eventIdOf(event);
        try {
            Boolean processed = transactionTemplate.execute(status -> {
                StartResult startResult = consumerRecordService.startManual(eventId, CONSUMER_NAME, "{}");
                if (startResult == StartResult.DUPLICATE_DONE) {
                    return false;
                }
                if (startResult != StartResult.STARTED) {
                    throw new InProgressRedeliveryException();
                }
                processor.process(event);
                consumerRecordService.markDone(eventId, CONSUMER_NAME);
                return true;
            });
            if (!Boolean.TRUE.equals(processed)) {
                channel.basicAck(deliveryTag, false);
                return;
            }
            channel.basicAck(deliveryTag, false);
        } catch (InProgressRedeliveryException e) {
            channel.basicNack(deliveryTag, false, true);
        } catch (Exception e) {
            TransactionTemplate repairTemplate = new TransactionTemplate(transactionManager);
            repairTemplate.executeWithoutResult(status -> processor.registerFailureRepair(event, e.getMessage()));
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

    private static class InProgressRedeliveryException extends RuntimeException {
    }
}
