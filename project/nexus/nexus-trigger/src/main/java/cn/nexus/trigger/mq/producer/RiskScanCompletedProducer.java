package cn.nexus.trigger.mq.producer;

import cn.nexus.infrastructure.mq.reliable.annotation.ReliableMqPublish;
import cn.nexus.trigger.mq.config.RiskMqConfig;
import cn.nexus.types.event.risk.ScanCompletedEvent;
import org.springframework.stereotype.Component;

@Component
public class RiskScanCompletedProducer {

    @ReliableMqPublish(exchange = RiskMqConfig.EXCHANGE,
            routingKey = RiskMqConfig.RK_SCAN_COMPLETED,
            eventId = "#event.eventId",
            payload = "#event")
    public void publish(ScanCompletedEvent event) {
    }
}
