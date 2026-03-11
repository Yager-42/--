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
public class FeedRecommendItemUpsertDlqConsumer {

    private final ReliableMqDlqRecorder reliableMqDlqRecorder;

    @RabbitListener(queues = FeedRecommendItemMqConfig.DLQ_FEED_RECOMMEND_ITEM_UPSERT)
    public void onMessage(Message message) {
        reliableMqDlqRecorder.record(
                message,
                "FeedRecommendItemUpsertConsumer",
                FeedRecommendItemMqConfig.Q_FEED_RECOMMEND_ITEM_UPSERT,
                FeedFanoutConfig.EXCHANGE,
                FeedFanoutConfig.ROUTING_KEY,
                "cn.nexus.types.event.PostPublishedEvent",
                null,
                "recommend item upsert dead-lettered"
        );
    }
}
