package cn.nexus.trigger.job.social;

import cn.nexus.domain.counter.adapter.service.IUserCounterService;
import cn.nexus.domain.social.adapter.repository.IClass2UserCounterRepairTaskRepository;
import cn.nexus.domain.social.model.valobj.Class2UserCounterRepairTaskVO;
import cn.nexus.infrastructure.adapter.counter.support.CountRedisOperations;
import java.net.InetAddress;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class Class2UserCounterRepairJob {

    private final IClass2UserCounterRepairTaskRepository repairTaskRepository;
    private final IUserCounterService userCounterService;
    private final StringRedisTemplate redisTemplate;

    @Scheduled(fixedDelay = 5000)
    public void repairPending() {
        CountRedisOperations operations = new CountRedisOperations(redisTemplate);
        String owner = hostName() + ":" + UUID.randomUUID();
        Date now = new Date();
        Date leaseUntil = new Date(now.getTime() + 60_000L);
        List<Class2UserCounterRepairTaskVO> claimed = repairTaskRepository.claimBatch(owner, 100, now, leaseUntil);
        for (Class2UserCounterRepairTaskVO task : claimed) {
            if (task == null || task.getUserId() == null) {
                continue;
            }
            Long userId = task.getUserId();
            String lockKey = "ucnt:repair:lock:" + userId;
            String rateKey = "ucnt:repair:rate:" + userId;
            if (!operations.tryAcquireRateLimit(rateKey, 30L)) {
                repairTaskRepository.release(task.getTaskId(), owner,
                        new Date(System.currentTimeMillis() + 5_000L), "rate limit");
                continue;
            }
            if (!operations.tryAcquireRebuildLock(lockKey, 15L)) {
                repairTaskRepository.release(task.getTaskId(), owner,
                        new Date(System.currentTimeMillis() + 5_000L), "user lock busy");
                continue;
            }
            try {
                userCounterService.repairClass2Counters(userId);
                repairTaskRepository.markDone(task.getTaskId(), owner);
            } catch (Exception e) {
                log.warn("class2 repair failed, taskId={}, userId={}", task.getTaskId(), userId, e);
                repairTaskRepository.markRetry(
                        task.getTaskId(),
                        owner,
                        nextRetryTime(task.getRetryCount()),
                        e.getMessage());
            } finally {
                operations.releaseRebuildLock(lockKey);
            }
        }
    }

    private Date nextRetryTime(Integer retryCount) {
        int retry = retryCount == null ? 0 : Math.max(0, retryCount);
        long delayMs = Math.min(60_000L * (retry + 1L), 10 * 60_000L);
        return new Date(System.currentTimeMillis() + delayMs);
    }

    private String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception ignored) {
            return "unknown-host";
        }
    }
}

