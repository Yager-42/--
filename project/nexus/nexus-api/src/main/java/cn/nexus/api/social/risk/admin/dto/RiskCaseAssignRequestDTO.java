package cn.nexus.api.social.risk.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 工单分配请求。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskCaseAssignRequestDTO {
    private Long assignee;
    /** 期望状态（并发保护）；默认 OPEN */
    private String expectedStatus;
}

