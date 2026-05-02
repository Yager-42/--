package cn.nexus.trigger.mq.consumer;

import cn.nexus.infrastructure.mq.reliable.annotation.ReliableMqDlq;
import cn.nexus.trigger.mq.config.FeedFanoutConfig;
import cn.nexus.trigger.mq.config.FeedRecommendItemMqConfig;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class FeedRecommendItemUpsertDlqConsumer {

    @RabbitListener(queues = FeedRecommendItemMqConfig.DLQ_FEED_RECOMMEND_ITEM_UPSERT)
    @ReliableMqDlq(consumerName = "FeedRecommendItemUpsertConsumer",
            originalQueue = FeedRecommendItemMqConfig.Q_FEED_RECOMMEND_ITEM_UPSERT,
            originalExchange = FeedFanoutConfig.EXCHANGE,
            originalRoutingKey = FeedFanoutConfig.ROUTING_KEY,
            fallbackPayloadType = "cn.nexus.types.event.PostPublishedEvent",
            lastError = "'recommend item upsert dead-lettered'")
    public void onMessage(Message message) {
    }
}
