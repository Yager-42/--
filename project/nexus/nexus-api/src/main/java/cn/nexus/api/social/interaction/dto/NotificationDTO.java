package cn.nexus.api.social.interaction.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 通知条目。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationDTO {
    private String title;
    private String content;
    private Long createTime;

    /** 用于标记已读与稳定分页 */
    private Long notificationId;
    private String bizType;
    private String targetType;
    private Long targetId;
    private Long postId;
    private Long rootCommentId;
    private Long lastCommentId;
    private Long lastActorUserId;
    private Long unreadCount;
}
