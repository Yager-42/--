package cn.nexus.infrastructure.dao.social.po;

import lombok.Data;

import java.util.Date;

/**
 * 风控人审工单 PO。
 */
@Data
public class RiskCasePO {
    private Long caseId;
    private Long decisionId;
    private String status;
    private String queue;
    private Long assignee;
    private String result;
    private String evidenceJson;
    private Date createTime;
    private Date updateTime;
}

