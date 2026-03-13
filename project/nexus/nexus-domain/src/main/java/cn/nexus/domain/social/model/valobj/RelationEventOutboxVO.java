package cn.nexus.domain.social.model.valobj;

import java.util.Date;
import lombok.Builder;
import lombok.Value;

/**
 * 关系事件 Outbox 视图。
 */
@Value
@Builder
public class RelationEventOutboxVO {
    Long eventId;
    String eventType;
    String payload;
    String status;
    Integer retryCount;
    Date nextRetryTime;
    Date createTime;
    Date updateTime;
}
