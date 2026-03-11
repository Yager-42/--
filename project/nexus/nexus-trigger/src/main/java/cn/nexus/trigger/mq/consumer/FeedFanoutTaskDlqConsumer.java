package cn.nexus.trigger.mq.consumer;

import cn.nexus.trigger.mq.config.FeedFanoutConfig;
import cn.nexus.trigger.mq.support.ReliableMqDlqRecorder;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class FeedFanoutTaskDlqConsumer {

    private final ReliableMqDlqRecorder reliableMqDlqRecorder;

    @RabbitListener(queues = FeedFanoutConfig.DLQ_FANOUT_TASK)
    public void onMessage(Message message) {
        reliableMqDlqRecorder.record(
                message,
                "FeedFanoutTaskConsumer",
                FeedFanoutConfig.TASK_QUEUE,
                FeedFanoutConfig.EXCHANGE,
                FeedFanoutConfig.TASK_ROUTING_KEY,
                "cn.nexus.types.event.FeedFanoutTask",
                null,
                "feed fanout task dead-lettered"
        );
    }
}
