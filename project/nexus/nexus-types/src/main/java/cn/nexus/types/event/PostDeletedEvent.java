package cn.nexus.types.event;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 内容删除/下架事件：用于同步推荐池（delete/hide item）等旁路能力。
 *
 * @author codex
 * @since 2026-01-26
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class PostDeletedEvent extends BaseEvent {

    /** 内容 ID。 */
    private Long postId;

    /** 操作者用户 ID（通常为作者本人）。 */
    private Long operatorId;

    /** 事件时间戳（毫秒）。 */
    private Long tsMs;
}

