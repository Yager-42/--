package cn.nexus.trigger.mq.consumer;

import cn.nexus.infrastructure.mq.reliable.annotation.ReliableMqDlq;
import cn.nexus.trigger.mq.config.FeedFanoutConfig;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class FeedFanoutDispatcherDlqConsumer {

    @RabbitListener(queues = FeedFanoutConfig.DLQ_POST_PUBLISHED)
    @ReliableMqDlq(consumerName = "FeedFanoutDispatcherConsumer",
            originalQueue = FeedFanoutConfig.QUEUE,
            originalExchange = FeedFanoutConfig.EXCHANGE,
            originalRoutingKey = FeedFanoutConfig.ROUTING_KEY,
            fallbackPayloadType = "cn.nexus.types.event.PostPublishedEvent",
            lastError = "'feed fanout dispatcher dead-lettered'")
    public void onMessage(Message message) {
    }
}
