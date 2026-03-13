package cn.nexus.types.event.interaction;

import cn.nexus.types.event.BaseEvent;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 一级评论点赞数变更事件：可选（如果你把点赞链路的结果回写到评论热榜）。
 *
 * @author codex
 * @since 2026-01-14
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class CommentLikeChangedEvent extends BaseEvent {
    private Long rootCommentId;
    private Long postId;
    private Long delta;
    private Long tsMs;
}

