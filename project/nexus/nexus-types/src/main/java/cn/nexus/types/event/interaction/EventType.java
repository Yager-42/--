package cn.nexus.types.event.interaction;

/**
 * 通知统一事件类型（用于 MQ 通知旁路）。
 *
 * <p>注意：这里只描述“发生了什么”，业务聚合后的 bizType 在通知消费者里推导。</p>
 *
 * @author codex
 * @since 2026-01-21
 */
public enum EventType {
    /** 点赞新增（仅 delta=+1 才会发） */
    LIKE_ADDED,
    /** 评论创建（包含直接评论 post 与回复评论） */
    COMMENT_CREATED,
    /** 评论内提及某用户（按收件人拆分为多条事件） */
    COMMENT_MENTIONED
}

