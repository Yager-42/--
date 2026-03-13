package cn.nexus.infrastructure.dao.social.po;

import java.util.Date;
import lombok.Data;

/**
 * 内容域事件 Outbox PO。
 */
@Data
public class ContentEventOutboxPO {
    /** 事件唯一指纹（幂等键）。 */
    private String eventId;
    private String eventType;
    private String payloadJson;
    private String status;
    private Integer retryCount;
    private Date nextRetryTime;
    private Date createTime;
    private Date updateTime;
}

