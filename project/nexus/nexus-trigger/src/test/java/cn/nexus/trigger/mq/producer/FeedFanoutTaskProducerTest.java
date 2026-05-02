package cn.nexus.trigger.mq.producer;

import cn.nexus.infrastructure.mq.reliable.ReliableMqOutboxService;
import cn.nexus.infrastructure.mq.reliable.aop.ReliableMqExpressionEvaluator;
import cn.nexus.infrastructure.mq.reliable.aop.ReliableMqPublishAspect;
import cn.nexus.trigger.mq.config.FeedFanoutConfig;
import cn.nexus.types.event.FeedFanoutTask;
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

@SpringJUnitConfig(FeedFanoutTaskProducerTest.TestConfig.class)
class FeedFanoutTaskProducerTest {

    @Autowired
    private FeedFanoutTaskProducer producer;

    @Autowired
    private ReliableMqOutboxService reliableMqOutboxService;

    @BeforeEach
    void setUp() {
        Mockito.reset(reliableMqOutboxService);
    }

    @Test
    void publish_shouldSaveProvidedTaskThroughReliableMqPublishAspect() {
        FeedFanoutTask task = new FeedFanoutTask("fanout-child-1", 22L, 11L, 33L, 0, 200);

        producer.publish(task);

        assertTrue(AopUtils.isAopProxy(producer));
        verify(reliableMqOutboxService).save("fanout-child-1",
                FeedFanoutConfig.EXCHANGE, FeedFanoutConfig.TASK_ROUTING_KEY, task);
    }

    @Configuration
    @org.springframework.context.annotation.EnableAspectJAutoProxy(proxyTargetClass = true)
    @Import(FeedFanoutTaskProducer.class)
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
