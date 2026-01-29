package cn.nexus.api.social.risk.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 查询 Prompt 版本列表请求。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskPromptVersionListRequestDTO {
    /** 可选：TEXT/IMAGE；为空表示不过滤 */
    private String contentType;
    /** 是否返回 promptText（默认 false） */
    private Boolean includePromptText;
}

