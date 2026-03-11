package cn.nexus.trigger.mq.event;

import cn.nexus.types.event.BaseEvent;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 定时发布触发事件：
 * 1. 用显式 eventId 代替裸 Long，保证可重试消息都有稳定身份。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ContentScheduleTriggerEvent extends BaseEvent {

    private Long taskId;
}
