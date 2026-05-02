package cn.nexus.trigger.mq.producer;

import cn.nexus.infrastructure.mq.reliable.annotation.ReliableMqPublish;
import cn.nexus.trigger.mq.config.RiskMqConfig;
import cn.nexus.types.event.risk.ImageScanRequestedEvent;
import cn.nexus.types.event.risk.LlmScanRequestedEvent;
import cn.nexus.types.event.risk.ReviewCaseCreatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 风控异步任务生产者：投递扫描/工单等工作队列消息。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RiskProducer {

    private final ObjectProvider<RiskProducer> selfProvider;

    public void sendLlmScan(LlmScanRequestedEvent event) {
        if (event == null) {
            return;
        }
        selfProvider.getObject().publishLlmScan(event);
        log.debug("Risk LLM scan dispatched. decisionId={}, taskId={}", event.getDecisionId(), event.getTaskId());
    }

    public void sendImageScan(ImageScanRequestedEvent event) {
        if (event == null) {
            return;
        }
        selfProvider.getObject().publishImageScan(event);
        log.debug("Risk image scan dispatched. decisionId={}, taskId={}", event.getDecisionId(), event.getTaskId());
    }

    public void sendReviewCase(ReviewCaseCreatedEvent event) {
        if (event == null) {
            return;
        }
        selfProvider.getObject().publishReviewCase(event);
        log.debug("Risk review case dispatched. caseId={}, decisionId={}", event.getCaseId(), event.getDecisionId());
    }

    @ReliableMqPublish(exchange = RiskMqConfig.EXCHANGE,
            routingKey = RiskMqConfig.RK_LLM_SCAN,
            eventId = "#event.eventId",
            payload = "#event")
    public void publishLlmScan(LlmScanRequestedEvent event) {
    }

    @ReliableMqPublish(exchange = RiskMqConfig.EXCHANGE,
            routingKey = RiskMqConfig.RK_IMAGE_SCAN,
            eventId = "#event.eventId",
            payload = "#event")
    public void publishImageScan(ImageScanRequestedEvent event) {
    }

    @ReliableMqPublish(exchange = RiskMqConfig.EXCHANGE,
            routingKey = RiskMqConfig.RK_REVIEW_CASE,
            eventId = "#event.eventId",
            payload = "#event")
    public void publishReviewCase(ReviewCaseCreatedEvent event) {
    }
}
