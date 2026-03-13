package cn.nexus.infrastructure.dao.social.po;

import java.util.Date;
import lombok.Data;

/**
 * RabbitMQ 消费幂等记录。
 */
@Data
public class ReliableMqConsumerRecordPO {
    private Long id;
    private String eventId;
    private String consumerName;
    private String payloadJson;
    private String status;
    private String lastError;
    private Date createTime;
    private Date updateTime;
}
