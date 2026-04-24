package cn.nexus.infrastructure.adapter.social.port;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.infrastructure.adapter.social.repository.FeedCardRepository;
import cn.nexus.infrastructure.adapter.social.repository.FeedCardStatRepository;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

class FeedCounterSideEffectPortTest {

    @Test
    void applyPostLikeDelta_shouldEvictCardCachesAndPruneStaleIndexMembers() {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        SetOperations<String, String> setOperations = Mockito.mock(SetOperations.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        FeedCardRepository feedCardRepository = Mockito.mock(FeedCardRepository.class);
        FeedCardStatRepository feedCardStatRepository = Mockito.mock(FeedCardStatRepository.class);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        String indexKey = "feed:public:index:101:483931";
        String stalePageKey = "feed:public:page:stale";
        String alivePageKey = "feed:public:page:alive";
        when(redisTemplate.hasKey(indexKey)).thenReturn(true);
        when(redisTemplate.getExpire(indexKey, TimeUnit.SECONDS)).thenReturn(600L);
        when(setOperations.members(indexKey)).thenReturn(Set.of(stalePageKey, alivePageKey));
        when(redisTemplate.hasKey(stalePageKey)).thenReturn(false);
        when(redisTemplate.hasKey(alivePageKey)).thenReturn(true);

        when(redisTemplate.execute(Mockito.<org.springframework.data.redis.core.RedisCallback<Set<String>>>any()))
                .thenReturn(Set.of(indexKey));

        FeedCounterSideEffectPort port = new FeedCounterSideEffectPort(
                redisTemplate,
                feedCardRepository,
                feedCardStatRepository);

        port.applyPostLikeDelta(101L, 1L);

        verify(setOperations).remove(indexKey, stalePageKey);
        verify(redisTemplate).expire(indexKey, 600L, TimeUnit.SECONDS);
        verify(feedCardRepository).evictLocal(101L);
        verify(feedCardRepository).evictRedis(101L);
        verify(feedCardStatRepository).evictLocal(101L);
        verify(feedCardStatRepository).evictRedis(101L);
        verify(redisTemplate).delete("feed:card:101");
        verify(redisTemplate).delete("feed:card:stat:101");
    }

    @Test
    void applyPostLikeDelta_shouldIgnoreZeroDelta() {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        FeedCardRepository feedCardRepository = Mockito.mock(FeedCardRepository.class);
        FeedCardStatRepository feedCardStatRepository = Mockito.mock(FeedCardStatRepository.class);
        FeedCounterSideEffectPort port = new FeedCounterSideEffectPort(
                redisTemplate,
                feedCardRepository,
                feedCardStatRepository);

        port.applyPostLikeDelta(101L, 0L);

        verify(feedCardRepository, never()).evictLocal(Mockito.anyLong());
        verify(feedCardRepository, never()).evictRedis(Mockito.anyLong());
        verify(feedCardStatRepository, never()).evictLocal(Mockito.anyLong());
        verify(feedCardStatRepository, never()).evictRedis(Mockito.anyLong());
        verify(redisTemplate, never()).delete(Mockito.anyString());
    }
}
