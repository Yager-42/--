package cn.nexus.domain.social.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 风控决策：对某次 RiskEvent 的最终结论。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskDecisionVO {
    private Long decisionId;
    /** PASS/REVIEW/BLOCK/CHALLENGE/SHADOWBAN/LIMIT */
    private String result;
    private String reasonCode;
    private List<RiskActionVO> actions;
    private List<RiskSignalVO> signals;
    private Integer ttlSeconds;
}

