package cn.nexus.trigger.counter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.domain.counter.model.event.CounterDeltaEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

class CounterAggregationConsumerTest {

    @Test
    void applyAggDeltaScriptShouldInitializeMissingOrMalformedSnapshot() throws Exception {
        Field field = CounterAggregationConsumer.class.getDeclaredField("APPLY_AGG_DELTA_SCRIPT");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        RedisScript<List> script = (RedisScript<List>) field.get(null);

        String lua = script.getScriptAsString();

        assertTrue(lua.contains("string.rep(string.char(0), len)"));
        assertFalse(lua.contains("return {'0','malformed'}"));
    }

    @Test
    void onMessageShouldAccumulatePostLikeAndFavIntoSingleAggregationBucket() throws Exception {
        Fixture fixture = new Fixture();
        String like = json("post", 42L, "like", 1, 7L, 3L, 1000L);
        String fav = json("post", 42L, "fav", 2, 8L, 2L, 1001L);

        fixture.consumer.onMessage(like);
        fixture.consumer.onMessage(fav);

        verify(fixture.hashOperations).increment("agg:v1:post:42", "1", 3L);
        verify(fixture.hashOperations).increment("agg:v1:post:42", "2", 2L);
        verify(fixture.setOperations, Mockito.times(2)).add("agg:v1:active", "agg:v1:post:42");
        verify(fixture.valueOperations, Mockito.times(2)).setIfAbsent(
                eq("count:rebuild-lock:object:{post:42}"),
                Mockito.anyString(),
                eq(300L),
                eq(java.util.concurrent.TimeUnit.SECONDS));
    }

    @Test
    void onMessageShouldWaitAndAccumulateWhenObjectRebuildLockIsTemporarilyUnavailable() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.valueOperations.setIfAbsent(
                eq("count:rebuild-lock:object:{post:42}"),
                Mockito.anyString(),
                eq(300L),
                eq(java.util.concurrent.TimeUnit.SECONDS)))
                .thenReturn(Boolean.FALSE)
                .thenReturn(Boolean.TRUE);

        fixture.consumer.onMessage(json("post", 42L, "like", 1, 7L, 3L, 1000L));

        verify(fixture.valueOperations, Mockito.times(2)).setIfAbsent(
                eq("count:rebuild-lock:object:{post:42}"),
                Mockito.anyString(),
                eq(300L),
                eq(java.util.concurrent.TimeUnit.SECONDS));
        verify(fixture.hashOperations).increment("agg:v1:post:42", "1", 3L);
        verify(fixture.setOperations).add("agg:v1:active", "agg:v1:post:42");
    }

    @Test
    void onMessageShouldIgnoreCommentTargetAndUnknownMetric() throws Exception {
        Fixture fixture = new Fixture();

        fixture.consumer.onMessage(json("comment", 42L, "like", 1, 7L, 3L));
        fixture.consumer.onMessage(json("post", 42L, "share", 9, 7L, 3L));
        fixture.consumer.onMessage(json("post", 42L, "like", 1, 7L, 0L));

        verify(fixture.hashOperations, never()).increment(Mockito.anyString(), Mockito.anyString(), Mockito.anyLong());
        verify(fixture.setOperations, never()).add(Mockito.anyString(), Mockito.anyString());
    }

    @Test
    void onMessageShouldDropEventsCoveredByObjectRebuildWatermark() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.valueOperations.get("count:rebuild-watermark:object:{post:42}:1")).thenReturn("1000");

        fixture.consumer.onMessage(json("post", 42L, "like", 1, 7L, 3L, 999L));

        verify(fixture.hashOperations, never()).increment(Mockito.anyString(), Mockito.anyString(), Mockito.anyLong());
        verify(fixture.setOperations, never()).add(Mockito.anyString(), Mockito.anyString());
    }

    @Test
    void onMessageShouldDropActivePostCounterEventsWithoutTimestamp() throws Exception {
        Fixture fixture = new Fixture();

        fixture.consumer.onMessage(json("post", 42L, "like", 1, 7L, 3L));
        fixture.consumer.onMessage(json("post", 42L, "fav", 2, 7L, 1L));

        verify(fixture.valueOperations, never()).setIfAbsent(
                eq("count:rebuild-lock:object:{post:42}"),
                Mockito.anyString(),
                eq(300L),
                eq(java.util.concurrent.TimeUnit.SECONDS));
        verify(fixture.hashOperations, never()).increment(Mockito.anyString(), Mockito.anyString(), Mockito.anyLong());
        verify(fixture.setOperations, never()).add(Mockito.anyString(), Mockito.anyString());
    }

    @Test
    void flushActiveBucketsShouldRequeueBucketWhileObjectRebuildLockIsHeld() {
        Fixture fixture = new Fixture();
        when(fixture.setOperations.size("agg:v1:active")).thenReturn(1L);
        when(fixture.setOperations.pop("agg:v1:active")).thenReturn("agg:v1:post:42");
        when(fixture.valueOperations.setIfAbsent(
                eq("count:rebuild-lock:object:{post:42}"),
                Mockito.anyString(),
                eq(300L),
                eq(java.util.concurrent.TimeUnit.SECONDS)))
                .thenReturn(Boolean.FALSE);

        fixture.consumer.flushActiveBuckets();

        verify(fixture.setOperations).add("agg:v1:active", "agg:v1:post:42");
        verify(fixture.hashOperations, never()).entries("agg:v1:post:42");
        verify(fixture.redisTemplate, never()).execute(any(RedisScript.class), eq(List.of("cnt:v1:post:42")), Mockito.any());
    }

    @Test
    void flushActiveBucketsShouldDrainFieldsAndApplySnapshotSlots() {
        Fixture fixture = new Fixture();
        when(fixture.setOperations.size("agg:v1:active")).thenReturn(1L);
        when(fixture.setOperations.pop("agg:v1:active")).thenReturn("agg:v1:post:42");
        when(fixture.hashOperations.entries("agg:v1:post:42")).thenReturn(Map.of("1", "3", "2", "4"));
        when(fixture.redisTemplate.execute(
                any(RedisScript.class),
                eq(List.of("agg:v1:post:42", "cnt:v1:post:42")),
                eq("1"),
                eq("5")))
                .thenReturn(List.of("1", "3", "3"));
        when(fixture.redisTemplate.execute(
                any(RedisScript.class),
                eq(List.of("agg:v1:post:42", "cnt:v1:post:42")),
                eq("2"),
                eq("5")))
                .thenReturn(List.of("1", "4", "4"));
        when(fixture.hashOperations.size("agg:v1:post:42")).thenReturn(0L);

        fixture.consumer.flushActiveBuckets();

        verify(fixture.redisTemplate).execute(
                any(RedisScript.class),
                eq(List.of("agg:v1:post:42", "cnt:v1:post:42")),
                eq("1"),
                eq("5"));
        verify(fixture.redisTemplate).execute(
                any(RedisScript.class),
                eq(List.of("agg:v1:post:42", "cnt:v1:post:42")),
                eq("2"),
                eq("5"));
    }

    @Test
    void flushActiveBucketsShouldInitializeMissingSnapshotAndApplyAggDelta() {
        Fixture fixture = new Fixture();
        when(fixture.setOperations.size("agg:v1:active")).thenReturn(1L);
        when(fixture.setOperations.pop("agg:v1:active")).thenReturn("agg:v1:post:42");
        when(fixture.hashOperations.entries("agg:v1:post:42")).thenReturn(Map.of("1", "3"));
        when(fixture.redisTemplate.execute(
                any(RedisScript.class),
                eq(List.of("agg:v1:post:42", "cnt:v1:post:42")),
                eq("1"),
                eq("5")))
                .thenReturn(List.of("1", "3", "3", "initialized"));
        when(fixture.hashOperations.size("agg:v1:post:42")).thenReturn(0L);

        fixture.consumer.flushActiveBuckets();

        verify(fixture.setOperations, never()).add("agg:v1:active", "agg:v1:post:42");
    }

    @Test
    void flushActiveBucketsShouldKeepBucketActiveWhenSlotIncrementFails() {
        Fixture fixture = new Fixture();
        when(fixture.setOperations.size("agg:v1:active")).thenReturn(1L);
        when(fixture.setOperations.pop("agg:v1:active")).thenReturn("agg:v1:post:42");
        when(fixture.hashOperations.entries("agg:v1:post:42")).thenReturn(Map.of("1", "3"));
        when(fixture.redisTemplate.execute(
                any(RedisScript.class),
                eq(List.of("agg:v1:post:42", "cnt:v1:post:42")),
                eq("1"),
                eq("5")))
                .thenReturn(null);
        when(fixture.hashOperations.size("agg:v1:post:42")).thenReturn(0L);

        fixture.consumer.flushActiveBuckets();

        verify(fixture.setOperations).add("agg:v1:active", "agg:v1:post:42");
    }

    @Test
    void flushActiveBucketsShouldDeleteInactiveOrInvalidSlotFieldsWithoutRequeueingForever() {
        Fixture fixture = new Fixture();
        when(fixture.setOperations.size("agg:v1:active")).thenReturn(1L);
        when(fixture.setOperations.pop("agg:v1:active")).thenReturn("agg:v1:post:42");
        when(fixture.hashOperations.entries("agg:v1:post:42"))
                .thenReturn(Map.of("0", "1", "3", "2", "4", "3", "9", "4", "bad", "5"));
        when(fixture.hashOperations.size("agg:v1:post:42")).thenReturn(0L);

        fixture.consumer.flushActiveBuckets();

        verify(fixture.hashOperations).delete("agg:v1:post:42", "0");
        verify(fixture.hashOperations).delete("agg:v1:post:42", "3");
        verify(fixture.hashOperations).delete("agg:v1:post:42", "4");
        verify(fixture.hashOperations).delete("agg:v1:post:42", "9");
        verify(fixture.hashOperations).delete("agg:v1:post:42", "bad");
        verify(fixture.redisTemplate, never()).execute(
                any(RedisScript.class),
                eq(List.of("agg:v1:post:42", "cnt:v1:post:42")),
                Mockito.anyString(),
                eq("5"));
        verify(fixture.setOperations, never()).add("agg:v1:active", "agg:v1:post:42");
    }

    @Test
    void flushActiveBucketsShouldRejectShardSuffixedBucketKeys() {
        Fixture fixture = new Fixture();
        when(fixture.setOperations.size("agg:v1:active")).thenReturn(1L);
        when(fixture.setOperations.pop("agg:v1:active")).thenReturn("agg:v1:post:42:7");

        fixture.consumer.flushActiveBuckets();

        verify(fixture.hashOperations, never()).entries(Mockito.anyString());
        verify(fixture.redisTemplate, never()).execute(any(RedisScript.class), eq(List.of("cnt:v1:post:42")), Mockito.any());
    }

    private static String json(String targetType, Long targetId, String metric, Integer slot,
                               Long actorUserId, Long delta) throws Exception {
        return json(targetType, targetId, metric, slot, actorUserId, delta, null);
    }

    private static String json(String targetType, Long targetId, String metric, Integer slot,
                               Long actorUserId, Long delta, Long tsMs) throws Exception {
        return new ObjectMapper().writeValueAsString(CounterDeltaEvent.builder()
                .targetType(targetType)
                .targetId(targetId)
                .metric(metric)
                .slot(slot)
                .actorUserId(actorUserId)
                .delta(delta)
                .tsMs(tsMs)
                .build());
    }

    private static class Fixture {
        private final StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        private final HashOperations<String, Object, Object> hashOperations = Mockito.mock(HashOperations.class);
        @SuppressWarnings("unchecked")
        private final SetOperations<String, String> setOperations = Mockito.mock(SetOperations.class);
        @SuppressWarnings("unchecked")
        private final ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        private final CounterAggregationConsumer consumer = new CounterAggregationConsumer(redisTemplate, new ObjectMapper());

        private Fixture() {
            when(redisTemplate.opsForHash()).thenReturn(hashOperations);
            when(redisTemplate.opsForSet()).thenReturn(setOperations);
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.setIfAbsent(
                    Mockito.startsWith("count:rebuild-lock:object:{post:"),
                    Mockito.anyString(),
                    eq(300L),
                    eq(java.util.concurrent.TimeUnit.SECONDS)))
                    .thenReturn(Boolean.TRUE);
        }
    }
}
