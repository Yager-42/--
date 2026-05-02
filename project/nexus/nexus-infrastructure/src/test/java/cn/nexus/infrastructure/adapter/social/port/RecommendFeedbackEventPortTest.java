package cn.nexus.infrastructure.adapter.social.port;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;

import cn.nexus.infrastructure.mq.reliable.ReliableMqOutboxService;
import cn.nexus.infrastructure.mq.reliable.aop.ReliableMqExpressionEvaluator;
import cn.nexus.infrastructure.mq.reliable.aop.ReliableMqPublishAspect;
import cn.nexus.types.event.recommend.RecommendFeedbackEvent;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

@SpringJUnitConfig(RecommendFeedbackEventPortTest.TestConfig.class)
class RecommendFeedbackEventPortTest {

    @Autowired
    private RecommendFeedbackEventPort port;

    @Autowired
    private ReliableMqOutboxService reliableMqOutboxService;

    @Test
    void publish_shouldSaveOutboxThroughReliableMqPublishAspect() {
        RecommendFeedbackEvent event = new RecommendFeedbackEvent();
        event.setEventId("recommend-event-1");

        port.publish(event);

        assertTrue(AopUtils.isAopProxy(port));
        verify(reliableMqOutboxService).save("recommend-event-1", "social.recommend", "recommend.feedback", event);
    }

    @Configuration
    @org.springframework.context.annotation.EnableAspectJAutoProxy(proxyTargetClass = true)
    @Import(RecommendFeedbackEventPort.class)
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
