package cn.nexus.domain.social.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 风控动作：决策需要落地执行的动作描述（用于业务/worker 执行）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskActionVO {
    /** ALLOW/BLOCK/REVIEW_CREATE/CHALLENGE/PUNISH/DEGRADE_VISIBILITY/RATE_LIMIT */
    private String type;
    /** 动作参数 JSON（例如 visibility=QUARANTINE、ttlSeconds 等） */
    private String paramsJson;
}

