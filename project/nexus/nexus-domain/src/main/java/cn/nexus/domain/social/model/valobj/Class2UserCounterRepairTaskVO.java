package cn.nexus.domain.social.model.valobj;

import java.util.Date;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class Class2UserCounterRepairTaskVO {
    Long taskId;
    String repairType;
    Long userId;
    String dedupeKey;
    String status;
    Integer retryCount;
    String claimOwner;
    Date claimedAt;
    Date leaseUntil;
    Date nextRetryTime;
    String reason;
    String lastError;
    Date createTime;
    Date updateTime;
}

