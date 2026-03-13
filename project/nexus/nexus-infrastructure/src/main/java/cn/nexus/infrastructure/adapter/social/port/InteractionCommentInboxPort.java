package cn.nexus.infrastructure.adapter.social.port;

import cn.nexus.domain.social.adapter.port.IInteractionCommentInboxPort;
import cn.nexus.infrastructure.dao.social.IInteractionCommentInboxDao;
import cn.nexus.infrastructure.dao.social.po.InteractionCommentInboxPO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 评论事件收件箱持久化实现：用于 MQ 幂等去重。
 *
 * @author codex
 * @since 2026-01-22
 */
@Component
@RequiredArgsConstructor
public class InteractionCommentInboxPort implements IInteractionCommentInboxPort {

    private final IInteractionCommentInboxDao inboxDao;

    @Override
    public boolean save(String eventId, String eventType, String payload) {
        if (eventId == null || eventId.isBlank()) {
            return false;
        }
        InteractionCommentInboxPO po = new InteractionCommentInboxPO();
        po.setEventId(eventId);
        po.setEventType(eventType == null ? "" : eventType);
        po.setPayload(payload);
        return inboxDao.insertIgnore(po) > 0;
    }
}

