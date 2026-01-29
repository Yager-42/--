package cn.nexus.api.social.risk.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 发布规则版本请求：可附带灰度/影子参数（写入 rules_json）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskRuleVersionPublishRequestDTO {
    /** 全局影子生效：命中只记录不拦截 */
    private Boolean shadow;
    /** 灰度比例（0-100） */
    private Integer canaryPercent;
    /** 灰度盐值 */
    private String canarySalt;
}

