package cn.nexus.api.social.risk.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 查询 Prompt 版本列表响应。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskPromptVersionListResponseDTO {
    /** TEXT 生效版本（可空） */
    private Long activeTextVersion;
    /** IMAGE 生效版本（可空） */
    private Long activeImageVersion;
    private List<RiskPromptVersionDTO> versions;
}

