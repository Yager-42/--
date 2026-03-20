package cn.nexus.trigger.mq.consumer;

import cn.nexus.trigger.mq.config.FeedFanoutConfig;
import cn.nexus.trigger.mq.config.PostSummaryMqConfig;
import cn.nexus.trigger.mq.support.ReliableMqDlqRecorder;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 摘要生成死信消费者：记录 DLQ 信息，便于人工排查与回放。
 *
 * @author {$authorName}
 * @since 2026-03-11
 */
@Component
@RequiredArgsConstructor
public class PostSummaryGenerateDlqConsumer {

    private final ReliableMqDlqRecorder reliableMqDlqRecorder;

    /**
     * 处理一条摘要生成死信消息。
     *
     * @param message 死信消息 {@link Message}
     */
    @RabbitListener(queues = PostSummaryMqConfig.DLQ_POST_SUMMARY_GENERATE)
    public void onMessage(Message message) {
        reliableMqDlqRecorder.record(
                message,
                "PostSummaryGenerateConsumer",
                PostSummaryMqConfig.Q_POST_SUMMARY_GENERATE,
                FeedFanoutConfig.EXCHANGE,
                PostSummaryMqConfig.RK_POST_SUMMARY_GENERATE,
                "cn.nexus.types.event.PostSummaryGenerateEvent",
                null,
                "post summary dead-lettered"
        );
    }
}
