package cn.nexus.api.social.risk.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 人审工单 DTO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskCaseDTO {
    private Long caseId;
    private Long decisionId;
    /** OPEN/ASSIGNED/DONE */
    private String status;
    private String queue;
    private Long assignee;
    /** PASS/BLOCK（结论） */
    private String result;
    private String evidenceJson;
    private Long createTime;
    private Long updateTime;
}

