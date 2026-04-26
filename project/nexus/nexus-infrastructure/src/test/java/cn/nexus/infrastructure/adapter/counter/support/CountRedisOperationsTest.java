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

import cn.nexus.domain.social.model.valobj.ReactionTargetTypeEnumVO;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

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
                operations.readObjectSnapshot("cnt:v1:comment:8", CountRedisSchema.forObject(ReactionTargetTypeEnumVO.COMMENT)));
    }

    @Test
    void snapshotWriteClampsNegativeValuesBeforePersisting() {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        CountRedisOperations operations = new CountRedisOperations(redisTemplate);
        operations.writeUserSnapshot("ucnt:7", Map.of("following", 6L, "follower", -4L), CountRedisSchema.user());

        verify(redisTemplate).execute(any(RedisCallback.class));
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
        when(hashOperations.entries("agg:v1:post:42")).thenReturn(Map.of("1", "3", "4", -2L));
        when(valueOperations.get("count:replay:checkpoint:object")).thenReturn("12");
        when(valueOperations.setIfAbsent(eq("count:rebuild-lock:object:{42}"), eq("1"), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(Boolean.TRUE);

        CountRedisOperations operations = new CountRedisOperations(redisTemplate);

        operations.addAggregationDelta("agg:v1:post:42", "1", 3L);
        assertEquals(Map.of("1", 3L, "4", 0L), operations.readAggregationBucket("agg:v1:post:42"));
        operations.writeReplayCheckpoint("count:replay:checkpoint:object", 12L);
        assertEquals(12L, operations.readReplayCheckpoint("count:replay:checkpoint:object"));
        assertTrue(operations.tryAcquireRebuildLock("count:rebuild-lock:object:{42}", 15));
        operations.releaseRebuildLock("count:rebuild-lock:object:{42}");

        verify(hashOperations).increment("agg:v1:post:42", "1", 3L);
        verify(valueOperations).set("count:replay:checkpoint:object", "12");
        verify(redisTemplate).delete("count:rebuild-lock:object:{42}");
    }

    @Test
    void bitmapAndRateLimitHelpersFollowRedisTruthiness() {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.getBit("bm:like:post:42:0", 7)).thenReturn(Boolean.TRUE);
        when(valueOperations.setBit("bm:like:post:42:0", 7, true)).thenReturn(Boolean.FALSE);
        when(valueOperations.setIfAbsent(eq("count:rate-limit:rebuild:{42}"), eq("1"), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(Boolean.FALSE);

        CountRedisOperations operations = new CountRedisOperations(redisTemplate);

        assertTrue(operations.readBitmapFact("bm:like:post:42:0", 7));
        assertFalse(operations.writeBitmapFact("bm:like:post:42:0", 7, true));
        assertFalse(operations.tryAcquireRateLimit("count:rate-limit:rebuild:{42}", 30));
        verify(redisTemplate, never()).delete("unused");
    }

    @Test
    void incrementSnapshotSlotOnceShouldUseLuaScriptAndDedupKey() {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        when(redisTemplate.execute(any(RedisScript.class), eq(java.util.List.of("ucnt:7", "ucnt:evt:dedup:7:following:e-1")),
                eq("0"), eq("1"), eq("86400"), eq("5")))
                .thenReturn(1L);

        CountRedisOperations operations = new CountRedisOperations(redisTemplate);

        boolean applied = operations.incrementSnapshotSlotOnce(
                "ucnt:7",
                0,
                1L,
                "ucnt:evt:dedup:7:following:e-1",
                86400L,
                CountRedisSchema.user());

        assertTrue(applied);
    }
}
