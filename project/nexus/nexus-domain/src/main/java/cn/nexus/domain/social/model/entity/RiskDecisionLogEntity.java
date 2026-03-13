package cn.nexus.domain.social.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 风控决策审计实体：每次决策必落库，用于追溯与对账。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskDecisionLogEntity {
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
    private Long createTime;
    private Long updateTime;
}

