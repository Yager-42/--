package cn.nexus.trigger.mq.producer;

import cn.nexus.infrastructure.mq.reliable.ReliableMqOutboxService;
import cn.nexus.infrastructure.mq.reliable.aop.ReliableMqExpressionEvaluator;
import cn.nexus.infrastructure.mq.reliable.aop.ReliableMqPublishAspect;
import cn.nexus.trigger.mq.config.RiskMqConfig;
import cn.nexus.types.event.risk.ScanCompletedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;

@SpringJUnitConfig(RiskScanCompletedProducerTest.TestConfig.class)
class RiskScanCompletedProducerTest {

    @Autowired
    private RiskScanCompletedProducer producer;

    @Autowired
    private ReliableMqOutboxService reliableMqOutboxService;

    @BeforeEach
    void setUp() {
        Mockito.reset(reliableMqOutboxService);
    }

    @Test
    void publish_shouldSaveProvidedEventThroughReliableMqPublishAspect() {
        ScanCompletedEvent event = new ScanCompletedEvent();
        event.setEventId("scan-completed-1");

        producer.publish(event);

        assertTrue(AopUtils.isAopProxy(producer));
        verify(reliableMqOutboxService).save("scan-completed-1",
                RiskMqConfig.EXCHANGE, RiskMqConfig.RK_SCAN_COMPLETED, event);
    }

    @Configuration
    @org.springframework.context.annotation.EnableAspectJAutoProxy(proxyTargetClass = true)
    @Import(RiskScanCompletedProducer.class)
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
