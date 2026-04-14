package cn.nexus.infrastructure.adapter.counter.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.domain.social.model.valobj.ReactionTargetTypeEnumVO;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
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
        when(valueOperations.get("count:comment:{8}"))
                .thenReturn(CountRedisCodec.toRedisValue(CountRedisCodec.encodeSlots(new long[]{5L, 9L}, 2)));

        CountRedisOperations operations = new CountRedisOperations(redisTemplate);

        assertEquals(Map.of("like", 5L, "reply", 9L),
                operations.readObjectSnapshot("count:comment:{8}", CountRedisSchema.forObject(ReactionTargetTypeEnumVO.COMMENT)));
    }

    @Test
    void snapshotWriteClampsNegativeValuesBeforePersisting() {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        CountRedisOperations operations = new CountRedisOperations(redisTemplate);
        operations.writeUserSnapshot("count:user:{7}", Map.of("following", 6L, "follower", -4L), CountRedisSchema.user());

        verify(valueOperations).set(eq("count:user:{7}"),
                eq(CountRedisCodec.toRedisValue(CountRedisCodec.encodeSlots(new long[]{6L, 0L, 0L, 0L, 0L}, 5))));
    }

    @Test
    void aggregationHelpersUseHashBucketsAndCheckpointKeys() {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        HashOperations<String, Object, Object> hashOperations = Mockito.mock(HashOperations.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(hashOperations.entries("count:agg:{post}:like")).thenReturn(Map.of("42", "3", "99", -2L));
        when(valueOperations.get("count:replay:checkpoint:object")).thenReturn("12");
        when(valueOperations.setIfAbsent(eq("count:rebuild-lock:object:{42}"), eq("1"), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(Boolean.TRUE);

        CountRedisOperations operations = new CountRedisOperations(redisTemplate);

        operations.addAggregationDelta("count:agg:{post}:like", "42", 3L);
        assertEquals(Map.of("42", 3L, "99", 0L), operations.readAggregationBucket("count:agg:{post}:like"));
        operations.writeReplayCheckpoint("count:replay:checkpoint:object", 12L);
        assertEquals(12L, operations.readReplayCheckpoint("count:replay:checkpoint:object"));
        assertTrue(operations.tryAcquireRebuildLock("count:rebuild-lock:object:{42}", 15));
        operations.releaseRebuildLock("count:rebuild-lock:object:{42}");

        verify(hashOperations).increment("count:agg:{post}:like", "42", 3L);
        verify(valueOperations).set("count:replay:checkpoint:object", "12");
        verify(redisTemplate).delete("count:rebuild-lock:object:{42}");
    }

    @Test
    void bitmapAndRateLimitHelpersFollowRedisTruthiness() {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.getBit("count:fact:post_like:{42}:0", 7)).thenReturn(Boolean.TRUE);
        when(valueOperations.setBit("count:fact:post_like:{42}:0", 7, true)).thenReturn(Boolean.FALSE);
        when(valueOperations.setIfAbsent(eq("count:rate-limit:rebuild:{42}"), eq("1"), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(Boolean.FALSE);

        CountRedisOperations operations = new CountRedisOperations(redisTemplate);

        assertTrue(operations.readBitmapFact("count:fact:post_like:{42}:0", 7));
        assertFalse(operations.writeBitmapFact("count:fact:post_like:{42}:0", 7, true));
        assertFalse(operations.tryAcquireRateLimit("count:rate-limit:rebuild:{42}", 30));
        verify(redisTemplate, never()).delete("unused");
    }
}
