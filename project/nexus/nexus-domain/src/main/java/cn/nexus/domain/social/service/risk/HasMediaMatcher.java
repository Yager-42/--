package cn.nexus.domain.social.service.risk;

import cn.nexus.domain.social.model.valobj.RiskEventVO;

import java.util.Map;

/**
 * when.type = has_media
 */
public class HasMediaMatcher implements RiskWhenMatcher {

    @Override
    public boolean hit(Map<String, Object> when, RiskEventVO event, Map<String, Long> counters) {
        if (event == null) {
            return false;
        }
        return event.getMediaUrls() != null && !event.getMediaUrls().isEmpty();
    }
}

