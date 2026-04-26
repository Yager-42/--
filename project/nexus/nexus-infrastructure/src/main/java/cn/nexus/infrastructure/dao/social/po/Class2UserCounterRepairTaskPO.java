package cn.nexus.infrastructure.dao.social.po;

import java.util.Date;
import lombok.Data;

@Data
public class Class2UserCounterRepairTaskPO {
    private Long taskId;
    private String repairType;
    private Long userId;
    private String dedupeKey;
    private String status;
    private Integer retryCount;
    private String claimOwner;
    private Date claimedAt;
    private Date leaseUntil;
    private Date nextRetryTime;
    private String reason;
    private String lastError;
    private Date createTime;
    private Date updateTime;
}

