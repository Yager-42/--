package cn.nexus.trigger.mq.consumer;

import cn.nexus.infrastructure.mq.reliable.annotation.ReliableMqDlq;
import cn.nexus.trigger.mq.config.RelationMqConfig;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class RelationBlockDlqConsumer {

    @RabbitListener(queues = RelationMqConfig.DLQ_BLOCK)
    @ReliableMqDlq(consumerName = "RelationCounterProjectConsumer",
            originalQueue = RelationMqConfig.Q_BLOCK,
            originalExchange = RelationMqConfig.EXCHANGE,
            originalRoutingKey = RelationMqConfig.RK_BLOCK,
            fallbackPayloadType = "cn.nexus.types.event.relation.RelationCounterProjectEvent",
            lastError = "'relation block projection dead-lettered'")
    public void onMessage(Message message) {
    }
}
