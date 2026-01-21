package cn.nexus.infrastructure.dao.social.po;

import lombok.Data;

import java.util.Date;

/**
 * 通知收件箱表映射：interaction_notification。
 *
 * @author codex
 * @since 2026-01-21
 */
@Data
public class InteractionNotificationPO {
    private Long notificationId;
    private Long toUserId;
    private String bizType;
    private String targetType;
    private Long targetId;
    private Long postId;
    private Long rootCommentId;
    private Long lastActorUserId;
    private Long lastCommentId;
    private Long unreadCount;
    private Date createTime;
    private Date updateTime;
}

