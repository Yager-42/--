package cn.nexus.domain.social.service.risk;

import cn.nexus.domain.social.model.valobj.RiskEventVO;

import java.util.List;
import java.util.Map;

/**
 * when.type = text_contains_any
 */
public class TextContainsAnyMatcher implements RiskWhenMatcher {

    @Override
    public boolean hit(Map<String, Object> when, RiskEventVO event, Map<String, Long> counters) {
        if (event == null) {
            return false;
        }
        return hitTextContainsAny(when, event.getContentText());
    }

    private boolean hitTextContainsAny(Map<String, Object> when, String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        Object k = when == null ? null : when.get("keywords");
        if (!(k instanceof List<?> list) || list.isEmpty()) {
            return false;
        }
        boolean ci = when != null && Boolean.TRUE.equals(when.get("caseInsensitive"));
        String content = ci ? text.toLowerCase() : text;
        for (Object raw : list) {
            if (raw == null) {
                continue;
            }
            String kw = String.valueOf(raw);
            String needle = ci ? kw.toLowerCase() : kw;
            if (!needle.isBlank() && content.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}

