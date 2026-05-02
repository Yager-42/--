package cn.nexus.trigger.mq.consumer;

import cn.nexus.infrastructure.mq.reliable.annotation.ReliableMqDlq;
import cn.nexus.trigger.mq.config.FeedFanoutConfig;
import cn.nexus.trigger.mq.config.PostSummaryMqConfig;
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
public class PostSummaryGenerateDlqConsumer {

    /**
     * 处理一条摘要生成死信消息。
     *
     * @param message 死信消息 {@link Message}
     */
    @RabbitListener(queues = PostSummaryMqConfig.DLQ_POST_SUMMARY_GENERATE)
    @ReliableMqDlq(consumerName = "PostSummaryGenerateConsumer",
            originalQueue = PostSummaryMqConfig.Q_POST_SUMMARY_GENERATE,
            originalExchange = FeedFanoutConfig.EXCHANGE,
            originalRoutingKey = PostSummaryMqConfig.RK_POST_SUMMARY_GENERATE,
            fallbackPayloadType = "cn.nexus.types.event.PostSummaryGenerateEvent",
            lastError = "'post summary dead-lettered'")
    public void onMessage(Message message) {
    }
}
