package cn.nexus.trigger.mq.consumer;

import cn.nexus.infrastructure.mq.reliable.annotation.ReliableMqDlq;
import cn.nexus.trigger.mq.config.RiskMqConfig;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * RiskLlmScanDlqConsumer 消费器。
 *
 * @author rr
 * @author codex
 * @since 2026-03-11
 */
@Component
public class RiskLlmScanDlqConsumer {

    /**
     * 消费单条消息。
     *
     * @param message 消息体。类型：{@link Message}
     */
    @RabbitListener(queues = RiskMqConfig.DLQ_LLM_SCAN)
    @ReliableMqDlq(consumerName = "RiskLlmScanConsumer",
            originalQueue = RiskMqConfig.Q_LLM_SCAN,
            originalExchange = RiskMqConfig.EXCHANGE,
            originalRoutingKey = RiskMqConfig.RK_LLM_SCAN,
            fallbackPayloadType = "cn.nexus.types.event.risk.LlmScanRequestedEvent",
            lastError = "'risk llm scan dead-lettered'")
    public void onMessage(Message message) {
    }
}
