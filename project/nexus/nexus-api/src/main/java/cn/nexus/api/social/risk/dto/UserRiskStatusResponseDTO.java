package cn.nexus.api.social.risk.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 用户风控状态。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserRiskStatusResponseDTO {
    private String status;
    private List<String> capabilities;
}
