package cn.nexus.api.social.risk.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户申诉响应。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskAppealResponseDTO {
    private Long appealId;
    private String status;
}

