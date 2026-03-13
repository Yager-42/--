package cn.nexus.types.event;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 内容更新事件：用于搜索索引等旁路能力做增量更新。
 *
 * <p>注意：时间字段统一使用毫秒时间戳（Long），禁止 Date。</p>
 *
 * @author codex
 * @since 2026-02-02
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class PostUpdatedEvent extends BaseEvent {

    /** 内容 ID。 */
    private Long postId;

    /** 操作者用户 ID（通常为作者本人）。 */
    private Long operatorId;

    /** 事件时间戳（毫秒）。 */
    private Long tsMs;
}

