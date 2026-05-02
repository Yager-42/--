package cn.nexus.trigger.mq.producer;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import cn.nexus.infrastructure.mq.reliable.ReliableMqOutboxService;
import cn.nexus.infrastructure.mq.reliable.aop.ReliableMqExpressionEvaluator;
import cn.nexus.infrastructure.mq.reliable.aop.ReliableMqPublishAspect;
import cn.nexus.trigger.mq.config.RiskMqConfig;
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

@SpringJUnitConfig(RiskProducerTest.TestConfig.class)
class RiskProducerTest {

    @Autowired
    private RiskProducer producer;

    @Autowired
    private ReliableMqOutboxService reliableMqOutboxService;

    @BeforeEach
    void setUp() {
        Mockito.reset(reliableMqOutboxService);
    }

    @Test
    void sendLlmScan_shouldSaveOutboxThroughReliableMqPublishAspect() {
        LlmScanRequestedEvent event = new LlmScanRequestedEvent();
        event.setEventId("llm-event-1");

        producer.sendLlmScan(event);

        assertTrue(AopUtils.isAopProxy(producer));
        verify(reliableMqOutboxService).save("llm-event-1",
                RiskMqConfig.EXCHANGE, RiskMqConfig.RK_LLM_SCAN, event);
    }

    @Test
    void sendImageScan_shouldSaveOutboxThroughReliableMqPublishAspect() {
        ImageScanRequestedEvent event = new ImageScanRequestedEvent();
        event.setEventId("image-event-1");

        producer.sendImageScan(event);

        assertTrue(AopUtils.isAopProxy(producer));
        verify(reliableMqOutboxService).save("image-event-1",
                RiskMqConfig.EXCHANGE, RiskMqConfig.RK_IMAGE_SCAN, event);
    }

    @Test
    void sendReviewCase_shouldSaveOutboxThroughReliableMqPublishAspect() {
        ReviewCaseCreatedEvent event = new ReviewCaseCreatedEvent();
        event.setEventId("review-event-1");

        producer.sendReviewCase(event);

        assertTrue(AopUtils.isAopProxy(producer));
        verify(reliableMqOutboxService).save("review-event-1",
                RiskMqConfig.EXCHANGE, RiskMqConfig.RK_REVIEW_CASE, event);
    }

    @Test
    void sendNullEvents_shouldKeepExistingNoopValidation() {
        producer.sendLlmScan(null);
        producer.sendImageScan(null);
        producer.sendReviewCase(null);

        verifyNoInteractions(reliableMqOutboxService);
    }

    @Configuration
    @org.springframework.context.annotation.EnableAspectJAutoProxy(proxyTargetClass = true)
    @Import(RiskProducer.class)
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
