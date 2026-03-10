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
    private static final String NULL_VALUE = "NULL";
    private static final Duration TTL = Duration.ofDays(1);

    private final StringRedisTemplate stringRedisTemplate;
    private final IContentPostDao contentPostDao;

    @Override
    public Long getPostAuthorId(Long postId) {
        if (postId == null) {
            return null;
        }
        String key = KEY_PREFIX + postId;
        CacheLookup cacheLookup = readCache(key);
        if (cacheLookup.hit()) {
            return cacheLookup.userId();
        }

        LoadAuthorResult result = loadAuthor(postId);
        writeBack(key, result);
        return result.userId();
    }

    private CacheLookup readCache(String key) {
        try {
            String cached = stringRedisTemplate.opsForValue().get(key);
            if (cached == null || cached.isBlank()) {
                return CacheLookup.miss();
            }
            if (NULL_VALUE.equals(cached)) {
                return CacheLookup.hit(null);
            }
            return CacheLookup.hit(Long.parseLong(cached.trim()));
        } catch (NumberFormatException e) {
            try {
                stringRedisTemplate.delete(key);
            } catch (Exception ignored) {
                // ignore
            }
            return CacheLookup.miss();
        } catch (Exception ignored) {
            // ignore
            return CacheLookup.miss();
        }
    }

    private LoadAuthorResult loadAuthor(Long postId) {
        try {
            Long userId = contentPostDao.selectUserId(postId);
            if (userId == null) {
                return new LoadAuthorResult(LoadStatus.NOT_FOUND, null);
            }
            return new LoadAuthorResult(LoadStatus.FOUND, userId);
        } catch (Exception e) {
            log.warn("select post author failed, postId={}", postId, e);
            return new LoadAuthorResult(LoadStatus.FAILED, null);
        }
    }

    private void writeBack(String key, LoadAuthorResult result) {
        if (result == null || result.status() == LoadStatus.FAILED) {
            return;
        }
        try {
            if (result.status() == LoadStatus.NOT_FOUND) {
                stringRedisTemplate.opsForValue().set(key, NULL_VALUE, nullTtl());
                return;
            }
            stringRedisTemplate.opsForValue().set(key, String.valueOf(result.userId()), positiveTtl());
        } catch (Exception ignored) {
            // ignore
        }
    }

    private Duration positiveTtl() {
        return jitter(TTL, 3600L);
    }

    private Duration nullTtl() {
        return jitter(Duration.ofSeconds(30), 10L);
    }

    private Duration jitter(Duration base, long maxExtraSeconds) {
        long sec = Math.max(1L, base.getSeconds());
        long extra = ThreadLocalRandom.current().nextLong(maxExtraSeconds + 1);
        return Duration.ofSeconds(sec + extra);
    }

    private enum LoadStatus {
        FOUND,
        NOT_FOUND,
        FAILED
    }

    private record LoadAuthorResult(LoadStatus status, Long userId) {
    }

    private record CacheLookup(boolean hit, Long userId) {

        private static CacheLookup miss() {
            return new CacheLookup(false, null);
        }

        private static CacheLookup hit(Long userId) {
            return new CacheLookup(true, userId);
        }
    }
}
