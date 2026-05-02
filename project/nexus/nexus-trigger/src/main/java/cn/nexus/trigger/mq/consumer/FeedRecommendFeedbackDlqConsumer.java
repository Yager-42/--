package cn.nexus.trigger.mq.consumer;

import cn.nexus.infrastructure.mq.reliable.annotation.ReliableMqDlq;
import cn.nexus.trigger.mq.config.FeedRecommendFeedbackMqConfig;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class FeedRecommendFeedbackDlqConsumer {

    @RabbitListener(queues = FeedRecommendFeedbackMqConfig.DLQ_RECOMMEND_FEEDBACK)
    @ReliableMqDlq(consumerName = "FeedRecommendFeedbackConsumer",
            originalQueue = FeedRecommendFeedbackMqConfig.QUEUE,
            originalExchange = FeedRecommendFeedbackMqConfig.EXCHANGE,
            originalRoutingKey = FeedRecommendFeedbackMqConfig.RK_RECOMMEND_FEEDBACK,
            fallbackPayloadType = "cn.nexus.types.event.recommend.RecommendFeedbackEvent",
            lastError = "'recommend feedback dead-lettered'")
    public void onMessage(Message message) {
    }
}
