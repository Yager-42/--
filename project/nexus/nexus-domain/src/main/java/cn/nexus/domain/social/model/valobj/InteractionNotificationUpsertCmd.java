package cn.nexus.domain.social.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 通知聚合写入命令：用于 UPSERT unread_count。
 *
 * @author codex
 * @since 2026-01-21
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InteractionNotificationUpsertCmd {
    private Long toUserId;
    private String bizType;
    private String targetType;
    private Long targetId;
    private Long postId;
    private Long rootCommentId;
    private Long lastActorUserId;
    private Long lastCommentId;
    /** 本次增量，通常为 1 */
    private Long delta;
}

