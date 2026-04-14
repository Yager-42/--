package cn.nexus.trigger.job.social;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.domain.counter.adapter.port.IUserCounterPort;
import cn.nexus.domain.counter.model.valobj.UserCounterType;
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
        IUserCounterPort userCounterPort = Mockito.mock(IUserCounterPort.class);
        when(outboxRepository.fetchPending(eq("NEW"), any(Date.class), eq(100)))
                .thenReturn(List.of(item(1L, 101L, 201L, "FOLLOW", "evt-1")));
        when(relationRepository.countActiveRelationsBySource(101L, 1)).thenReturn(7);
        when(relationRepository.countFollowerIds(101L)).thenReturn(3);
        when(relationRepository.countActiveRelationsBySource(201L, 1)).thenReturn(5);
        when(relationRepository.countFollowerIds(201L)).thenReturn(9);

        UserCounterRepairJob job = new UserCounterRepairJob(outboxRepository, relationRepository, userCounterPort);

        job.repairReady();

        verify(userCounterPort).setCount(101L, UserCounterType.FOLLOWING, 7);
        verify(userCounterPort).setCount(101L, UserCounterType.FOLLOWER, 3);
        verify(userCounterPort).setCount(201L, UserCounterType.FOLLOWING, 5);
        verify(userCounterPort).setCount(201L, UserCounterType.FOLLOWER, 9);
        verify(outboxRepository).markDone(1L);
        verify(outboxRepository, never()).markFail(eq(1L), any(Date.class));
    }

    @Test
    void repairReady_shouldMarkFailWhenRepairThrows() {
        IUserCounterRepairOutboxRepository outboxRepository = Mockito.mock(IUserCounterRepairOutboxRepository.class);
        IRelationRepository relationRepository = Mockito.mock(IRelationRepository.class);
        IUserCounterPort userCounterPort = Mockito.mock(IUserCounterPort.class);
        when(outboxRepository.fetchPending(eq("NEW"), any(Date.class), eq(100)))
                .thenReturn(List.of(item(2L, 101L, 201L, "FOLLOW", "evt-2")));
        when(relationRepository.countActiveRelationsBySource(101L, 1)).thenReturn(7);
        when(relationRepository.countFollowerIds(101L)).thenReturn(3);
        Mockito.doThrow(new RuntimeException("redis down"))
                .when(userCounterPort).setCount(101L, UserCounterType.FOLLOWING, 7);

        UserCounterRepairJob job = new UserCounterRepairJob(outboxRepository, relationRepository, userCounterPort);

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
