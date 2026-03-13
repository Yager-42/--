package cn.nexus.domain.social.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 通知值对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationVO {
    private String title;
    private String content;
    private Long createTime;

    /** 通知行 ID：用于稳定分页与标记已读 */
    private Long notificationId;

    /** 业务类型：POST_LIKED / COMMENT_LIKED / POST_COMMENTED / COMMENT_REPLIED / COMMENT_MENTIONED */
    private String bizType;

    /** 目标类型：POST / COMMENT */
    private String targetType;

    /** 目标 ID：postId 或 commentId */
    private Long targetId;

    /** 关联 postId：用于跳转；COMMENT 场景建议填 */
    private Long postId;

    /** COMMENT 场景：用于两级盖楼定位 */
    private Long rootCommentId;

    /** 最近一次产生的 commentId（用于点击进入定位） */
    private Long lastCommentId;

    /** 最近一次触发者（可用于展示“某某点赞了你”） */
    private Long lastActorUserId;

    /** 未读新增次数（赞/评论的增量） */
    private Long unreadCount;
}
