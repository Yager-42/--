package cn.nexus.domain.social.service.risk;

import cn.nexus.domain.social.model.valobj.RiskEventVO;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * when.type = regex
 */
public class RegexMatcher implements RiskWhenMatcher {

    @Override
    public boolean hit(Map<String, Object> when, RiskEventVO event, Map<String, Long> counters) {
        if (event == null) {
            return false;
        }
        return hitRegex(when, event.getContentText());
    }

    private boolean hitRegex(Map<String, Object> when, String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        Object p = when == null ? null : when.get("pattern");
        if (p == null) {
            return false;
        }
        Pattern pattern = Pattern.compile(String.valueOf(p), Pattern.CASE_INSENSITIVE);
        return pattern.matcher(text).find();
    }
}

