package cn.nexus.domain.social.adapter.repository;

import cn.nexus.domain.social.model.valobj.UserCounterRepairOutboxVO;
import java.util.Date;
import java.util.List;

public interface IUserCounterRepairOutboxRepository {

    void save(Long sourceUserId, Long targetUserId, String operation, String reason, String correlationId);

    List<UserCounterRepairOutboxVO> fetchPending(String status, Date now, int limit);

    void markDone(Long id);

    void markFail(Long id, Date nextRetryTime);
}
