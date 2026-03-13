package cn.nexus.domain.social.service.risk;

import cn.nexus.domain.social.model.valobj.RiskEventVO;

import java.util.Map;

/**
 * 风控规则 when 匹配器：把不同 when.type 的判断拆出去，避免 RiskService 里堆 if/else。
 */
@FunctionalInterface
public interface RiskWhenMatcher {

    /**
     * @param when     规则 when JSON（已解析成 Map）
     * @param event    风控事件
     * @param counters 同一次 decision 内的计数缓存（避免同一 key 被重复自增）
     * @return 是否命中
     */
    boolean hit(Map<String, Object> when, RiskEventVO event, Map<String, Long> counters);
}

