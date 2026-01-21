package cn.nexus.infrastructure.adapter.social.port;

import cn.nexus.domain.social.adapter.port.IInteractionNotifyInboxPort;
import cn.nexus.infrastructure.dao.social.IInteractionNotifyInboxDao;
import cn.nexus.infrastructure.dao.social.po.InteractionNotifyInboxPO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 通知事件收件箱持久化实现：用于 MQ 幂等去重与状态标记。
 *
 * @author codex
 * @since 2026-01-21
 */
@Component
@RequiredArgsConstructor
public class InteractionNotifyInboxPort implements IInteractionNotifyInboxPort {

    private final IInteractionNotifyInboxDao inboxDao;

    @Override
    public boolean save(String eventId, String eventType, String payload) {
        if (eventId == null || eventId.isBlank()) {
            return false;
        }
        InteractionNotifyInboxPO po = new InteractionNotifyInboxPO();
        po.setEventId(eventId);
        po.setEventType(eventType == null ? "" : eventType);
        po.setPayload(payload);
        po.setStatus("NEW");
        return inboxDao.insertIgnore(po) > 0;
    }

    @Override
    public void markDone(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return;
        }
        inboxDao.updateStatus(eventId, "DONE");
    }

    @Override
    public void markFail(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return;
        }
        inboxDao.updateStatus(eventId, "FAIL");
    }
}

