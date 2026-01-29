package cn.nexus.api.social.risk.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 创建/更新规则版本请求：保存 rules_json 并生成/更新版本号。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskRuleVersionUpsertRequestDTO {
    /** 可选：指定 version 表示更新该 DRAFT 版本；为空表示创建新版本 */
    private Long version;
    /** 规则配置 JSON（必填） */
    private String rulesJson;
}

