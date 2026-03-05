package cn.nexus.types.event.interaction;

import lombok.Data;

/**
 * Delta change of a reaction count, used for async DB alignment.
 */
@Data
public class ReactionCountDeltaEvent {
    /** 上游事件 ID（幂等键） */
    private String eventId;
    /** target_type in DB, e.g. POST/USER */
    private String targetType;
    /** target_id in DB */
    private Long targetId;
    /** reaction_type in DB, e.g. LIKE */
    private String reactionType;
    /** delta (can be negative) */
    private Long delta;
}
