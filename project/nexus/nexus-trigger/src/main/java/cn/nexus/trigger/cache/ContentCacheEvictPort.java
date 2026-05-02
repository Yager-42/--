package cn.nexus.trigger.cache;

import cn.nexus.domain.social.adapter.port.IContentCacheEvictPort;
import cn.nexus.infrastructure.adapter.social.repository.ContentRepository;
import cn.nexus.infrastructure.adapter.social.repository.FeedCardRepository;
import cn.nexus.infrastructure.mq.reliable.annotation.ReliableMqPublish;
import cn.nexus.trigger.http.social.support.ContentDetailQueryService;
import cn.nexus.trigger.mq.config.ContentCacheEvictConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 内容缓存失效端口实现：负责内容变更后的本地缓存、Redis 缓存失效，以及广播通知其它节点。
 *
 * <p>这里采用“双删”策略降低并发下的“旧值回填”概率：先立即删一次，再延迟删一次。</p>
 *
 * @author {$authorName}
 * @since 2026-03-03
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContentCacheEvictPort implements IContentCacheEvictPort {

    private static final String POST_REDIS_KEY_PREFIX = "interact:content:post:";
    private static final String FEED_CARD_REDIS_KEY_PREFIX = "feed:card:";

    private static final long DELAY_MS = 1000L;

    private static final ScheduledExecutorService SCHEDULER = Executors.newScheduledThreadPool(4, r -> {
        Thread t = new Thread(r, "content-cache-evict");
        t.setDaemon(true);
        return t;
    });

    private final ObjectProvider<ContentCacheEvictPort> selfProvider;
    private final StringRedisTemplate stringRedisTemplate;
    private final ContentRepository contentRepository;
    private final ContentDetailQueryService contentDetailQueryService;
    private final FeedCardRepository feedCardRepository;

    /**
     * 失效指定帖子相关缓存。
     *
     * <p>这一步不做任何业务计算，只做“让旧展示尽快消失”的工作。</p>
     *
     * @param postId 帖子 ID {@link Long}
     */
    @Override
    public void evictPost(Long postId) {
        if (postId == null) {
            return;
        }

        // 1. 先清本地缓存：避免当前节点继续复用旧快照。
        deleteLocal(postId);

        // 2. Redis 双删：降低“并发回源写回旧值”的概率。
        // 场景：A 删缓存 -> B 并发 miss 回源 -> B 写回 -> A 再删一次，把可能写回的旧值清掉。
        deleteRedis(postId);
        scheduleDelayDelete(postId);

        // 3. 广播本地失效：让其它节点也尽快把本地缓存清掉，避免“不同节点看到不同内容”。
        ContentCacheEvictEvent event = new ContentCacheEvictEvent(postId);
        selfProvider.getObject().publishEvict(event);
    }

    @ReliableMqPublish(exchange = ContentCacheEvictConfig.EXCHANGE,
            routingKey = "",
            eventId = "#event.eventId",
            payload = "#event")
    public void publishEvict(ContentCacheEvictEvent event) {
    }

    private void deleteRedis(Long postId) {
        try {
            stringRedisTemplate.delete(POST_REDIS_KEY_PREFIX + postId);
        } catch (Exception ignored) {
        }
        try {
            feedCardRepository.evictRedis(postId);
        } catch (Exception ignored) {
        }
        try {
            stringRedisTemplate.delete(FEED_CARD_REDIS_KEY_PREFIX + postId);
        } catch (Exception ignored) {
        }
    }

    private void scheduleDelayDelete(Long postId) {
        try {
            SCHEDULER.schedule(() -> {
                deleteRedis(postId);
                deleteLocal(postId);
            }, DELAY_MS, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
        }
    }

    private void deleteLocal(Long postId) {
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
    }
}
