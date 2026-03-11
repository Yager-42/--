package cn.nexus.infrastructure.dao.social.po;

import java.util.Date;
import lombok.Data;

/**
 * RabbitMQ 失败消息重放记录。
 */
@Data
public class ReliableMqReplayRecordPO {
    private Long id;
    private String eventId;
    private String consumerName;
    private String originalQueue;
    private String originalExchange;
    private String originalRoutingKey;
    private String payloadType;
    private String payloadJson;
    private String status;
    private Integer attempt;
    private Date nextRetryAt;
    private String lastError;
    private Date createTime;
    private Date updateTime;
}
