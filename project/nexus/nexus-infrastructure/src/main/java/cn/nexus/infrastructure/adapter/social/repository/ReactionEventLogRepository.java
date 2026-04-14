package cn.nexus.infrastructure.adapter.social.repository;

import cn.nexus.infrastructure.dao.social.IInteractionReactionEventLogDao;
import cn.nexus.infrastructure.dao.social.po.InteractionReactionEventLogPO;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ReactionEventLogRepository {

    private final IInteractionReactionEventLogDao eventLogDao;

    public String append(String eventId,
                         String targetType,
                         Long targetId,
                         String reactionType,
                         Long userId,
                         int desiredState,
                         int delta,
                         long eventTime) {
        if (delta == 0) {
            return "duplicate";
        }
        InteractionReactionEventLogPO po = new InteractionReactionEventLogPO();
        po.setEventId(eventId == null ? null : eventId.trim());
        po.setTargetType(targetType);
        po.setTargetId(targetId);
        po.setReactionType(reactionType);
        po.setUserId(userId);
        po.setDesiredState(desiredState);
        po.setDelta(delta);
        po.setEventTime(eventTime);
        int inserted = eventLogDao.insertIgnore(po);
        return inserted > 0 ? "inserted" : "duplicate";
    }

    public List<InteractionReactionEventLogPO> pageAfterSeq(String targetType, String reactionType, Long cursor, int limit) {
        return eventLogDao.selectPage(targetType, reactionType, cursor == null ? 0L : cursor, limit);
    }
}
