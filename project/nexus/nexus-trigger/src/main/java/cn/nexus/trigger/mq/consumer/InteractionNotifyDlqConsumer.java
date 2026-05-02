package cn.nexus.trigger.mq.consumer;

import cn.nexus.infrastructure.mq.reliable.annotation.ReliableMqDlq;
import cn.nexus.trigger.mq.config.InteractionNotifyMqConfig;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class InteractionNotifyDlqConsumer {

    @RabbitListener(queues = InteractionNotifyMqConfig.DLQ_INTERACTION_NOTIFY)
    @ReliableMqDlq(consumerName = "InteractionNotifyConsumer",
            originalQueue = InteractionNotifyMqConfig.Q_INTERACTION_NOTIFY,
            originalExchange = "social.interaction",
            originalRoutingKey = InteractionNotifyMqConfig.RK_INTERACTION_NOTIFY,
            fallbackPayloadType = "cn.nexus.types.event.interaction.InteractionNotifyEvent",
            lastError = "'interaction notify dead-lettered'")
    public void onMessage(Message message) {
    }
}
