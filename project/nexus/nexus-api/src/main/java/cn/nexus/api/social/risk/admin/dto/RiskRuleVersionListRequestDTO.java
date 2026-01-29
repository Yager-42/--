package cn.nexus.api.social.risk.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 规则版本列表查询请求（GET 参数）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskRuleVersionListRequestDTO {
    /** 是否包含 rulesJson（默认 false，避免大字段拖慢列表） */
    private Boolean includeRulesJson;
}

