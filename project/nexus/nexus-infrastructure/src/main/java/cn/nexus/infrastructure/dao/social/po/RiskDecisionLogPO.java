package cn.nexus.infrastructure.dao.social.po;

import lombok.Data;

import java.util.Date;

/**
 * 风控决策审计日志 PO。
 */
@Data
public class RiskDecisionLogPO {
    private Long decisionId;
    private String eventId;
    private Long userId;
    private String actionType;
    private String scenario;
    private String result;
    private String reasonCode;
    private String requestHash;
    private String signalsJson;
    private String actionsJson;
    private String extJson;
    private String traceId;
    private Date createTime;
    private Date updateTime;
}

