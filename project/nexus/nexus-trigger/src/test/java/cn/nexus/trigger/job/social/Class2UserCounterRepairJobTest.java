package cn.nexus.trigger.job.social;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.domain.counter.adapter.service.IUserCounterService;
import cn.nexus.domain.social.adapter.repository.IClass2UserCounterRepairTaskRepository;
import cn.nexus.domain.social.model.valobj.Class2UserCounterRepairTaskVO;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class Class2UserCounterRepairJobTest {

    @Test
    void repairPending_shouldMarkDoneOnSuccess() {
        IClass2UserCounterRepairTaskRepository repository = Mockito.mock(IClass2UserCounterRepairTaskRepository.class);
        IUserCounterService userCounterService = Mockito.mock(IUserCounterService.class);
        StringRedisTemplate redisTemplate = redisTemplateAlwaysTrue();
        Class2UserCounterRepairJob job = new Class2UserCounterRepairJob(repository, userCounterService, redisTemplate);

        when(repository.claimBatch(any(), eq(100), any(Date.class), any(Date.class)))
                .thenReturn(List.of(task(1L, 11L, "worker")));

        job.repairPending();

        verify(userCounterService).repairClass2Counters(11L);
        verify(repository).markDone(eq(1L), any());
    }

    @Test
    void repairPending_shouldMarkRetryOnFailure() {
        IClass2UserCounterRepairTaskRepository repository = Mockito.mock(IClass2UserCounterRepairTaskRepository.class);
        IUserCounterService userCounterService = Mockito.mock(IUserCounterService.class);
        StringRedisTemplate redisTemplate = redisTemplateAlwaysTrue();
        Class2UserCounterRepairJob job = new Class2UserCounterRepairJob(repository, userCounterService, redisTemplate);

        when(repository.claimBatch(any(), eq(100), any(Date.class), any(Date.class)))
                .thenReturn(List.of(task(2L, 11L, "worker")));
        Mockito.doThrow(new IllegalStateException("boom")).when(userCounterService).repairClass2Counters(11L);

        job.repairPending();

        verify(repository).markRetry(eq(2L), any(), any(Date.class), contains("boom"));
    }

    @Test
    void repairPending_shouldReleaseWhenRateLimited() {
        IClass2UserCounterRepairTaskRepository repository = Mockito.mock(IClass2UserCounterRepairTaskRepository.class);
        IUserCounterService userCounterService = Mockito.mock(IUserCounterService.class);
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq("ucnt:repair:rate:11"), eq("1"), any(Long.class), any()))
                .thenReturn(Boolean.FALSE);
        Class2UserCounterRepairJob job = new Class2UserCounterRepairJob(repository, userCounterService, redisTemplate);

        when(repository.claimBatch(any(), eq(100), any(Date.class), any(Date.class)))
                .thenReturn(List.of(task(3L, 11L, "worker")));

        job.repairPending();

        verify(userCounterService, never()).repairClass2Counters(11L);
        verify(repository).release(eq(3L), any(), any(Date.class), eq("rate limit"));
    }

    @Test
    void repairPending_shouldProcessTaskClaimedFromExpiredLease() {
        IClass2UserCounterRepairTaskRepository repository = Mockito.mock(IClass2UserCounterRepairTaskRepository.class);
        IUserCounterService userCounterService = Mockito.mock(IUserCounterService.class);
        StringRedisTemplate redisTemplate = redisTemplateAlwaysTrue();
        Class2UserCounterRepairJob job = new Class2UserCounterRepairJob(repository, userCounterService, redisTemplate);

        Class2UserCounterRepairTaskVO reclaimed = Class2UserCounterRepairTaskVO.builder()
                .taskId(4L)
                .repairType("USER_CLASS2")
                .userId(11L)
                .dedupeKey("USER_CLASS2:11")
                .status("RUNNING")
                .retryCount(1)
                .claimOwner("worker-new")
                .claimedAt(new Date(System.currentTimeMillis() - 10_000L))
                .leaseUntil(new Date(System.currentTimeMillis() - 5_000L))
                .nextRetryTime(new Date())
                .createTime(new Date(System.currentTimeMillis() - 60_000L))
                .updateTime(new Date(System.currentTimeMillis() - 10_000L))
                .build();
        when(repository.claimBatch(any(), eq(100), any(Date.class), any(Date.class)))
                .thenReturn(List.of(reclaimed));

        job.repairPending();

        verify(userCounterService).repairClass2Counters(11L);
        verify(repository).markDone(eq(4L), any());
    }

    private StringRedisTemplate redisTemplateAlwaysTrue() {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(any(), eq("1"), any(Long.class), any()))
                .thenReturn(Boolean.TRUE);
        return redisTemplate;
    }

    private Class2UserCounterRepairTaskVO task(Long taskId, Long userId, String owner) {
        return Class2UserCounterRepairTaskVO.builder()
                .taskId(taskId)
                .repairType("USER_CLASS2")
                .userId(userId)
                .dedupeKey("USER_CLASS2:" + userId)
                .status("RUNNING")
                .retryCount(0)
                .claimOwner(owner)
                .claimedAt(new Date())
                .leaseUntil(new Date(System.currentTimeMillis() + 60_000L))
                .nextRetryTime(new Date())
                .createTime(new Date())
                .updateTime(new Date())
                .build();
    }
}
