package cn.nexus.types.event;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;
import lombok.Data;

/**
 * 事件基类，统一事件标识与时间戳。
 */
@Data
public class BaseEvent implements Serializable {
    /** 事件唯一标识 */
    private String eventId = UUID.randomUUID().toString();
    /** 事件产生时间 */
    private Instant occurredAt = Instant.now();
}
