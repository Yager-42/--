package cn.nexus.trigger.cache;

import cn.nexus.domain.social.adapter.port.IContentCacheEvictPort;
import cn.nexus.infrastructure.adapter.social.repository.ContentRepository;
import cn.nexus.infrastructure.adapter.social.repository.FeedCardRepository;
import cn.nexus.infrastructure.adapter.social.repository.FeedCardStatRepository;
import cn.nexus.trigger.http.social.support.ContentDetailQueryService;
import cn.nexus.trigger.mq.config.ContentCacheEvictConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class ContentCacheEvictPort implements IContentCacheEvictPort {

    private static final String POST_REDIS_KEY_PREFIX = "interact:content:post:";
    private static final String DETAIL_REDIS_KEY_PREFIX = "interact:content:detail:";
    private static final String FEED_CARD_REDIS_KEY_PREFIX = "feed:card:";
    private static final String FEED_CARD_STAT_REDIS_KEY_PREFIX = "feed:card:stat:";

    private static final long DELAY_MS = 1000L;

    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "content-cache-evict");
        t.setDaemon(true);
        return t;
    });

    private final RabbitTemplate rabbitTemplate;
    private final StringRedisTemplate stringRedisTemplate;
    private final ContentRepository contentRepository;
    private final ContentDetailQueryService contentDetailQueryService;
    private final FeedCardRepository feedCardRepository;
    private final FeedCardStatRepository feedCardStatRepository;

    @Override
    public void evictPost(Long postId) {
        if (postId == null) {
            return;
        }

        // local first
        try {
            contentRepository.evictLocalPostCache(postId);
        } catch (Exception ignored) {
        }
        try {
            contentDetailQueryService.evictLocal(postId);
        } catch (Exception ignored) {
        }
        try {
            feedCardRepository.evictLocal(postId);
        } catch (Exception ignored) {
        }
        try {
            feedCardStatRepository.evictLocal(postId);
        } catch (Exception ignored) {
        }

        // redis double delete
        deleteRedis(postId);
        scheduleDelayDelete(postId);

        // broadcast local evict
        try {
            rabbitTemplate.convertAndSend(ContentCacheEvictConfig.EXCHANGE, "", new ContentCacheEvictEvent(postId));
        } catch (Exception e) {
            log.warn("publish cache evict event failed, postId={}", postId, e);
        }
    }

    private void deleteRedis(Long postId) {
        try {
            stringRedisTemplate.delete(POST_REDIS_KEY_PREFIX + postId);
        } catch (Exception ignored) {
        }
        try {
            stringRedisTemplate.delete(DETAIL_REDIS_KEY_PREFIX + postId);
        } catch (Exception ignored) {
        }
        try {
            feedCardRepository.evictRedis(postId);
        } catch (Exception ignored) {
        }
        try {
            feedCardStatRepository.evictRedis(postId);
        } catch (Exception ignored) {
        }
        try {
            stringRedisTemplate.delete(FEED_CARD_REDIS_KEY_PREFIX + postId);
        } catch (Exception ignored) {
        }
        try {
            stringRedisTemplate.delete(FEED_CARD_STAT_REDIS_KEY_PREFIX + postId);
        } catch (Exception ignored) {
        }
    }

    private void scheduleDelayDelete(Long postId) {
        try {
            SCHEDULER.schedule(() -> deleteRedis(postId), DELAY_MS, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
        }
    }
}
