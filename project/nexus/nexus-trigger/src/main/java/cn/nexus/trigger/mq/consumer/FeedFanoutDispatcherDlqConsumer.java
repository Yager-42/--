package cn.nexus.trigger.mq.consumer;

import cn.nexus.trigger.mq.config.FeedFanoutConfig;
import cn.nexus.trigger.mq.support.ReliableMqDlqRecorder;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class FeedFanoutDispatcherDlqConsumer {

    private final ReliableMqDlqRecorder reliableMqDlqRecorder;

    @RabbitListener(queues = FeedFanoutConfig.DLQ_POST_PUBLISHED)
    public void onMessage(Message message) {
        reliableMqDlqRecorder.record(
                message,
                "FeedFanoutDispatcherConsumer",
                FeedFanoutConfig.QUEUE,
                FeedFanoutConfig.EXCHANGE,
                FeedFanoutConfig.ROUTING_KEY,
                "cn.nexus.types.event.PostPublishedEvent",
                null,
                "feed fanout dispatcher dead-lettered"
        );
    }
}
