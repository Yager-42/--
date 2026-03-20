package cn.nexus.infrastructure.adapter.social.port;

import cn.nexus.domain.social.adapter.port.IRelationEventInboxPort;
import cn.nexus.domain.social.model.valobj.RelationEventInboxVO;
import cn.nexus.infrastructure.dao.social.IRelationEventInboxDao;
import cn.nexus.infrastructure.dao.social.po.RelationEventInboxPO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 关系事件收件箱持久化实现：负责去重、失败重试和历史清理的数据落库。
 *
 * @author rr
 * @author codex
 * @since 2025-12-26
 */
@Component
@RequiredArgsConstructor
public class RelationEventInboxPort implements IRelationEventInboxPort {

    private static final String STATUS_NEW = "NEW";
    private static final String STATUS_PROCESSED = "PROCESSED";
    private static final String STATUS_FAILED = "FAILED";

    private final IRelationEventInboxDao relationEventInboxDao;

    /**
     * 执行 save 逻辑。
     *
     * @param eventType eventType 参数。类型：{@link String}
     * @param fingerprint fingerprint 参数。类型：{@link String}
     * @param payload payload 参数。类型：{@link String}
     * @return 处理结果。类型：{@code boolean}
     */
    @Override
    public boolean save(String eventType, String fingerprint, String payload) {
        RelationEventInboxPO po = new RelationEventInboxPO();
        po.setEventType(eventType);
        po.setFingerprint(fingerprint);
        po.setPayload(payload);
        po.setStatus(STATUS_NEW);
        return relationEventInboxDao.insertIgnore(po) > 0;
    }

    /**
     * 执行 markDone 逻辑。
     *
     * @param fingerprint fingerprint 参数。类型：{@link String}
     */
    @Override
    public void markDone(String fingerprint) {
        relationEventInboxDao.updateStatus(fingerprint, STATUS_PROCESSED);
    }

    /**
     * 执行 markFail 逻辑。
     *
     * @param fingerprint fingerprint 参数。类型：{@link String}
     */
    @Override
    public void markFail(String fingerprint) {
        relationEventInboxDao.updateStatus(fingerprint, STATUS_FAILED);
    }

    /**
     * 执行 fetchRetry 逻辑。
     *
     * @param limit 分页大小。类型：{@code int}
     * @return 处理结果。类型：{@link List}
     */
    @Override
    public java.util.List<RelationEventInboxVO> fetchRetry(int limit) {
        return relationEventInboxDao.selectByStatus(STATUS_FAILED, limit).stream()
                .map(this::toVO)
                .toList();
    }

    /**
     * 执行 cleanBefore 逻辑。
     *
     * @param beforeTime beforeTime 参数。类型：{@link Date}
     * @return 处理结果。类型：{@code int}
     */
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
