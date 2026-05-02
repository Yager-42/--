package cn.nexus.trigger.mq.consumer;

import cn.nexus.infrastructure.mq.reliable.annotation.ReliableMqDlq;
import cn.nexus.trigger.mq.config.RiskMqConfig;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * RiskImageScanDlqConsumer 消费器。
 *
 * @author rr
 * @author codex
 * @since 2026-03-11
 */
@Component
public class RiskImageScanDlqConsumer {

    /**
     * 消费单条消息。
     *
     * @param message 消息体。类型：{@link Message}
     */
    @RabbitListener(queues = RiskMqConfig.DLQ_IMAGE_SCAN)
    @ReliableMqDlq(consumerName = "RiskImageScanConsumer",
            originalQueue = RiskMqConfig.Q_IMAGE_SCAN,
            originalExchange = RiskMqConfig.EXCHANGE,
            originalRoutingKey = RiskMqConfig.RK_IMAGE_SCAN,
            fallbackPayloadType = "cn.nexus.types.event.risk.ImageScanRequestedEvent",
            lastError = "'risk image scan dead-lettered'")
    public void onMessage(Message message) {
    }
}
