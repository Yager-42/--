package cn.nexus.types.event.interaction;

import cn.nexus.types.event.BaseEvent;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 通知统一事件：`LIKE / COMMENT / @mention` 都收敛成这一种消息结构。
 *
 * <p>字段设计刻意保持“笨”：消费者只做幂等、目标归属解析和一条 `UPSERT`。</p>
 *
 * @author rr
 * @author codex
 * @since 2026-01-21
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class InteractionNotifyEvent extends BaseEvent {
    /** 事件类型 */
    private EventType eventType;

    /** 触发者 */
    private Long fromUserId;

    /** 收件人：仅 COMMENT_MENTIONED 必填；其他场景由消费者按 target 归属回表解析 */
    private Long toUserId;

    /** 目标类型：POST / COMMENT */
    private String targetType;

    /** 目标 ID：postId 或 commentId（被互动的目标） */
    private Long targetId;

    /** 关联 postId：COMMENT 场景强烈建议填；LIKE/POST 场景等于 targetId */
    private Long postId;

    /** COMMENT 场景可选：用于两级盖楼定位 */
    private Long rootCommentId;

    /** COMMENT_CREATED / COMMENT_MENTIONED 时为新评论 ID */
    private Long commentId;

    /** 事件时间戳（毫秒） */
    private Long tsMs;

    /** 点赞链路请求号（可选，但 LIKE_ADDED 会补齐并用作 eventId） */
    private String requestId;
}
