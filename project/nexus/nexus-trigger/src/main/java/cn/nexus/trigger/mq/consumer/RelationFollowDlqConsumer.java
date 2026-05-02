package cn.nexus.trigger.mq.consumer;

import cn.nexus.infrastructure.mq.reliable.annotation.ReliableMqDlq;
import cn.nexus.trigger.mq.config.RelationMqConfig;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class RelationFollowDlqConsumer {

    @RabbitListener(queues = RelationMqConfig.DLQ_FOLLOW)
    @ReliableMqDlq(consumerName = "RelationCounterProjectConsumer",
            originalQueue = RelationMqConfig.Q_FOLLOW,
            originalExchange = RelationMqConfig.EXCHANGE,
            originalRoutingKey = RelationMqConfig.RK_FOLLOW,
            fallbackPayloadType = "cn.nexus.types.event.relation.RelationCounterProjectEvent",
            lastError = "'relation follow projection dead-lettered'")
    public void onMessage(Message message) {
    }
}
