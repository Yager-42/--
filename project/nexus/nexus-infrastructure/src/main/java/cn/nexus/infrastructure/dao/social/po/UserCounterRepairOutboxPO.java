package cn.nexus.infrastructure.dao.social.po;

import java.util.Date;
import lombok.Data;

@Data
public class UserCounterRepairOutboxPO {
    private Long id;
    private Long sourceUserId;
    private Long targetUserId;
    private String operation;
    private String reason;
    private String correlationId;
    private String status;
    private Integer retryCount;
    private Date nextRetryTime;
    private Date createTime;
    private Date updateTime;
}
