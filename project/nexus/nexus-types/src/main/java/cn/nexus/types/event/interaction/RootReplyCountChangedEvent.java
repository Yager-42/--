package cn.nexus.types.event.interaction;

import cn.nexus.types.event.BaseEvent;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 一级评论回复数变更事件：只更新一级评论的 reply_count，并驱动热榜更新。
 *
 * @author codex
 * @since 2026-01-14
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class RootReplyCountChangedEvent extends BaseEvent {
    private Long rootCommentId;
    private Long postId;
    private Long delta;
    private Long tsMs;
}

