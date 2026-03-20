package cn.nexus.trigger.mq.consumer;

import cn.nexus.trigger.mq.config.RiskMqConfig;
import cn.nexus.trigger.mq.support.ReliableMqDlqRecorder;
import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class RiskLlmScanDlqConsumer {

    private final ReliableMqDlqRecorder reliableMqDlqRecorder;

    /**
     * 消费单条消息。
     *
     * @param message 消息体。类型：{@link Message}
     */
    @RabbitListener(queues = RiskMqConfig.DLQ_LLM_SCAN)
    public void onMessage(Message message) {
        reliableMqDlqRecorder.record(
                message,
                "RiskLlmScanConsumer",
                RiskMqConfig.Q_LLM_SCAN,
                RiskMqConfig.EXCHANGE,
                RiskMqConfig.RK_LLM_SCAN,
                "cn.nexus.types.event.risk.LlmScanRequestedEvent",
                null,
                "risk llm scan dead-lettered"
        );
    }
}
