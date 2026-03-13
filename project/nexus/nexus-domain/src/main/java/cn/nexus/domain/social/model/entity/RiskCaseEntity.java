package cn.nexus.domain.social.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 风控人审工单实体：承接 REVIEW 结果与人工流转。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskCaseEntity {
    private Long caseId;
    private Long decisionId;
    private String status;
    private String queue;
    private Long assignee;
    private String result;
    private String evidenceJson;
    private Long createTime;
    private Long updateTime;
}

