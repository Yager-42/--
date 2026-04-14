package cn.nexus.domain.social.model.valobj;

import java.util.Date;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class UserCounterRepairOutboxVO {
    Long id;
    Long sourceUserId;
    Long targetUserId;
    String operation;
    String reason;
    String correlationId;
    String status;
    Integer retryCount;
    Date nextRetryTime;
    Date createTime;
    Date updateTime;
}
