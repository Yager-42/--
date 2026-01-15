package cn.nexus.types.event.interaction;

import cn.nexus.types.event.BaseEvent;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 点赞落库 flush 任务事件（延迟队列消息体）。
 *
 * <p>用于触发“窗口到期后批量落库”，避免在 HTTP 请求链路里同步写 MySQL。</p>
 *
 * @author codex
 * @since 2026-01-15
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class LikeFlushTaskEvent extends BaseEvent {

    /**
     * 目标类型：POST/COMMENT。
     */
    private String targetType;

    /**
     * 目标 ID。
     */
    private Long targetId;
}

