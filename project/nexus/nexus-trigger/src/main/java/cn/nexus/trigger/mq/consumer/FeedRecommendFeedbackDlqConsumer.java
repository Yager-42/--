package cn.nexus.trigger.mq.consumer;

import cn.nexus.trigger.mq.config.FeedRecommendFeedbackMqConfig;
import cn.nexus.trigger.mq.support.ReliableMqDlqRecorder;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class FeedRecommendFeedbackDlqConsumer {

    private final ReliableMqDlqRecorder reliableMqDlqRecorder;

    @RabbitListener(queues = FeedRecommendFeedbackMqConfig.DLQ_RECOMMEND_FEEDBACK)
    public void onMessage(Message message) {
        reliableMqDlqRecorder.record(
                message,
                "FeedRecommendFeedbackConsumer",
                FeedRecommendFeedbackMqConfig.QUEUE,
                FeedRecommendFeedbackMqConfig.EXCHANGE,
                FeedRecommendFeedbackMqConfig.RK_RECOMMEND_FEEDBACK,
                "cn.nexus.types.event.recommend.RecommendFeedbackEvent",
                null,
                "recommend feedback dead-lettered"
        );
    }
}
