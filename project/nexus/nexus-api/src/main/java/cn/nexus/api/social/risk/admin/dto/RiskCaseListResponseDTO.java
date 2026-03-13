package cn.nexus.api.social.risk.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 人审工单列表响应。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskCaseListResponseDTO {
    private List<RiskCaseDTO> cases;
}

