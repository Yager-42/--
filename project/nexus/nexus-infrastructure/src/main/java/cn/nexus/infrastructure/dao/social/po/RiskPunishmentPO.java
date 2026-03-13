package cn.nexus.infrastructure.dao.social.po;

import lombok.Data;

import java.util.Date;

/**
 * 风控处罚 PO。
 */
@Data
public class RiskPunishmentPO {
    private Long punishId;
    private Long userId;
    private String type;
    private String status;
    private Date startTime;
    private Date endTime;
    private String reasonCode;
    private Long decisionId;
    private Long operatorId;
    private Date createTime;
    private Date updateTime;
}

