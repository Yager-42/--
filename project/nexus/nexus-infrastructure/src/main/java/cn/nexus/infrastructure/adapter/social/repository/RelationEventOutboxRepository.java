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
 * 关系事件 Outbox 仓储实现：负责把待发事件存库、重试和清理。
 *
 * @author m0_52354773
 * @author codex
 * @since 2026-03-08
 */
@Repository
@RequiredArgsConstructor
public class RelationEventOutboxRepository implements IRelationEventOutboxRepository {

    private final IRelationEventOutboxDao outboxDao;

    /**
     * 执行 save 逻辑。
     *
     * @param eventId eventId 参数。类型：{@link Long}
     * @param eventType eventType 参数。类型：{@link String}
     * @param payload payload 参数。类型：{@link String}
     */
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

    /**
     * 执行 fetchPending 逻辑。
     *
     * @param status status 参数。类型：{@link String}
     * @param now now 参数。类型：{@link Date}
     * @param limit 分页大小。类型：{@code int}
     * @return 处理结果。类型：{@link List}
     */
    @Override
    public List<RelationEventOutboxVO> fetchPending(String status, Date now, int limit) {
        return outboxDao.selectByStatus(status, now, limit).stream()
                .map(this::toVO)
                .toList();
    }

    /**
     * 执行 markSent 逻辑。
     *
     * @param eventId eventId 参数。类型：{@link Long}
     */
    @Override
    public void markSent(Long eventId) {
        if (eventId == null) {
            return;
        }
        outboxDao.markSent(eventId);
    }

    /**
     * 执行 markFail 逻辑。
     *
     * @param eventId eventId 参数。类型：{@link Long}
     * @param nextRetryTime nextRetryTime 参数。类型：{@link Date}
     */
    @Override
    public void markFail(Long eventId, Date nextRetryTime) {
        if (eventId == null) {
            return;
        }
        outboxDao.markFail(eventId, nextRetryTime == null ? new Date() : nextRetryTime);
    }

    /**
     * 执行 cleanSentBefore 逻辑。
     *
     * @param beforeTime beforeTime 参数。类型：{@link Date}
     * @return 处理结果。类型：{@code int}
     */
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
