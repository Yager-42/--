package cn.nexus.trigger.job.social;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.domain.social.adapter.port.IRelationEventPort;
import cn.nexus.domain.social.adapter.repository.IRelationEventOutboxRepository;
import cn.nexus.domain.social.model.valobj.RelationEventOutboxVO;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class RelationEventOutboxPublishJobTest {

    @Test
    void publishPending_shouldPublishPostCounterProjectionAndMarkSentAfterConfirm() {
        IRelationEventOutboxRepository outboxRepository = Mockito.mock(IRelationEventOutboxRepository.class);
        IRelationEventPort relationEventPort = Mockito.mock(IRelationEventPort.class);
        RelationEventOutboxPublishJob job = new RelationEventOutboxPublishJob(outboxRepository, relationEventPort);
        RelationEventOutboxVO outbox = RelationEventOutboxVO.builder()
                .eventId(900L)
                .eventType("POST")
                .payload("{\"eventId\":900,\"sourceId\":11,\"targetId\":101,\"status\":\"PUBLISHED\",\"projectionKey\":\"post:101\",\"projectionVersion\":3}")
                .retryCount(0)
                .build();
        when(outboxRepository.fetchPending(eq("NEW"), Mockito.any(Date.class), eq(100)))
                .thenReturn(List.of(outbox));
        when(outboxRepository.fetchPending(eq("FAIL"), Mockito.any(Date.class), eq(100)))
                .thenReturn(List.of());
        when(relationEventPort.publishCounterProjection(900L, "POST", 11L, 101L, "PUBLISHED", "post:101", 3L))
                .thenReturn(true);

        job.publishPending();

        verify(relationEventPort).publishCounterProjection(900L, "POST", 11L, 101L, "PUBLISHED", "post:101", 3L);
        verify(outboxRepository).markSent(900L);
        verify(outboxRepository, never()).markFail(eq(900L), Mockito.any(Date.class));
    }

    @Test
    void publishPending_shouldNotMarkPostOutboxSentWhenPublishConfirmFails() {
        IRelationEventOutboxRepository outboxRepository = Mockito.mock(IRelationEventOutboxRepository.class);
        IRelationEventPort relationEventPort = Mockito.mock(IRelationEventPort.class);
        RelationEventOutboxPublishJob job = new RelationEventOutboxPublishJob(outboxRepository, relationEventPort);
        RelationEventOutboxVO outbox = RelationEventOutboxVO.builder()
                .eventId(901L)
                .eventType("POST")
                .payload("{\"eventId\":901,\"sourceId\":11,\"targetId\":101,\"status\":\"UNPUBLISHED\",\"projectionKey\":\"post:101\",\"projectionVersion\":4}")
                .retryCount(0)
                .build();
        when(outboxRepository.fetchPending(eq("NEW"), Mockito.any(Date.class), eq(100)))
                .thenReturn(List.of(outbox));
        when(outboxRepository.fetchPending(eq("FAIL"), Mockito.any(Date.class), eq(100)))
                .thenReturn(List.of());
        when(relationEventPort.publishCounterProjection(901L, "POST", 11L, 101L, "UNPUBLISHED", "post:101", 4L))
                .thenReturn(false);

        job.publishPending();

        verify(relationEventPort).publishCounterProjection(901L, "POST", 11L, 101L, "UNPUBLISHED", "post:101", 4L);
        verify(outboxRepository, never()).markSent(901L);
        verify(outboxRepository).markFail(eq(901L), Mockito.any(Date.class));
    }
}
