package cn.nexus.api.social.risk.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 发布 Prompt 版本请求（预留扩展字段）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskPromptVersionPublishRequestDTO {
    /** 预留字段：目前无需传参 */
    private String reserved;
}

