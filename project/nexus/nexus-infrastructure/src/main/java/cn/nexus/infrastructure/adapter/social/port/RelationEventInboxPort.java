package cn.nexus.infrastructure.adapter.social.port;

import cn.nexus.domain.social.adapter.port.IRelationEventInboxPort;
import cn.nexus.domain.social.model.valobj.RelationEventInboxVO;
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

    private static final String STATUS_NEW = "NEW";
    private static final String STATUS_PROCESSED = "PROCESSED";
    private static final String STATUS_FAILED = "FAILED";

    private final IRelationEventInboxDao relationEventInboxDao;

    @Override
    public boolean save(String eventType, String fingerprint, String payload) {
        RelationEventInboxPO po = new RelationEventInboxPO();
        po.setEventType(eventType);
        po.setFingerprint(fingerprint);
        po.setPayload(payload);
        po.setStatus(STATUS_NEW);
        return relationEventInboxDao.insertIgnore(po) > 0;
    }

    @Override
    public void markDone(String fingerprint) {
        relationEventInboxDao.updateStatus(fingerprint, STATUS_PROCESSED);
    }

    @Override
    public void markFail(String fingerprint) {
        relationEventInboxDao.updateStatus(fingerprint, STATUS_FAILED);
    }

    @Override
    public java.util.List<RelationEventInboxVO> fetchRetry(int limit) {
        return relationEventInboxDao.selectByStatus(STATUS_FAILED, limit).stream()
                .map(this::toVO)
                .toList();
    }

    @Override
    public int cleanBefore(java.util.Date beforeTime) {
        return relationEventInboxDao.deleteOlderThan(beforeTime, STATUS_PROCESSED);
    }

    private RelationEventInboxVO toVO(RelationEventInboxPO po) {
        if (po == null) {
            return null;
        }
        return RelationEventInboxVO.builder()
                .eventType(po.getEventType())
                .fingerprint(po.getFingerprint())
                .payload(po.getPayload())
                .status(po.getStatus())
                .createTime(po.getCreateTime())
                .updateTime(po.getUpdateTime())
                .build();
    }
}
