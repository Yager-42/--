package cn.nexus.domain.social.adapter.repository;

import cn.nexus.domain.social.model.valobj.RelationEventOutboxVO;
import java.util.Date;
import java.util.List;

/**
 * 关系事件 Outbox 仓储。
 */
public interface IRelationEventOutboxRepository {

    void save(Long eventId, String eventType, String payload);

    List<RelationEventOutboxVO> fetchPending(String status, Date now, int limit);

    void markSent(Long eventId);

    void markFail(Long eventId, Date nextRetryTime);

    int cleanSentBefore(Date beforeTime);
}
