package cn.nexus.infrastructure.dao.user.po;

import java.util.Date;
import lombok.Data;

/**
 * 用户域事件 Outbox PO。
 */
@Data
public class UserEventOutboxPO {
    private Long id;
    private String eventType;
    private String fingerprint;
    private String payload;
    private String status;
    private Integer retryCount;
    private Date createTime;
    private Date updateTime;
}

