package cn.nexus.trigger.job.social;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.domain.counter.adapter.service.IUserCounterService;
import cn.nexus.domain.social.adapter.repository.IRelationRepository;
import cn.nexus.domain.social.adapter.repository.IUserCounterRepairOutboxRepository;
import cn.nexus.domain.social.model.valobj.UserCounterRepairOutboxVO;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class UserCounterRepairJobTest {

    @Test
    void repairReady_shouldRecomputeCountsAndMarkDone() {
        IUserCounterRepairOutboxRepository outboxRepository = Mockito.mock(IUserCounterRepairOutboxRepository.class);
        IRelationRepository relationRepository = Mockito.mock(IRelationRepository.class);
        IUserCounterService userCounterService = Mockito.mock(IUserCounterService.class);
        when(outboxRepository.fetchPending(eq("NEW"), any(Date.class), eq(100)))
                .thenReturn(List.of(item(1L, 101L, 201L, "FOLLOW", "evt-1")));

        UserCounterRepairJob job = new UserCounterRepairJob(outboxRepository, relationRepository, userCounterService);

        job.repairReady();

        verify(userCounterService).rebuildAllCounters(101L);
        verify(userCounterService).rebuildAllCounters(201L);
        verify(outboxRepository).markDone(1L);
        verify(outboxRepository, never()).markFail(eq(1L), any(Date.class));
    }

    @Test
    void repairReady_shouldMarkFailWhenRepairThrows() {
        IUserCounterRepairOutboxRepository outboxRepository = Mockito.mock(IUserCounterRepairOutboxRepository.class);
        IRelationRepository relationRepository = Mockito.mock(IRelationRepository.class);
        IUserCounterService userCounterService = Mockito.mock(IUserCounterService.class);
        when(outboxRepository.fetchPending(eq("NEW"), any(Date.class), eq(100)))
                .thenReturn(List.of(item(2L, 101L, 201L, "FOLLOW", "evt-2")));
        Mockito.doThrow(new RuntimeException("redis down"))
                .when(userCounterService).rebuildAllCounters(101L);

        UserCounterRepairJob job = new UserCounterRepairJob(outboxRepository, relationRepository, userCounterService);

        job.repairReady();

        verify(outboxRepository, never()).markDone(2L);
        verify(outboxRepository).markFail(eq(2L), any(Date.class));
    }

    private UserCounterRepairOutboxVO item(Long id,
                                           Long sourceUserId,
                                           Long targetUserId,
                                           String operation,
                                           String correlationId) {
        return UserCounterRepairOutboxVO.builder()
                .id(id)
                .sourceUserId(sourceUserId)
                .targetUserId(targetUserId)
                .operation(operation)
                .reason("COUNT_REDIS_WRITE_FAILED")
                .correlationId(correlationId)
                .status("NEW")
                .retryCount(0)
                .nextRetryTime(new Date())
                .build();
    }
}
