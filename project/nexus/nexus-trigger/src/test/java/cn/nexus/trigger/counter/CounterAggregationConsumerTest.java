package cn.nexus.trigger.counter;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.domain.counter.model.event.CounterDeltaEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

class CounterAggregationConsumerTest {

    @Test
    void onMessageShouldAccumulatePostLikeAndFavIntoSingleAggregationBucket() throws Exception {
        Fixture fixture = new Fixture();
        String like = json("post", 42L, "like", 1, 7L, 3L);
        String fav = json("post", 42L, "fav", 2, 8L, 2L);

        fixture.consumer.onMessage(like);
        fixture.consumer.onMessage(fav);

        verify(fixture.hashOperations).increment("agg:v1:post:42", "1", 3L);
        verify(fixture.hashOperations).increment("agg:v1:post:42", "2", 2L);
        verify(fixture.setOperations, Mockito.times(2)).add("agg:v1:active", "agg:v1:post:42");
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
    void flushActiveBucketsShouldDrainFieldsAndApplySnapshotSlots() {
        Fixture fixture = new Fixture();
        when(fixture.setOperations.size("agg:v1:active")).thenReturn(1L);
        when(fixture.setOperations.pop("agg:v1:active")).thenReturn("agg:v1:post:42");
        when(fixture.hashOperations.entries("agg:v1:post:42")).thenReturn(Map.of("1", "3", "2", "4"));
        when(fixture.redisTemplate.execute(any(RedisScript.class), eq(List.of("agg:v1:post:42")), eq("1")))
                .thenReturn(List.of("1", "3"));
        when(fixture.redisTemplate.execute(any(RedisScript.class), eq(List.of("agg:v1:post:42")), eq("2")))
                .thenReturn(List.of("1", "4"));
        when(fixture.redisTemplate.execute(any(RedisScript.class), eq(List.of("cnt:v1:post:42")), eq("1"), eq("3"), eq("5")))
                .thenReturn(3L);
        when(fixture.redisTemplate.execute(any(RedisScript.class), eq(List.of("cnt:v1:post:42")), eq("2"), eq("4"), eq("5")))
                .thenReturn(4L);
        when(fixture.hashOperations.size("agg:v1:post:42")).thenReturn(0L);

        fixture.consumer.flushActiveBuckets();

        verify(fixture.redisTemplate).execute(any(RedisScript.class), eq(List.of("cnt:v1:post:42")), eq("1"), eq("3"), eq("5"));
        verify(fixture.redisTemplate).execute(any(RedisScript.class), eq(List.of("cnt:v1:post:42")), eq("2"), eq("4"), eq("5"));
    }

    @Test
    void flushActiveBucketsShouldKeepBucketActiveWhenSlotIncrementFails() {
        Fixture fixture = new Fixture();
        when(fixture.setOperations.size("agg:v1:active")).thenReturn(1L);
        when(fixture.setOperations.pop("agg:v1:active")).thenReturn("agg:v1:post:42");
        when(fixture.hashOperations.entries("agg:v1:post:42")).thenReturn(Map.of("1", "3"));
        when(fixture.redisTemplate.execute(any(RedisScript.class), eq(List.of("agg:v1:post:42")), eq("1")))
                .thenReturn(List.of("1", "3"));
        when(fixture.redisTemplate.execute(any(RedisScript.class), eq(List.of("cnt:v1:post:42")), eq("1"), eq("3"), eq("5")))
                .thenReturn(null);
        when(fixture.hashOperations.size("agg:v1:post:42")).thenReturn(0L);

        fixture.consumer.flushActiveBuckets();

        verify(fixture.setOperations).add("agg:v1:active", "agg:v1:post:42");
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
        return new ObjectMapper().writeValueAsString(CounterDeltaEvent.builder()
                .targetType(targetType)
                .targetId(targetId)
                .metric(metric)
                .slot(slot)
                .actorUserId(actorUserId)
                .delta(delta)
                .build());
    }

    private static class Fixture {
        private final StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        private final HashOperations<String, Object, Object> hashOperations = Mockito.mock(HashOperations.class);
        @SuppressWarnings("unchecked")
        private final SetOperations<String, String> setOperations = Mockito.mock(SetOperations.class);
        private final CounterAggregationConsumer consumer = new CounterAggregationConsumer(redisTemplate, new ObjectMapper());

        private Fixture() {
            when(redisTemplate.opsForHash()).thenReturn(hashOperations);
            when(redisTemplate.opsForSet()).thenReturn(setOperations);
        }
    }
}
