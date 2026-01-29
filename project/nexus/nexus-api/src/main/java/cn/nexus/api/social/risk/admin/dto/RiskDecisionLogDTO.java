package cn.nexus.api.social.risk.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 决策审计日志 DTO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskDecisionLogDTO {
    private Long decisionId;
    private String eventId;
    private Long userId;
    private String actionType;
    private String scenario;
    private String result;
    private String reasonCode;
    private String signalsJson;
    private String actionsJson;
    private String extJson;
    private String traceId;
    private Long createTime;
    private Long updateTime;
}

