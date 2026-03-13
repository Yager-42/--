package cn.nexus.api.social.risk.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 撤销处罚请求。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskPunishmentRevokeRequestDTO {
    private Long punishId;
}

