package cn.nexus.infrastructure.adapter.social.repository;

import cn.nexus.domain.social.adapter.repository.IUserCounterRepairOutboxRepository;
import cn.nexus.domain.social.model.valobj.UserCounterRepairOutboxVO;
import cn.nexus.infrastructure.dao.social.IUserCounterRepairOutboxDao;
import cn.nexus.infrastructure.dao.social.po.UserCounterRepairOutboxPO;
import java.util.Date;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class UserCounterRepairOutboxRepository implements IUserCounterRepairOutboxRepository {

    private final IUserCounterRepairOutboxDao outboxDao;

    @Override
    public void save(Long sourceUserId, Long targetUserId, String operation, String reason, String correlationId) {
        if (sourceUserId == null || targetUserId == null
                || operation == null || operation.isBlank()
                || reason == null || reason.isBlank()) {
            return;
        }
        UserCounterRepairOutboxPO po = new UserCounterRepairOutboxPO();
        po.setSourceUserId(sourceUserId);
        po.setTargetUserId(targetUserId);
        po.setOperation(operation);
        po.setReason(reason);
        po.setCorrelationId(correlationId);
        po.setStatus("NEW");
        po.setRetryCount(0);
        po.setNextRetryTime(new Date());
        outboxDao.insertIgnore(po);
    }

    @Override
    public List<UserCounterRepairOutboxVO> fetchPending(String status, Date now, int limit) {
        return outboxDao.selectByStatus(status, now, limit).stream()
                .map(this::toVO)
                .toList();
    }

    @Override
    public void markDone(Long id) {
        if (id == null) {
            return;
        }
        outboxDao.markDone(id);
    }

    @Override
    public void markFail(Long id, Date nextRetryTime) {
        if (id == null) {
            return;
        }
        outboxDao.markFail(id, nextRetryTime == null ? new Date() : nextRetryTime);
    }

    private UserCounterRepairOutboxVO toVO(UserCounterRepairOutboxPO po) {
        if (po == null) {
            return null;
        }
        return UserCounterRepairOutboxVO.builder()
                .id(po.getId())
                .sourceUserId(po.getSourceUserId())
                .targetUserId(po.getTargetUserId())
                .operation(po.getOperation())
                .reason(po.getReason())
                .correlationId(po.getCorrelationId())
                .status(po.getStatus())
                .retryCount(po.getRetryCount())
                .nextRetryTime(po.getNextRetryTime())
                .createTime(po.getCreateTime())
                .updateTime(po.getUpdateTime())
                .build();
    }
}
