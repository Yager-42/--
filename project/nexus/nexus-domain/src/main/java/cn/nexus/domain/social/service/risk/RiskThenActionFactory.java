package cn.nexus.domain.social.service.risk;

import cn.nexus.domain.social.model.valobj.RiskDecisionVO;
import cn.nexus.domain.social.model.valobj.RiskRuleVO;
import cn.nexus.domain.social.model.valobj.RiskSignalVO;

import java.util.List;

/**
 * 风控规则 then 工厂：把不同 then.type 的 decision 构建拆出去，避免 RiskService 里堆 if/else。
 */
@FunctionalInterface
public interface RiskThenActionFactory {

    /**
     * @param rule    当前命中的规则
     * @param signals 已累计的 signals（会被透传到决策结果）
     * @return 风控决策
     */
    RiskDecisionVO build(RiskRuleVO rule, List<RiskSignalVO> signals);
}

