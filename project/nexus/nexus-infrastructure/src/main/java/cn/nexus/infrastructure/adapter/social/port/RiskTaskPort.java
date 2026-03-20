package cn.nexus.infrastructure.adapter.social.port;

import cn.nexus.domain.social.adapter.port.IRiskTaskPort;
import cn.nexus.infrastructure.mq.reliable.ReliableMqOutboxService;
import cn.nexus.types.event.risk.ImageScanRequestedEvent;
import cn.nexus.types.event.risk.LlmScanRequestedEvent;
import cn.nexus.types.event.risk.ReviewCaseCreatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 风控异步任务 MQ 实现：使用 RabbitMQ 作为工作队列。
 *
 * @author rr
 * @author codex
 * @since 2026-01-29
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RiskTaskPort implements IRiskTaskPort {

    private static final String EXCHANGE = "social.risk";
    private static final String RK_LLM_SCAN = "risk.llm.scan";
    private static final String RK_IMAGE_SCAN = "risk.image.scan";
    private static final String RK_REVIEW_CASE = "risk.review.case";

    private final ReliableMqOutboxService reliableMqOutboxService;

    /**
     * 执行 dispatchLlmScan 逻辑。
     *
     * @param event 事件对象。类型：{@link LlmScanRequestedEvent}
     */
    @Override
    public void dispatchLlmScan(LlmScanRequestedEvent event) {
        if (event == null) {
            return;
        }
        reliableMqOutboxService.save(event.getEventId(), EXCHANGE, RK_LLM_SCAN, event);
        log.debug("risk llm scan dispatched. decisionId={}, taskId={}", event.getDecisionId(), event.getTaskId());
    }

    /**
     * 执行 dispatchImageScan 逻辑。
     *
     * @param event 事件对象。类型：{@link ImageScanRequestedEvent}
     */
    @Override
    public void dispatchImageScan(ImageScanRequestedEvent event) {
        if (event == null) {
            return;
        }
        reliableMqOutboxService.save(event.getEventId(), EXCHANGE, RK_IMAGE_SCAN, event);
        log.debug("risk image scan dispatched. decisionId={}, taskId={}", event.getDecisionId(), event.getTaskId());
    }

    /**
     * 执行 dispatchReviewCase 逻辑。
     *
     * @param event 事件对象。类型：{@link ReviewCaseCreatedEvent}
     */
    @Override
    public void dispatchReviewCase(ReviewCaseCreatedEvent event) {
        if (event == null) {
            return;
        }
        reliableMqOutboxService.save(event.getEventId(), EXCHANGE, RK_REVIEW_CASE, event);
        log.debug("risk review case dispatched. caseId={}, decisionId={}", event.getCaseId(), event.getDecisionId());
    }
}
