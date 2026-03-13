package cn.nexus.trigger.mq.consumer;

import cn.nexus.trigger.mq.config.RiskMqConfig;
import cn.nexus.trigger.mq.support.ReliableMqDlqRecorder;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RiskImageScanDlqConsumer {

    private final ReliableMqDlqRecorder reliableMqDlqRecorder;

    @RabbitListener(queues = RiskMqConfig.DLQ_IMAGE_SCAN)
    public void onMessage(Message message) {
        reliableMqDlqRecorder.record(
                message,
                "RiskImageScanConsumer",
                RiskMqConfig.Q_IMAGE_SCAN,
                RiskMqConfig.EXCHANGE,
                RiskMqConfig.RK_IMAGE_SCAN,
                "cn.nexus.types.event.risk.ImageScanRequestedEvent",
                null,
                "risk image scan dead-lettered"
        );
    }
}
