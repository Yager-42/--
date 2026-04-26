package cn.nexus.infrastructure.adapter.social.repository;

import cn.nexus.domain.social.adapter.port.ISocialIdPort;
import cn.nexus.domain.social.adapter.repository.IClass2UserCounterRepairTaskRepository;
import cn.nexus.domain.social.model.valobj.Class2UserCounterRepairTaskVO;
import cn.nexus.infrastructure.dao.social.IClass2UserCounterRepairTaskDao;
import cn.nexus.infrastructure.dao.social.po.Class2UserCounterRepairTaskPO;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class Class2UserCounterRepairTaskRepository implements IClass2UserCounterRepairTaskRepository {

    private final IClass2UserCounterRepairTaskDao taskDao;
    private final ISocialIdPort socialIdPort;

    @Override
    public void enqueue(String repairType, Long userId, String reason, String dedupeKey) {
        if (repairType == null || repairType.isBlank() || userId == null || dedupeKey == null || dedupeKey.isBlank()) {
            return;
        }
        Date now = new Date();
        taskDao.insertIgnore(
                socialIdPort.nextId(),
                repairType,
                userId,
                dedupeKey,
                "PENDING",
                0,
                now,
                reason,
                now,
                now);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<Class2UserCounterRepairTaskVO> claimBatch(String owner, int limit, Date now, Date leaseUntil) {
        if (owner == null || owner.isBlank()) {
            return List.of();
        }
        int boundedLimit = Math.max(1, Math.min(limit, 200));
        Date claimNow = now == null ? new Date() : now;
        Date lease = leaseUntil == null ? new Date(claimNow.getTime() + 60_000L) : leaseUntil;

        List<Long> taskIds = taskDao.selectClaimableTaskIds(claimNow, boundedLimit);
        if (taskIds == null || taskIds.isEmpty()) {
            return List.of();
        }
        taskDao.claimBatch(taskIds, owner, claimNow, lease);
        List<Class2UserCounterRepairTaskPO> list = taskDao.selectByTaskIds(taskIds);
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        List<Class2UserCounterRepairTaskVO> result = new ArrayList<>(list.size());
        for (Class2UserCounterRepairTaskPO po : list) {
            if (po == null) {
                continue;
            }
            if (!owner.equals(po.getClaimOwner())) {
                continue;
            }
            result.add(toVO(po));
        }
        return result;
    }

    @Override
    public void markDone(Long taskId, String owner) {
        if (taskId == null || owner == null || owner.isBlank()) {
            return;
        }
        taskDao.markDone(taskId, owner, new Date());
    }

    @Override
    public void markRetry(Long taskId, String owner, Date nextRetryTime, String lastError) {
        if (taskId == null || owner == null || owner.isBlank()) {
            return;
        }
        taskDao.markRetry(
                taskId,
                owner,
                nextRetryTime == null ? new Date() : nextRetryTime,
                lastError,
                new Date());
    }

    @Override
    public void release(Long taskId, String owner, Date nextRetryTime, String reason) {
        if (taskId == null || owner == null || owner.isBlank()) {
            return;
        }
        taskDao.release(
                taskId,
                owner,
                nextRetryTime == null ? new Date() : nextRetryTime,
                reason,
                new Date());
    }

    private Class2UserCounterRepairTaskVO toVO(Class2UserCounterRepairTaskPO po) {
        return Class2UserCounterRepairTaskVO.builder()
                .taskId(po.getTaskId())
                .repairType(po.getRepairType())
                .userId(po.getUserId())
                .dedupeKey(po.getDedupeKey())
                .status(po.getStatus())
                .retryCount(po.getRetryCount())
                .claimOwner(po.getClaimOwner())
                .claimedAt(po.getClaimedAt())
                .leaseUntil(po.getLeaseUntil())
                .nextRetryTime(po.getNextRetryTime())
                .reason(po.getReason())
                .lastError(po.getLastError())
                .createTime(po.getCreateTime())
                .updateTime(po.getUpdateTime())
                .build();
    }
}

