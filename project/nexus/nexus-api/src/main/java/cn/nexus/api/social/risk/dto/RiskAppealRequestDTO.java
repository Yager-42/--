package cn.nexus.api.social.risk.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户申诉请求：写入 risk_feedback(type=APPEAL)。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskAppealRequestDTO {
    /** 可选：关联决策ID */
    private Long decisionId;
    /** 可选：关联处罚ID */
    private Long punishId;
    /** 申诉理由（必填） */
    private String content;
}

