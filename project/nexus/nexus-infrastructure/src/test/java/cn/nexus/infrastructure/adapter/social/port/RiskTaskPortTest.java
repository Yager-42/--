package cn.nexus.infrastructure.adapter.social.port;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import cn.nexus.infrastructure.mq.reliable.ReliableMqOutboxService;
import cn.nexus.infrastructure.mq.reliable.aop.ReliableMqExpressionEvaluator;
import cn.nexus.infrastructure.mq.reliable.aop.ReliableMqPublishAspect;
import cn.nexus.types.event.risk.ImageScanRequestedEvent;
import cn.nexus.types.event.risk.LlmScanRequestedEvent;
import cn.nexus.types.event.risk.ReviewCaseCreatedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

@SpringJUnitConfig(RiskTaskPortTest.TestConfig.class)
class RiskTaskPortTest {

    @Autowired
    private RiskTaskPort port;

    @Autowired
    private ReliableMqOutboxService reliableMqOutboxService;

    @BeforeEach
    void setUp() {
        Mockito.reset(reliableMqOutboxService);
    }

    @Test
    void dispatchLlmScan_shouldSaveOutboxThroughReliableMqPublishAspect() {
        LlmScanRequestedEvent event = new LlmScanRequestedEvent();
        event.setEventId("llm-event-1");

        port.dispatchLlmScan(event);

        assertTrue(AopUtils.isAopProxy(port));
        verify(reliableMqOutboxService).save("llm-event-1", "social.risk", "risk.llm.scan", event);
    }

    @Test
    void dispatchImageScan_shouldSaveOutboxThroughReliableMqPublishAspect() {
        ImageScanRequestedEvent event = new ImageScanRequestedEvent();
        event.setEventId("image-event-1");

        port.dispatchImageScan(event);

        assertTrue(AopUtils.isAopProxy(port));
        verify(reliableMqOutboxService).save("image-event-1", "social.risk", "risk.image.scan", event);
    }

    @Test
    void dispatchReviewCase_shouldSaveOutboxThroughReliableMqPublishAspect() {
        ReviewCaseCreatedEvent event = new ReviewCaseCreatedEvent();
        event.setEventId("review-event-1");

        port.dispatchReviewCase(event);

        assertTrue(AopUtils.isAopProxy(port));
        verify(reliableMqOutboxService).save("review-event-1", "social.risk", "risk.review.case", event);
    }

    @Test
    void dispatchNullEvents_shouldKeepExistingNoopValidation() {
        port.dispatchLlmScan(null);
        port.dispatchImageScan(null);
        port.dispatchReviewCase(null);

        verifyNoInteractions(reliableMqOutboxService);
    }

    @Configuration
    @org.springframework.context.annotation.EnableAspectJAutoProxy(proxyTargetClass = true)
    @Import(RiskTaskPort.class)
    static class TestConfig {

        @Bean
        ReliableMqPublishAspect reliableMqPublishAspect(ReliableMqOutboxService outboxService,
                                                        ReliableMqExpressionEvaluator evaluator) {
            return new ReliableMqPublishAspect(outboxService, evaluator);
        }

        @Bean
        ReliableMqExpressionEvaluator reliableMqExpressionEvaluator() {
            return new ReliableMqExpressionEvaluator();
        }

        @Bean
        ReliableMqOutboxService reliableMqOutboxService() {
            return org.mockito.Mockito.mock(ReliableMqOutboxService.class);
        }
    }
}
