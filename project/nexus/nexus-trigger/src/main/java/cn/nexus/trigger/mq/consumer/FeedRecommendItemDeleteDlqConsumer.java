package cn.nexus.trigger.mq.consumer;

import cn.nexus.infrastructure.mq.reliable.annotation.ReliableMqDlq;
import cn.nexus.trigger.mq.config.FeedFanoutConfig;
import cn.nexus.trigger.mq.config.FeedRecommendItemMqConfig;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class FeedRecommendItemDeleteDlqConsumer {

    @RabbitListener(queues = FeedRecommendItemMqConfig.DLQ_FEED_RECOMMEND_ITEM_DELETE)
    @ReliableMqDlq(consumerName = "FeedRecommendItemDeleteConsumer",
            originalQueue = FeedRecommendItemMqConfig.Q_FEED_RECOMMEND_ITEM_DELETE,
            originalExchange = FeedFanoutConfig.EXCHANGE,
            originalRoutingKey = FeedRecommendItemMqConfig.RK_POST_DELETED,
            fallbackPayloadType = "cn.nexus.types.event.PostDeletedEvent",
            lastError = "'recommend item delete dead-lettered'")
    public void onMessage(Message message) {
    }
}
