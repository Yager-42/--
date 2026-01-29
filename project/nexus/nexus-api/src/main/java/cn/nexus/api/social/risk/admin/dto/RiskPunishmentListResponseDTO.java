package cn.nexus.api.social.risk.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 处罚列表响应。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskPunishmentListResponseDTO {
    private List<RiskPunishmentDTO> punishments;
}

