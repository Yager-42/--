package cn.nexus.api.social.risk.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 后台处理申诉请求。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskAppealDecisionRequestDTO {
    /** ACCEPT/REJECT */
    private String result;
}

