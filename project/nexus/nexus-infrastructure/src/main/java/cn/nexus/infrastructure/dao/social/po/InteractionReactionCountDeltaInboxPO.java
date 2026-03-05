package cn.nexus.infrastructure.dao.social.po;

import java.util.Date;
import lombok.Data;

/**
 * 计数增量幂等收件箱表映射：interaction_reaction_count_delta_inbox。
 *
 * @author codex
 * @since 2026-03-04
 */
@Data
public class InteractionReactionCountDeltaInboxPO {
    private String eventId;
    private String targetType;
    private Long targetId;
    private String reactionType;
    private Date createTime;
}
