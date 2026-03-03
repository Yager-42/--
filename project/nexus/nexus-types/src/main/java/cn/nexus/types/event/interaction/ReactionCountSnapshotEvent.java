package cn.nexus.types.event.interaction;

import lombok.Data;

/**
 * Snapshot of a reaction count in Redis, used for async DB alignment.
 *
 * <p>We intentionally send absolute counts (not deltas) to make the DB consumer idempotent.</p>
 */
@Data
public class ReactionCountSnapshotEvent {
    /** target_type in DB, e.g. POST/USER */
    private String targetType;
    /** target_id in DB */
    private Long targetId;
    /** reaction_type in DB, e.g. LIKE */
    private String reactionType;
    /** absolute count snapshot */
    private Long count;
}
