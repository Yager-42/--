package cn.nexus.trigger.mq.consumer;

import cn.nexus.infrastructure.mq.reliable.annotation.ReliableMqDlq;
import cn.nexus.trigger.mq.config.FeedRecommendFeedbackAMqConfig;
import cn.nexus.trigger.mq.config.InteractionNotifyMqConfig;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class FeedRecommendFeedbackADlqConsumer {

    @RabbitListener(queues = FeedRecommendFeedbackAMqConfig.DLQ_FEED_RECOMMEND_FEEDBACK_A)
    @ReliableMqDlq(consumerName = "FeedRecommendFeedbackAConsumer",
            originalQueue = FeedRecommendFeedbackAMqConfig.Q_FEED_RECOMMEND_FEEDBACK_A,
            originalExchange = "social.interaction",
            originalRoutingKey = InteractionNotifyMqConfig.RK_INTERACTION_NOTIFY,
            fallbackPayloadType = "cn.nexus.types.event.interaction.InteractionNotifyEvent",
            lastError = "'recommend feedback A dead-lettered'")
    public void onMessage(Message message) {
    }
}
