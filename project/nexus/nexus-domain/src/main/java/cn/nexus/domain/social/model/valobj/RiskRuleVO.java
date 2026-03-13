package cn.nexus.domain.social.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 风控规则：可配置、可版本化、可回滚。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskRuleVO {
    private String ruleId;
    private String scenario;
    private Integer priority;
    private Boolean enabled;
    /** 影子生效：命中只记录，不改变最终决策 */
    private Boolean shadow;

    /** 条件定义：{type: ..., ...} */
    private Map<String, Object> when;
    /** 动作定义：{type: ..., ...} */
    private Map<String, Object> then;

    private String reasonCode;
    private List<String> tags;
}

