package cn.nexus.infrastructure.adapter.social.port;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import cn.nexus.domain.social.model.valobj.RelationCounterRouting;
import cn.nexus.infrastructure.mq.reliable.ReliableMqOutboxService;
import cn.nexus.types.event.relation.RelationCounterProjectEvent;
import java.lang.reflect.Constructor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class RelationEventPortTest {

    private final ReliableMqOutboxService reliableMqOutboxService = Mockito.mock(ReliableMqOutboxService.class);
    private final RelationEventPort port = portWithReliableOutbox(reliableMqOutboxService);

    @Test
    void publishCounterProjection_shouldSaveFollowProjectionToReliableOutbox() {
        boolean result = port.publishCounterProjection(101L, " follow ", 1L, 2L, "ACTIVE");

        ArgumentCaptor<RelationCounterProjectEvent> eventCaptor =
                ArgumentCaptor.forClass(RelationCounterProjectEvent.class);
        assertTrue(result);
        verify(reliableMqOutboxService).save(Mockito.eq("relation-counter:101"),
                Mockito.eq(RelationCounterRouting.EXCHANGE),
                Mockito.eq(RelationCounterRouting.RK_FOLLOW),
                eventCaptor.capture());
        RelationCounterProjectEvent event = eventCaptor.getValue();
        assertEquals("relation-counter:101", event.getEventId());
        assertEquals(101L, event.getRelationEventId());
        assertEquals(" follow ", event.getEventType());
    }

    @Test
    void publishCounterProjection_shouldReturnFalseWhenOutboxSaveFails() {
        Mockito.doThrow(new IllegalStateException("save failed"))
                .when(reliableMqOutboxService)
                .save(Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.any());

        boolean result = port.publishCounterProjection(102L, "BLOCK", 1L, 2L, "BLOCKED");

        assertFalse(result);
    }

    @Test
    void publishCounterProjection_shouldReturnFalseForInvalidInputs() {
        assertFalse(port.publishCounterProjection(null, "FOLLOW", 1L, 2L, "ACTIVE"));
        assertFalse(port.publishCounterProjection(101L, " ", 1L, 2L, "ACTIVE"));
        assertFalse(port.publishCounterProjection(101L, "POST", 1L, 2L, "ACTIVE"));

        verifyNoInteractions(reliableMqOutboxService);
    }

    private static RelationEventPort portWithReliableOutbox(ReliableMqOutboxService reliableMqOutboxService) {
        return assertDoesNotThrow(() -> {
            Constructor<RelationEventPort> constructor =
                    RelationEventPort.class.getConstructor(ReliableMqOutboxService.class);
            return constructor.newInstance(reliableMqOutboxService);
        });
    }
}
