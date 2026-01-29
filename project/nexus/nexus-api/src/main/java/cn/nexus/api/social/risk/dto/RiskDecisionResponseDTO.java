package cn.nexus.api.social.risk.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 统一风控决策响应。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskDecisionResponseDTO {
    private Long decisionId;
    private String result;
    private String reasonCode;
    private List<RiskActionDTO> actions;
    private List<RiskSignalDTO> signals;
}

