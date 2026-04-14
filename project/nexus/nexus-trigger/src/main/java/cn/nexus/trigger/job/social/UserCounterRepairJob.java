package cn.nexus.trigger.job.social;

import cn.nexus.domain.counter.adapter.port.IUserCounterPort;
import cn.nexus.domain.counter.model.valobj.UserCounterType;
import cn.nexus.domain.social.adapter.repository.IRelationRepository;
import cn.nexus.domain.social.adapter.repository.IUserCounterRepairOutboxRepository;
import cn.nexus.domain.social.model.valobj.UserCounterRepairOutboxVO;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class UserCounterRepairJob {

    private final IUserCounterRepairOutboxRepository outboxRepository;
    private final IRelationRepository relationRepository;
    private final IUserCounterPort userCounterPort;

    @Scheduled(fixedDelay = 60000)
    public void repairReady() {
        List<UserCounterRepairOutboxVO> items = outboxRepository.fetchPending("NEW", new Date(), 100);
        for (UserCounterRepairOutboxVO item : items) {
            try {
                repairItem(item);
                outboxRepository.markDone(item.getId());
            } catch (Exception e) {
                outboxRepository.markFail(item.getId(), nextRetryTime(item.getRetryCount()));
                log.warn("user counter repair failed, id={}, correlationId={}",
                        item == null ? null : item.getId(),
                        item == null ? null : item.getCorrelationId(), e);
            }
        }
    }

    private void repairItem(UserCounterRepairOutboxVO item) {
        if (item == null) {
            return;
        }
        Set<Long> affectedUserIds = new LinkedHashSet<>();
        if (item.getSourceUserId() != null) {
            affectedUserIds.add(item.getSourceUserId());
        }
        if (item.getTargetUserId() != null) {
            affectedUserIds.add(item.getTargetUserId());
        }
        for (Long userId : affectedUserIds) {
            long following = Math.max(0, relationRepository.countActiveRelationsBySource(userId, 1));
            long follower = Math.max(0, relationRepository.countFollowerIds(userId));
            userCounterPort.setCount(userId, UserCounterType.FOLLOWING, following);
            userCounterPort.setCount(userId, UserCounterType.FOLLOWER, follower);
        }
    }

    private Date nextRetryTime(Integer retryCount) {
        int current = retryCount == null ? 0 : Math.max(0, retryCount);
        long delayMs = 60_000L * (current + 1L);
        return new Date(System.currentTimeMillis() + delayMs);
    }
}
