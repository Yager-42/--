package cn.nexus.domain.social.service.risk;

import cn.nexus.domain.social.model.valobj.RiskEventVO;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.Map;

/**
 * when.type = rate_limit
 */
public class RateLimitMatcher implements RiskWhenMatcher {

    private final RedissonClient redissonClient;

    public RateLimitMatcher(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    @Override
    public boolean hit(Map<String, Object> when, RiskEventVO event, Map<String, Long> counters) {
        if (event == null || when == null) {
            return false;
        }
        Integer window = toIntObj(when.get("windowSeconds"));
        Integer threshold = toIntObj(when.get("threshold"));
        if (window == null || threshold == null || window <= 0) {
            return false;
        }
        long cnt = incrCounter(event.getUserId(), event.getActionType(), window, counters);
        return cnt > threshold;
    }

    private long incrCounter(Long userId, String actionType, int windowSeconds, Map<String, Long> counters) {
        if (userId == null || actionType == null || actionType.isBlank()) {
            return 0L;
        }
        String key = "risk:cnt:" + userId + ":" + actionType + ":" + windowSeconds;
        if (counters != null) {
            Long cached = counters.get(key);
            if (cached != null) {
                return cached;
            }
        }
        RAtomicLong counter = redissonClient.getAtomicLong(key);
        long v = counter.incrementAndGet();
        counter.expire(Duration.ofSeconds(windowSeconds));
        if (counters != null) {
            counters.put(key, v);
        }
        return v;
    }

    private Integer toIntObj(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Integer i) {
            return i;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (Exception e) {
            return null;
        }
    }
}

