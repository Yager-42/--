package cn.nexus.infrastructure.adapter.counter.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.domain.counter.adapter.port.ICounterEventProducer;
import cn.nexus.domain.counter.model.event.CounterDeltaEvent;
import cn.nexus.domain.counter.model.valobj.ObjectCounterType;
import cn.nexus.domain.social.model.valobj.ReactionTargetTypeEnumVO;
import cn.nexus.infrastructure.adapter.counter.support.CountRedisCodec;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class ObjectCounterServiceTest {

    @Test
    void likeShouldEmitDeltaOnlyOnStateTransition() {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        @SuppressWarnings("unchecked")
        HashOperations<String, Object, Object> hashOperations = Mockito.mock(HashOperations.class);
        @SuppressWarnings("unchecked")
        SetOperations<String, String> setOperations = Mockito.mock(SetOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(valueOperations.setBit("bm:like:post:42:0", 7L, true))
                .thenReturn(Boolean.FALSE)
                .thenReturn(Boolean.TRUE);

        ICounterEventProducer counterEventProducer = Mockito.mock(ICounterEventProducer.class);
        ObjectCounterService service = new ObjectCounterService(redisTemplate, counterEventProducer);

        assertTrue(service.like(ReactionTargetTypeEnumVO.POST, 42L, 7L));
        assertFalse(service.like(ReactionTargetTypeEnumVO.POST, 42L, 7L));

        verify(hashOperations, never()).increment(Mockito.anyString(), Mockito.anyString(), Mockito.anyLong());
        verify(setOperations).add("bm:like:post:42:idx", "0");
        verify(counterEventProducer).publish(CounterDeltaEvent.builder()
                .entityType(ReactionTargetTypeEnumVO.POST)
                .entityId(42L)
                .metric(ObjectCounterType.LIKE)
                .idx(1)
                .userId(7L)
                .delta(1L)
                .build());
    }

    @Test
    void unlikeShouldEmitNegativeDeltaOnlyOnStateTransition() {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        @SuppressWarnings("unchecked")
        HashOperations<String, Object, Object> hashOperations = Mockito.mock(HashOperations.class);
        @SuppressWarnings("unchecked")
        SetOperations<String, String> setOperations = Mockito.mock(SetOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(valueOperations.setBit("bm:like:post:42:0", 7L, false))
                .thenReturn(Boolean.TRUE)
                .thenReturn(Boolean.FALSE);

        ICounterEventProducer counterEventProducer = Mockito.mock(ICounterEventProducer.class);
        ObjectCounterService service = new ObjectCounterService(redisTemplate, counterEventProducer);

        assertTrue(service.unlike(ReactionTargetTypeEnumVO.POST, 42L, 7L));
        assertFalse(service.unlike(ReactionTargetTypeEnumVO.POST, 42L, 7L));

        verify(hashOperations, never()).increment(Mockito.anyString(), Mockito.anyString(), Mockito.anyLong());
        verify(setOperations).add("bm:like:post:42:idx", "0");
        verify(counterEventProducer).publish(CounterDeltaEvent.builder()
                .entityType(ReactionTargetTypeEnumVO.POST)
                .entityId(42L)
                .metric(ObjectCounterType.LIKE)
                .idx(1)
                .userId(7L)
                .delta(-1L)
                .build());
    }

    @Test
    void isLikedShouldReadBitmapTruth() {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.getBit("bm:like:post:99:1", 0L)).thenReturn(Boolean.TRUE);

        ObjectCounterService service = new ObjectCounterService(redisTemplate, Mockito.mock(ICounterEventProducer.class));

        assertTrue(service.isLiked(ReactionTargetTypeEnumVO.POST, 99L, 32768L));
    }

    @Test
    void getCountsBatchShouldReturnZeroForMissingOrMalformedSnapshotsWithoutRebuild() {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        when(redisTemplate.execute(any(RedisCallback.class)))
                .thenReturn(CountRedisCodec.toRedisValue(CountRedisCodec.encodeSlots(new long[]{0L, 6L, 0L, 0L, 0L}, 5)))
                .thenReturn(new byte[]{1, 2, 3});

        ObjectCounterService service = new ObjectCounterService(redisTemplate, Mockito.mock(ICounterEventProducer.class));

        Map<Long, Map<String, Long>> values = service.getCountsBatch(
                ReactionTargetTypeEnumVO.POST,
                List.of(11L, 12L),
                List.of(ObjectCounterType.LIKE));

        assertEquals(6L, values.get(11L).get("like"));
        assertEquals(0L, values.get(12L).get("like"));
        verify(redisTemplate, Mockito.times(2)).execute(Mockito.any(org.springframework.data.redis.core.RedisCallback.class));
    }

    @Test
    void getCountsShouldRebuildFromBitmapWhenSnapshotMissingAndGuardsAllow() {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        @SuppressWarnings("unchecked")
        HashOperations<String, Object, Object> hashOperations = Mockito.mock(HashOperations.class);
        @SuppressWarnings("unchecked")
        SetOperations<String, String> setOperations = Mockito.mock(SetOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(valueOperations.get("count:rebuild-backoff:object:{POST:42:like}")).thenReturn(null);
        when(valueOperations.setIfAbsent(eq("count:rate-limit:object:{POST:42:like}"), eq("1"), anyLong(), eq(java.util.concurrent.TimeUnit.SECONDS)))
                .thenReturn(Boolean.TRUE);
        when(valueOperations.setIfAbsent(eq("count:rebuild-lock:object:{POST:42:like}"), eq("1"), anyLong(), eq(java.util.concurrent.TimeUnit.SECONDS)))
                .thenReturn(Boolean.TRUE);
        when(setOperations.members("bm:like:post:42:idx")).thenReturn(java.util.Set.of("0", "3"));
        when(redisTemplate.execute(any(RedisCallback.class)))
                .thenReturn(null)
                .thenReturn(4L)
                .thenReturn(3L)
                .thenReturn(Boolean.TRUE);

        ObjectCounterService service = new ObjectCounterService(redisTemplate, Mockito.mock(ICounterEventProducer.class));

        Map<String, Long> values = service.getCounts(
                ReactionTargetTypeEnumVO.POST,
                42L,
                List.of(ObjectCounterType.LIKE));

        assertEquals(7L, values.get("like"));
        verify(setOperations).members("bm:like:post:42:idx");
        verify(hashOperations, Mockito.times(64)).delete(Mockito.startsWith("agg:v1:post:42:"), eq("1"));
        verify(redisTemplate, Mockito.times(4)).execute(Mockito.any(RedisCallback.class));
        verify(redisTemplate).delete("count:rebuild-lock:object:{POST:42:like}");
        verify(redisTemplate).delete("count:rebuild-backoff:object:{POST:42:like}");
    }

    @Test
    void getCountsShouldReturnZeroWhenBackoffBlocksRebuild() {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("count:rebuild-backoff:object:{POST:42:like}"))
                .thenReturn(String.valueOf(System.currentTimeMillis() + 30_000L));

        ObjectCounterService service = new ObjectCounterService(redisTemplate, Mockito.mock(ICounterEventProducer.class));

        Map<String, Long> values = service.getCounts(
                ReactionTargetTypeEnumVO.POST,
                42L,
                List.of(ObjectCounterType.LIKE));

        assertEquals(0L, values.get("like"));
        verify(valueOperations, never()).setIfAbsent(eq("count:rate-limit:object:{POST:42:like}"), eq("1"), anyLong(), eq(java.util.concurrent.TimeUnit.SECONDS));
        verify(redisTemplate).execute(any(RedisCallback.class));
    }

    @Test
    void getCountsShouldEscalateBackoffWhenRateLimitRejectsRebuild() {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("count:rebuild-backoff:object:{POST:42:like}")).thenReturn(null);
        when(valueOperations.setIfAbsent(eq("count:rate-limit:object:{POST:42:like}"), eq("1"), anyLong(), eq(java.util.concurrent.TimeUnit.SECONDS)))
                .thenReturn(Boolean.FALSE);

        ObjectCounterService service = new ObjectCounterService(redisTemplate, Mockito.mock(ICounterEventProducer.class));

        Map<String, Long> values = service.getCounts(
                ReactionTargetTypeEnumVO.POST,
                42L,
                List.of(ObjectCounterType.LIKE));

        assertEquals(0L, values.get("like"));
        verify(valueOperations, never()).setIfAbsent(eq("count:rebuild-lock:object:{POST:42:like}"), eq("1"), anyLong(), eq(java.util.concurrent.TimeUnit.SECONDS));
        verify(valueOperations).set(eq("count:rebuild-backoff:object:{POST:42:like}"), any(), anyLong(), eq(java.util.concurrent.TimeUnit.SECONDS));
    }

    @Test
    void getCountsShouldEscalateBackoffWhenLockMisses() {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("count:rebuild-backoff:object:{POST:42:like}")).thenReturn(null);
        when(valueOperations.setIfAbsent(eq("count:rate-limit:object:{POST:42:like}"), eq("1"), anyLong(), eq(java.util.concurrent.TimeUnit.SECONDS)))
                .thenReturn(Boolean.TRUE);
        when(valueOperations.setIfAbsent(eq("count:rebuild-lock:object:{POST:42:like}"), eq("1"), anyLong(), eq(java.util.concurrent.TimeUnit.SECONDS)))
                .thenReturn(Boolean.FALSE);

        ObjectCounterService service = new ObjectCounterService(redisTemplate, Mockito.mock(ICounterEventProducer.class));

        Map<String, Long> values = service.getCounts(
                ReactionTargetTypeEnumVO.POST,
                42L,
                List.of(ObjectCounterType.LIKE));

        assertEquals(0L, values.get("like"));
        verify(valueOperations).set(eq("count:rebuild-backoff:object:{POST:42:like}"), any(), anyLong(), eq(java.util.concurrent.TimeUnit.SECONDS));
        verify(redisTemplate).execute(any(RedisCallback.class));
    }
}
