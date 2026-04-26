package cn.nexus.infrastructure.adapter.social.port;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import cn.nexus.domain.social.model.valobj.RelationCounterRouting;
import cn.nexus.types.event.relation.RelationCounterProjectEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class RelationEventPortTest {

    @Test
    void publishCounterProjection_post_shouldUsePostRoutingKeyAndWaitForConfirm() {
        RabbitTemplate rabbitTemplate = Mockito.mock(RabbitTemplate.class);
        RabbitOperations rabbitOperations = Mockito.mock(RabbitOperations.class);
        Mockito.when(rabbitTemplate.invoke(any(), any(), any()))
                .thenAnswer(invocation -> {
                    RabbitOperations.OperationsCallback<?> callback = invocation.getArgument(0);
                    return callback.doInRabbit(rabbitOperations);
                });
        RelationEventPort port = new RelationEventPort(rabbitTemplate);

        boolean published = port.publishCounterProjection(
                900L,
                "POST",
                11L,
                101L,
                "PUBLISHED",
                "post:101",
                3L);

        assertTrue(published);
        ArgumentCaptor<RelationCounterProjectEvent> eventCaptor =
                ArgumentCaptor.forClass(RelationCounterProjectEvent.class);
        verify(rabbitOperations).convertAndSend(
                eq(RelationCounterRouting.EXCHANGE),
                eq(RelationCounterRouting.RK_POST),
                eventCaptor.capture());
        RelationCounterProjectEvent event = eventCaptor.getValue();
        org.junit.jupiter.api.Assertions.assertEquals("relation-counter:900", event.getEventId());
        org.junit.jupiter.api.Assertions.assertEquals("POST", event.getEventType());
        org.junit.jupiter.api.Assertions.assertEquals(11L, event.getSourceId());
        org.junit.jupiter.api.Assertions.assertEquals(101L, event.getTargetId());
        org.junit.jupiter.api.Assertions.assertEquals("PUBLISHED", event.getStatus());
        org.junit.jupiter.api.Assertions.assertEquals("post:101", event.getProjectionKey());
        org.junit.jupiter.api.Assertions.assertEquals(3L, event.getProjectionVersion());
        verify(rabbitTemplate).waitForConfirmsOrDie(5000L);
    }
}
