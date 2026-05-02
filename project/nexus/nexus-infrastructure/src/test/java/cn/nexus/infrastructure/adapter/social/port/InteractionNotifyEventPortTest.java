package cn.nexus.infrastructure.adapter.social.port;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;

import cn.nexus.infrastructure.mq.reliable.ReliableMqOutboxService;
import cn.nexus.infrastructure.mq.reliable.aop.ReliableMqExpressionEvaluator;
import cn.nexus.infrastructure.mq.reliable.aop.ReliableMqPublishAspect;
import cn.nexus.types.event.interaction.InteractionNotifyEvent;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

@SpringJUnitConfig(InteractionNotifyEventPortTest.TestConfig.class)
class InteractionNotifyEventPortTest {

    @Autowired
    private InteractionNotifyEventPort port;

    @Autowired
    private ReliableMqOutboxService reliableMqOutboxService;

    @Test
    void publish_shouldSaveOutboxThroughReliableMqPublishAspect() {
        InteractionNotifyEvent event = new InteractionNotifyEvent();
        event.setEventId("notify-event-1");

        port.publish(event);

        assertTrue(AopUtils.isAopProxy(port));
        verify(reliableMqOutboxService).save("notify-event-1", "social.interaction", "interaction.notify", event);
    }

    @Configuration
    @org.springframework.context.annotation.EnableAspectJAutoProxy(proxyTargetClass = true)
    @Import(InteractionNotifyEventPort.class)
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
