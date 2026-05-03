package cn.nexus.infrastructure.adapter.social.repository;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import cn.nexus.infrastructure.config.FeedGlobalLatestProperties;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

class FeedGlobalLatestRepositoryTest {

    @Test
    void removeFromLatest_nullPostIdDoesNothing() {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        FeedGlobalLatestRepository repository = new FeedGlobalLatestRepository(redisTemplate, new FeedGlobalLatestProperties());

        repository.removeFromLatest(null);

        verify(redisTemplate, never()).opsForZSet();
    }

    @Test
    void removeFromLatest_removesOnlyGlobalLatestMember() {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ZSetOperations<String, String> zSetOperations = Mockito.mock(ZSetOperations.class);
        Mockito.when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        FeedGlobalLatestRepository repository = new FeedGlobalLatestRepository(redisTemplate, new FeedGlobalLatestProperties());

        repository.removeFromLatest(42L);

        verify(zSetOperations).remove("feed:global:latest", "42");
    }
}
