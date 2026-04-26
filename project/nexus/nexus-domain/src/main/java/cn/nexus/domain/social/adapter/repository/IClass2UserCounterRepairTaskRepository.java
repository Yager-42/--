package cn.nexus.domain.social.adapter.repository;

import cn.nexus.domain.social.model.valobj.Class2UserCounterRepairTaskVO;
import java.util.Date;
import java.util.List;

public interface IClass2UserCounterRepairTaskRepository {

    void enqueue(String repairType, Long userId, String reason, String dedupeKey);

    List<Class2UserCounterRepairTaskVO> claimBatch(String owner, int limit, Date now, Date leaseUntil);

    void markDone(Long taskId, String owner);

    void markRetry(Long taskId, String owner, Date nextRetryTime, String lastError);

    void release(Long taskId, String owner, Date nextRetryTime, String reason);
}

