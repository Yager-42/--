package cn.nexus.trigger.mq.consumer;

import cn.nexus.trigger.mq.config.FeedFanoutConfig;
import cn.nexus.trigger.mq.config.FeedRecommendItemMqConfig;
import cn.nexus.trigger.mq.support.ReliableMqDlqRecorder;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class FeedRecommendItemDeleteDlqConsumer {

    private final ReliableMqDlqRecorder reliableMqDlqRecorder;

    @RabbitListener(queues = FeedRecommendItemMqConfig.DLQ_FEED_RECOMMEND_ITEM_DELETE)
    public void onMessage(Message message) {
        reliableMqDlqRecorder.record(
                message,
                "FeedRecommendItemDeleteConsumer",
                FeedRecommendItemMqConfig.Q_FEED_RECOMMEND_ITEM_DELETE,
                FeedFanoutConfig.EXCHANGE,
                FeedRecommendItemMqConfig.RK_POST_DELETED,
                "cn.nexus.types.event.PostDeletedEvent",
                null,
                "recommend item delete dead-lettered"
        );
    }
}
