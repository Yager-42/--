package cn.nexus.infrastructure.adapter.social.port;

import cn.nexus.domain.social.adapter.port.IPostAuthorPort;
import cn.nexus.infrastructure.dao.social.IContentPostDao;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Component
@RequiredArgsConstructor
public class PostAuthorPort implements IPostAuthorPort {

    private static final String KEY_PREFIX = "interact:content:author:";
    private static final Duration TTL = Duration.ofDays(1);

    private final StringRedisTemplate stringRedisTemplate;
    private final IContentPostDao contentPostDao;

    @Override
    public Long getPostAuthorId(Long postId) {
        if (postId == null) {
            return null;
        }
        String key = KEY_PREFIX + postId;
        try {
            String cached = stringRedisTemplate.opsForValue().get(key);
            if (cached != null && !cached.isBlank()) {
                return Long.parseLong(cached.trim());
            }
        } catch (Exception ignored) {
        }

        Long userId = null;
        try {
            userId = contentPostDao.selectUserId(postId);
        } catch (Exception e) {
            log.warn("select post author failed, postId={}", postId, e);
        }
        if (userId == null) {
            return null;
        }

        try {
            Duration ttl = jitter(TTL);
            stringRedisTemplate.opsForValue().set(key, String.valueOf(userId), ttl);
        } catch (Exception ignored) {
        }
        return userId;
    }

    private Duration jitter(Duration base) {
        long sec = Math.max(60L, base.getSeconds());
        long extra = ThreadLocalRandom.current().nextLong(0, Math.min(3600L, sec));
        return Duration.ofSeconds(sec + extra);
    }
}
