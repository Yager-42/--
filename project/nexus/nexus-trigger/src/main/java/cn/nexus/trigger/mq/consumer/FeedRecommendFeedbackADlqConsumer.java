package cn.nexus.trigger.mq.consumer;

import cn.nexus.trigger.mq.config.FeedRecommendFeedbackAMqConfig;
import cn.nexus.trigger.mq.config.InteractionNotifyMqConfig;
import cn.nexus.trigger.mq.support.ReliableMqDlqRecorder;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class FeedRecommendFeedbackADlqConsumer {

    private final ReliableMqDlqRecorder reliableMqDlqRecorder;

    @RabbitListener(queues = FeedRecommendFeedbackAMqConfig.DLQ_FEED_RECOMMEND_FEEDBACK_A)
    public void onMessage(Message message) {
        reliableMqDlqRecorder.record(
                message,
                "FeedRecommendFeedbackAConsumer",
                FeedRecommendFeedbackAMqConfig.Q_FEED_RECOMMEND_FEEDBACK_A,
                "social.interaction",
                InteractionNotifyMqConfig.RK_INTERACTION_NOTIFY,
                "cn.nexus.types.event.interaction.InteractionNotifyEvent",
                null,
                "recommend feedback A dead-lettered"
        );
    }
}
