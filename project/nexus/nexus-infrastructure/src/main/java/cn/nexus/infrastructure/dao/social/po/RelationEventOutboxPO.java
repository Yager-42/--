package cn.nexus.infrastructure.dao.social.po;

import java.util.Date;
import lombok.Data;

/**
 * 关系事件 Outbox PO。
 */
@Data
public class RelationEventOutboxPO {
    private Long eventId;
    private String eventType;
    private String payload;
    private String status;
    private Integer retryCount;
    private Date nextRetryTime;
    private Date createTime;
    private Date updateTime;
}
