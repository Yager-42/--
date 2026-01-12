package cn.nexus.types.event;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 内容发布事件：用于触发 Feed 写扩散（fanout）。
 *
 * @author codex
 * @since 2026-01-12
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class PostPublishedEvent extends BaseEvent {

    /**
     * 内容 ID。
     */
    private Long postId;

    /**
     * 发布者（作者）用户 ID。
     */
    private Long authorId;

    /**
     * 发布毫秒时间戳（写扩散排序依据）。
     */
    private Long publishTimeMs;
}

