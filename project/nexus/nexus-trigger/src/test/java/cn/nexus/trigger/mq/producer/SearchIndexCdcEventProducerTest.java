package cn.nexus.trigger.mq.producer;

import cn.nexus.infrastructure.mq.reliable.ReliableMqOutboxService;
import cn.nexus.infrastructure.mq.reliable.aop.ReliableMqExpressionEvaluator;
import cn.nexus.infrastructure.mq.reliable.aop.ReliableMqPublishAspect;
import cn.nexus.trigger.mq.config.SearchIndexCdcMqConfig;
import cn.nexus.types.event.search.PostChangedCdcEvent;
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

@SpringJUnitConfig(SearchIndexCdcEventProducerTest.TestConfig.class)
class SearchIndexCdcEventProducerTest {

    @Autowired
    private SearchIndexCdcEventProducer producer;

    @Autowired
    private ReliableMqOutboxService reliableMqOutboxService;

    @BeforeEach
    void setUp() {
        Mockito.reset(reliableMqOutboxService);
    }

    @Test
    void publish_shouldSaveProvidedEventThroughReliableMqPublishAspect() {
        PostChangedCdcEvent event = new PostChangedCdcEvent();
        event.setEventId("cdc-event-1");

        producer.publish(event);

        assertTrue(AopUtils.isAopProxy(producer));
        verify(reliableMqOutboxService).save("cdc-event-1",
                SearchIndexCdcMqConfig.EXCHANGE, SearchIndexCdcMqConfig.ROUTING_KEY, event);
    }

    @Configuration
    @org.springframework.context.annotation.EnableAspectJAutoProxy(proxyTargetClass = true)
    @Import(SearchIndexCdcEventProducer.class)
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
