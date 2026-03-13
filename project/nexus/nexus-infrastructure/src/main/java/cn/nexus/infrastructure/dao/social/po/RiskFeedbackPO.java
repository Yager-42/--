package cn.nexus.infrastructure.dao.social.po;

import lombok.Data;

import java.util.Date;

/**
 * 风控反馈/申诉 PO。
 */
@Data
public class RiskFeedbackPO {
    private Long feedbackId;
    private Long userId;
    private String type;
    private String status;
    private Long decisionId;
    private Long punishId;
    private String content;
    private String result;
    private Long operatorId;
    private Date createTime;
    private Date updateTime;
}

