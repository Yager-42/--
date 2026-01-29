package cn.nexus.domain.social.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 风控规则集合：用于从 rules_json 反序列化并执行。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskRuleSetVO {
    private Integer version;
    /** 全局影子生效：只记录不拦截 */
    private Boolean shadow;
    /**
     * 灰度比例（0-100）：<100 表示只对部分用户“硬生效”，其余用户只记录信号（等价 shadow）。
     *
     * <p>注意：这是最小可用的 canary 开关，不引入复杂的分流系统。</p>
     */
    private Integer canaryPercent;
    /**
     * 灰度盐值：用于让分流策略可控（避免 userId 分布不均导致偏差）。
     */
    private String canarySalt;
    private List<RiskRuleVO> rules;
}
