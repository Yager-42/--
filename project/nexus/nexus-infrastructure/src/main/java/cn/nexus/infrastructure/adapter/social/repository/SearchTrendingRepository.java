package cn.nexus.infrastructure.adapter.social.repository;

import cn.nexus.domain.social.adapter.repository.ISearchTrendingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 热搜/联想仓储 Redis 实现（ZSET + ZUNIONSTORE）。
 *
 * <p>日热搜 Key：search:trend:{category}:{yyyyMMdd}</p>
 * <p>聚合热搜 Key：search:trend:{category}:7d:{yyyyMMdd}</p>
 */
@Repository
@RequiredArgsConstructor
public class SearchTrendingRepository implements ISearchTrendingRepository {

    private static final ZoneId ZONE_SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.BASIC_ISO_DATE;

    private static final String KEY_PREFIX = "search:trend:";

    private final StringRedisTemplate stringRedisTemplate;

    @Value("${search.trending.windowDays:7}")
    private int windowDays;

    @Value("${search.trending.dailyTtlDays:8}")
    private int dailyTtlDays;

    @Value("${search.trending.unionTtlSeconds:120}")
    private int unionTtlSeconds;

    @Override
    public void incr(String category, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return;
        }
        String cat = normalizeCategory(category);
        String day = today();
        String key = dailyKey(cat, day);

        Boolean existed = stringRedisTemplate.hasKey(key);
        stringRedisTemplate.opsForZSet().incrementScore(key, keyword, 1D);

        if (existed == null || !existed) {
            stringRedisTemplate.expire(key, dailyTtl());
        }
    }

    @Override
    public List<String> top(String category, int limit) {
        int normalizedLimit = Math.max(1, limit);
        String cat = normalizeCategory(category);
        String day = today();
        String unionKey = ensureUnionKey(cat, day);
        Set<String> set = stringRedisTemplate.opsForZSet().reverseRange(unionKey, 0, normalizedLimit - 1);
        if (set == null || set.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(set);
    }

    @Override
    public List<String> topAndFilterPrefix(String category, String prefix, int scanTopK, int limit) {
        int scan = Math.max(1, scanTopK);
        int normalizedLimit = Math.max(1, limit);
        String cat = normalizeCategory(category);
        String day = today();
        String unionKey = ensureUnionKey(cat, day);

        Set<String> set = stringRedisTemplate.opsForZSet().reverseRange(unionKey, 0, scan - 1);
        if (set == null || set.isEmpty()) {
            return List.of();
        }

        String p = prefix == null ? "" : prefix.trim().toLowerCase(Locale.ROOT);
        if (p.isEmpty()) {
            List<String> all = new ArrayList<>(set);
            return all.size() <= normalizedLimit ? all : all.subList(0, normalizedLimit);
        }

        List<String> res = new ArrayList<>(normalizedLimit);
        for (String kw : set) {
            if (kw == null) {
                continue;
            }
            String k = kw.toLowerCase(Locale.ROOT);
            if (!k.startsWith(p)) {
                continue;
            }
            res.add(kw);
            if (res.size() >= normalizedLimit) {
                break;
            }
        }
        return res;
    }

    private String ensureUnionKey(String category, String today) {
        String unionKey = unionKey(category, today);
        // 聚合结果作为短 TTL 缓存：有效期内直接复用，避免每次请求都做 union。
        Long ttl = stringRedisTemplate.getExpire(unionKey);
        if (ttl != null && ttl > 0) {
            return unionKey;
        }

        int days = Math.max(1, windowDays);
        List<String> keys = new ArrayList<>(days);
        LocalDate base = LocalDate.parse(today, YYYYMMDD);
        for (int i = 0; i < days; i++) {
            String day = base.minusDays(i).format(YYYYMMDD);
            keys.add(dailyKey(category, day));
        }

        if (!keys.isEmpty()) {
            String first = keys.get(0);
            List<String> rest = keys.size() <= 1 ? List.of() : keys.subList(1, keys.size());
            stringRedisTemplate.opsForZSet().unionAndStore(first, rest, unionKey);
        }
        stringRedisTemplate.expire(unionKey, unionTtl());
        return unionKey;
    }

    private String dailyKey(String category, String yyyyMMdd) {
        return KEY_PREFIX + category + ":" + yyyyMMdd;
    }

    private String unionKey(String category, String yyyyMMdd) {
        return KEY_PREFIX + category + ":7d:" + yyyyMMdd;
    }

    private String today() {
        return LocalDate.now(ZONE_SHANGHAI).format(YYYYMMDD);
    }

    private String normalizeCategory(String category) {
        // 写死口径：POST/ALL/其它都按 POST，避免客户端传参导致热搜空。
        if (category == null || category.isBlank()) {
            return "POST";
        }
        String c = category.trim().toUpperCase(Locale.ROOT);
        if ("ALL".equals(c) || "POST".equals(c)) {
            return "POST";
        }
        return "POST";
    }

    private Duration dailyTtl() {
        int days = Math.max(1, dailyTtlDays);
        return Duration.ofDays(days);
    }

    private Duration unionTtl() {
        int seconds = Math.max(1, unionTtlSeconds);
        return Duration.ofSeconds(seconds);
    }
}
