package cn.nexus.api.user.dto;

import cn.nexus.api.social.risk.dto.UserRiskStatusResponseDTO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 个人主页聚合响应：Profile + 关系统计 + 风控能力。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserProfilePageResponseDTO {
    private UserProfileResponseDTO profile;
    private UserRelationStatsDTO relation;
    private UserRiskStatusResponseDTO risk;
}

