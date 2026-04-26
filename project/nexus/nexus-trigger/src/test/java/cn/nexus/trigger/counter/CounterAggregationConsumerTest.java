package cn.nexus.trigger.counter;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.domain.counter.model.event.CounterDeltaEvent;
import cn.nexus.domain.counter.model.valobj.ObjectCounterType;
import cn.nexus.domain.social.model.valobj.ReactionTargetTypeEnumVO;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    void onMessageShouldAccumulateIntoShardedAggregationBucketAndActiveIndex() throws Exception {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        HashOperations<String, Object, Object> hashOperations = Mockito.mock(HashOperations.class);
        @SuppressWarnings("unchecked")
        SetOperations<String, String> setOperations = Mockito.mock(SetOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        CounterAggregationConsumer consumer = new CounterAggregationConsumer(redisTemplate, new ObjectMapper());

        String json = new ObjectMapper().writeValueAsString(CounterDeltaEvent.builder()
                .entityType(ReactionTargetTypeEnumVO.POST)
                .entityId(42L)
                .metric(ObjectCounterType.LIKE)
                .idx(1)
                .userId(7L)
                .delta(3L)
                .build());

        consumer.onMessage(json);

        verify(hashOperations).increment("agg:v1:post:42:7", "1", 3L);
        verify(setOperations).add("agg:v1:active", "agg:v1:post:42:7");
    }

    @Test
    void flushActiveBucketsShouldAtomicallyDrainAndApplySnapshotDelta() {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        HashOperations<String, Object, Object> hashOperations = Mockito.mock(HashOperations.class);
        @SuppressWarnings("unchecked")
        SetOperations<String, String> setOperations = Mockito.mock(SetOperations.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(setOperations.size("agg:v1:active")).thenReturn(1L);
        when(setOperations.pop("agg:v1:active")).thenReturn("agg:v1:post:42:7");
        when(hashOperations.entries("agg:v1:post:42:7")).thenReturn(Map.of("1", "3"));
        when(redisTemplate.execute(any(RedisScript.class), eq(List.of("agg:v1:post:42:7")), eq("1")))
                .thenReturn(List.of("1", "3"));
        when(redisTemplate.execute(any(RedisScript.class), eq(List.of("cnt:v1:post:42")), eq("1"), eq("3"), eq("5")))
                .thenReturn(7L);
        when(hashOperations.size("agg:v1:post:42:7")).thenReturn(0L);
        CounterAggregationConsumer consumer = new CounterAggregationConsumer(redisTemplate, new ObjectMapper());

        consumer.flushActiveBuckets();

        verify(redisTemplate).execute(any(RedisScript.class), eq(List.of("cnt:v1:post:42")), eq("1"), eq("3"), eq("5"));
        verify(valueOperations, never()).set(eq("cnt:v1:post:42"), isA(String.class));
    }

    @Test
    void flushActiveBucketsShouldApplyMultipleShardsThroughAtomicSlotIncrements() {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        HashOperations<String, Object, Object> hashOperations = Mockito.mock(HashOperations.class);
        @SuppressWarnings("unchecked")
        SetOperations<String, String> setOperations = Mockito.mock(SetOperations.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(setOperations.size("agg:v1:active")).thenReturn(2L);
        when(setOperations.pop("agg:v1:active"))
                .thenReturn("agg:v1:post:42:7")
                .thenReturn("agg:v1:post:42:8");
        when(hashOperations.entries("agg:v1:post:42:7")).thenReturn(Map.of("1", "3"));
        when(hashOperations.entries("agg:v1:post:42:8")).thenReturn(Map.of("1", "4"));
        when(redisTemplate.execute(any(RedisScript.class), eq(List.of("agg:v1:post:42:7")), eq("1")))
                .thenReturn(List.of("1", "3"));
        when(redisTemplate.execute(any(RedisScript.class), eq(List.of("agg:v1:post:42:8")), eq("1")))
                .thenReturn(List.of("1", "4"));
        when(redisTemplate.execute(any(RedisScript.class), eq(List.of("cnt:v1:post:42")), eq("1"), eq("3"), eq("5")))
                .thenReturn(3L);
        when(redisTemplate.execute(any(RedisScript.class), eq(List.of("cnt:v1:post:42")), eq("1"), eq("4"), eq("5")))
                .thenReturn(7L);
        when(hashOperations.size("agg:v1:post:42:7")).thenReturn(0L);
        when(hashOperations.size("agg:v1:post:42:8")).thenReturn(0L);
        CounterAggregationConsumer consumer = new CounterAggregationConsumer(redisTemplate, new ObjectMapper());

        consumer.flushActiveBuckets();

        verify(redisTemplate).execute(any(RedisScript.class), eq(List.of("cnt:v1:post:42")), eq("1"), eq("3"), eq("5"));
        verify(redisTemplate).execute(any(RedisScript.class), eq(List.of("cnt:v1:post:42")), eq("1"), eq("4"), eq("5"));
        verify(valueOperations, never()).set(eq("cnt:v1:post:42"), isA(String.class));
    }

    @Test
    void flushActiveBucketsShouldKeepBucketActiveWhenConcurrentDeltaArrivesDuringDrain() {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        HashOperations<String, Object, Object> hashOperations = Mockito.mock(HashOperations.class);
        @SuppressWarnings("unchecked")
        SetOperations<String, String> setOperations = Mockito.mock(SetOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.size("agg:v1:active")).thenReturn(1L);
        when(setOperations.pop("agg:v1:active")).thenReturn("agg:v1:post:42:7");
        when(hashOperations.entries("agg:v1:post:42:7")).thenReturn(Map.of("1", "3"));
        when(redisTemplate.execute(any(RedisScript.class), eq(List.of("agg:v1:post:42:7")), eq("1")))
                .thenReturn(List.of("1", "3"));
        when(redisTemplate.execute(any(RedisScript.class), eq(List.of("cnt:v1:post:42")), eq("1"), eq("3"), eq("5")))
                .thenReturn(3L);
        when(hashOperations.size("agg:v1:post:42:7")).thenReturn(1L);
        CounterAggregationConsumer consumer = new CounterAggregationConsumer(redisTemplate, new ObjectMapper());

        consumer.flushActiveBuckets();

        verify(setOperations).add("agg:v1:active", "agg:v1:post:42:7");
    }

    @Test
    void flushActiveBucketsShouldRecoverMalformedSnapshotThroughAtomicSlotIncrementScript() {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        HashOperations<String, Object, Object> hashOperations = Mockito.mock(HashOperations.class);
        @SuppressWarnings("unchecked")
        SetOperations<String, String> setOperations = Mockito.mock(SetOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.size("agg:v1:active")).thenReturn(1L);
        when(setOperations.pop("agg:v1:active")).thenReturn("agg:v1:post:42:7");
        when(hashOperations.entries("agg:v1:post:42:7")).thenReturn(Map.of("1", "3"));
        when(redisTemplate.execute(any(RedisScript.class), eq(List.of("agg:v1:post:42:7")), eq("1")))
                .thenReturn(List.of("1", "3"));
        when(redisTemplate.execute(any(RedisScript.class), eq(List.of("cnt:v1:post:42")), eq("1"), eq("3"), eq("5")))
                .thenReturn(3L);
        when(hashOperations.size("agg:v1:post:42:7")).thenReturn(0L);
        CounterAggregationConsumer consumer = new CounterAggregationConsumer(redisTemplate, new ObjectMapper());

        consumer.flushActiveBuckets();

        verify(redisTemplate).execute(any(RedisScript.class), eq(List.of("cnt:v1:post:42")), eq("1"), eq("3"), eq("5"));
    }
}
