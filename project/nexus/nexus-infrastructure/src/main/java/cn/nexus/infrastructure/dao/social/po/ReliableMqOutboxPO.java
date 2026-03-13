package cn.nexus.infrastructure.dao.social.po;

import java.util.Date;
import lombok.Data;

/**
 * 通用 RabbitMQ Outbox 记录。
 */
@Data
public class ReliableMqOutboxPO {
    private Long id;
    private String eventId;
    private String exchangeName;
    private String routingKey;
    private String payloadType;
    private String payloadJson;
    private String headersJson;
    private String status;
    private Integer retryCount;
    private Date nextRetryAt;
    private String lastError;
    private Date createTime;
    private Date updateTime;
}
