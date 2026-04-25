package cn.nexus.types.event.relation;

import cn.nexus.types.event.BaseEvent;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 关系计数投影事件：由关系写侧 outbox 发送到 RabbitMQ，用于 follower/cache/ucnt 投影。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class RelationCounterProjectEvent extends BaseEvent {

    private Long relationEventId;
    private String eventType;
    private Long sourceId;
    private Long targetId;
    private String status;
}
