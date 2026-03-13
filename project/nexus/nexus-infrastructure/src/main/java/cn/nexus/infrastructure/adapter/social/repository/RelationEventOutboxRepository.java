package cn.nexus.infrastructure.adapter.social.repository;

import cn.nexus.domain.social.adapter.repository.IRelationEventOutboxRepository;
import cn.nexus.domain.social.model.valobj.RelationEventOutboxVO;
import cn.nexus.infrastructure.dao.social.IRelationEventOutboxDao;
import cn.nexus.infrastructure.dao.social.po.RelationEventOutboxPO;
import java.util.Date;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * 关系事件 Outbox 仓储实现。
 */
@Repository
@RequiredArgsConstructor
public class RelationEventOutboxRepository implements IRelationEventOutboxRepository {

    private final IRelationEventOutboxDao outboxDao;

    @Override
    public void save(Long eventId, String eventType, String payload) {
        if (eventId == null || eventType == null || eventType.isBlank() || payload == null || payload.isBlank()) {
            return;
        }
        RelationEventOutboxPO po = new RelationEventOutboxPO();
        po.setEventId(eventId);
        po.setEventType(eventType);
        po.setPayload(payload);
        po.setStatus("NEW");
        po.setRetryCount(0);
        po.setNextRetryTime(new Date());
        outboxDao.insertIgnore(po);
    }

    @Override
    public List<RelationEventOutboxVO> fetchPending(String status, Date now, int limit) {
        return outboxDao.selectByStatus(status, now, limit).stream()
                .map(this::toVO)
                .toList();
    }

    @Override
    public void markSent(Long eventId) {
        if (eventId == null) {
            return;
        }
        outboxDao.markSent(eventId);
    }

    @Override
    public void markFail(Long eventId, Date nextRetryTime) {
        if (eventId == null) {
            return;
        }
        outboxDao.markFail(eventId, nextRetryTime == null ? new Date() : nextRetryTime);
    }

    @Override
    public int cleanSentBefore(Date beforeTime) {
        return outboxDao.deleteOlderThan(beforeTime, "DONE");
    }

    private RelationEventOutboxVO toVO(RelationEventOutboxPO po) {
        if (po == null) {
            return null;
        }
        return RelationEventOutboxVO.builder()
                .eventId(po.getEventId())
                .eventType(po.getEventType())
                .payload(po.getPayload())
                .status(po.getStatus())
                .retryCount(po.getRetryCount())
                .nextRetryTime(po.getNextRetryTime())
                .createTime(po.getCreateTime())
                .updateTime(po.getUpdateTime())
                .build();
    }
}
