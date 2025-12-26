package cn.nexus.infrastructure.adapter.social.port;

import cn.nexus.domain.social.adapter.port.IRelationEventInboxPort;
import cn.nexus.infrastructure.dao.social.IRelationEventInboxDao;
import cn.nexus.infrastructure.dao.social.po.RelationEventInboxPO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 关系事件收件箱持久化实现。
 */
@Component
@RequiredArgsConstructor
public class RelationEventInboxPort implements IRelationEventInboxPort {

    private final IRelationEventInboxDao relationEventInboxDao;

    @Override
    public boolean save(String eventType, String fingerprint, String payload) {
        RelationEventInboxPO po = new RelationEventInboxPO();
        po.setEventType(eventType);
        po.setFingerprint(fingerprint);
        po.setPayload(payload);
        po.setStatus("NEW");
        return relationEventInboxDao.insertIgnore(po) > 0;
    }

    @Override
    public void markDone(String fingerprint) {
        relationEventInboxDao.updateStatus(fingerprint, "DONE");
    }

    @Override
    public void markFail(String fingerprint) {
        relationEventInboxDao.updateStatus(fingerprint, "FAIL");
    }

    @Override
    public java.util.List<RelationEventInboxPO> fetchRetry(int limit) {
        return relationEventInboxDao.selectByStatus("FAIL", limit);
    }

    @Override
    public int cleanBefore(java.util.Date beforeTime) {
        return relationEventInboxDao.deleteOlderThan(beforeTime, "DONE");
    }
}
