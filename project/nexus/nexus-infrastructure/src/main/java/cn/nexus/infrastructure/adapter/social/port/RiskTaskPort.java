package cn.nexus.infrastructure.adapter.social.port;

import cn.nexus.domain.social.adapter.port.IRiskTaskPort;
import cn.nexus.types.event.risk.ImageScanRequestedEvent;
import cn.nexus.types.event.risk.LlmScanRequestedEvent;
import cn.nexus.types.event.risk.ReviewCaseCreatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * 风控异步任务 MQ 实现：使用 RabbitMQ 作为工作队列。
 *
 * <p>注意：这里不依赖 trigger 模块的 MQ Config（避免分层穿透），仅复用一致的 exchange/routingKey 字符串。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RiskTaskPort implements IRiskTaskPort {

    private static final String EXCHANGE = "social.risk";
    private static final String RK_LLM_SCAN = "risk.llm.scan";
    private static final String RK_IMAGE_SCAN = "risk.image.scan";
    private static final String RK_REVIEW_CASE = "risk.review.case";

    private final RabbitTemplate rabbitTemplate;

    @Override
    public void dispatchLlmScan(LlmScanRequestedEvent event) {
        if (event == null) {
            return;
        }
        rabbitTemplate.convertAndSend(EXCHANGE, RK_LLM_SCAN, event);
        log.debug("risk llm scan dispatched. decisionId={}, taskId={}", event.getDecisionId(), event.getTaskId());
    }

    @Override
    public void dispatchImageScan(ImageScanRequestedEvent event) {
        if (event == null) {
            return;
        }
        rabbitTemplate.convertAndSend(EXCHANGE, RK_IMAGE_SCAN, event);
        log.debug("risk image scan dispatched. decisionId={}, taskId={}", event.getDecisionId(), event.getTaskId());
    }

    @Override
    public void dispatchReviewCase(ReviewCaseCreatedEvent event) {
        if (event == null) {
            return;
        }
        rabbitTemplate.convertAndSend(EXCHANGE, RK_REVIEW_CASE, event);
        log.debug("risk review case dispatched. caseId={}, decisionId={}", event.getCaseId(), event.getDecisionId());
    }
}

