package cn.nexus.api.social.risk.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 创建/更新 Prompt 版本响应。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskPromptVersionUpsertResponseDTO {
    private Long version;
    private String status;
}

