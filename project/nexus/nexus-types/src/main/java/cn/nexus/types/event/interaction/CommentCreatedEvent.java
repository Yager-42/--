package cn.nexus.types.event.interaction;

import cn.nexus.types.event.BaseEvent;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 评论创建事件：用于异步热榜初始化、通知等。
 *
 * <p>rootId 为 {@code null} 表示一级评论；非 {@code null} 表示楼内回复归属的一级评论 ID。</p>
 *
 * @author codex
 * @since 2026-01-14
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class CommentCreatedEvent extends BaseEvent {
    private Long commentId;
    private Long postId;
    private Long rootId;
    private Long userId;
    private Long createTimeMs;
}

