package cn.nexus.infrastructure.adapter.counter.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.domain.counter.model.valobj.ObjectCounterType;
import cn.nexus.domain.social.model.valobj.ReactionTargetTypeEnumVO;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class CountRedisOperationsTest {

    @Test
    void snapshotReadReturnsDecodedSlots() {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.execute(any(RedisCallback.class)))
                .thenReturn(CountRedisCodec.toRedisValue(CountRedisCodec.encodeSlots(new long[]{0L, 5L, 0L, 0L, 0L}, 5)));

        CountRedisOperations operations = new CountRedisOperations(redisTemplate);

        assertEquals(Map.of("read", 0L, "like", 5L, "fav", 0L, "comment", 0L, "repost", 0L),
                operations.readObjectSnapshot("cnt:v1:post:8", CountRedisSchema.forObject(ReactionTargetTypeEnumVO.POST)));
    }

    @Test
    void snapshotWriteClampsNegativeValuesBeforePersisting() {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        CountRedisOperations operations = new CountRedisOperations(redisTemplate);
        operations.writeUserSnapshot("ucnt:7", Map.of("followings", 6L, "followers", -4L), CountRedisSchema.user());

        verify(redisTemplate).execute(any(RedisCallback.class));
    }

    @Test
    void aggregationHelpersUseOnlyObjectHashBuckets() {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        HashOperations<String, Object, Object> hashOperations = Mockito.mock(HashOperations.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(hashOperations.entries("agg:v1:post:42")).thenReturn(Map.of("1", "3", "4", -2L));
        when(valueOperations.setIfAbsent(eq("count:rebuild-lock:object:{post:42}"), eq("1"), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(Boolean.TRUE);

        CountRedisOperations operations = new CountRedisOperations(redisTemplate);

        operations.addAggregationDelta("agg:v1:post:42", "1", 3L);
        assertEquals(Map.of("1", 3L, "4", 0L), operations.readAggregationBucket("agg:v1:post:42"));
        assertTrue(operations.tryAcquireRebuildLock("count:rebuild-lock:object:{post:42}", 15));
        operations.releaseRebuildLock("count:rebuild-lock:object:{post:42}");

        verify(hashOperations).increment("agg:v1:post:42", "1", 3L);
        assertTrue(java.util.Arrays.stream(CountRedisOperations.class.getDeclaredMethods())
                .noneMatch(method -> method.getName().contains("ReplayCheckpoint")));
        verify(redisTemplate).delete("count:rebuild-lock:object:{post:42}");
    }

    @Test
    void bitmapAndRateLimitHelpersFollowRedisTruthiness() {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        String likeKey = CountRedisKeys.bitmapShard(ObjectCounterType.LIKE, ReactionTargetTypeEnumVO.POST, 42L, 0);
        String favKey = CountRedisKeys.bitmapShard(ObjectCounterType.FAV, ReactionTargetTypeEnumVO.POST, 42L, 0);
        when(valueOperations.getBit(likeKey, 7)).thenReturn(Boolean.TRUE);
        when(valueOperations.setBit(favKey, 7, true)).thenReturn(Boolean.FALSE);
        when(valueOperations.setIfAbsent(eq("count:rate-limit:object:{post:42}"), eq("1"), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(Boolean.FALSE);

        CountRedisOperations operations = new CountRedisOperations(redisTemplate);

        assertEquals("bm:like:post:42:0", likeKey);
        assertEquals("bm:fav:post:42:0", favKey);
        assertTrue(operations.readBitmapFact(likeKey, 7));
        assertFalse(operations.writeBitmapFact(favKey, 7, true));
        assertFalse(operations.tryAcquireRateLimit("count:rate-limit:object:{post:42}", 30));
        verify(redisTemplate, never()).delete("unused");
    }
}
