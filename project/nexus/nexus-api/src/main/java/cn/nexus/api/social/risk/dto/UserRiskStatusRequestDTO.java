package cn.nexus.api.social.risk.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户风控状态请求。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserRiskStatusRequestDTO {
    private Long userId;
}
