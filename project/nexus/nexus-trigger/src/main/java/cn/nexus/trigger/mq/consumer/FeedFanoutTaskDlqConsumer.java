package cn.nexus.trigger.mq.consumer;

import cn.nexus.infrastructure.mq.reliable.annotation.ReliableMqDlq;
import cn.nexus.trigger.mq.config.FeedFanoutConfig;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class FeedFanoutTaskDlqConsumer {

    @RabbitListener(queues = FeedFanoutConfig.DLQ_FANOUT_TASK)
    @ReliableMqDlq(consumerName = "FeedFanoutTaskConsumer",
            originalQueue = FeedFanoutConfig.TASK_QUEUE,
            originalExchange = FeedFanoutConfig.EXCHANGE,
            originalRoutingKey = FeedFanoutConfig.TASK_ROUTING_KEY,
            fallbackPayloadType = "cn.nexus.types.event.FeedFanoutTask",
            lastError = "'feed fanout task dead-lettered'")
    public void onMessage(Message message) {
    }
}
