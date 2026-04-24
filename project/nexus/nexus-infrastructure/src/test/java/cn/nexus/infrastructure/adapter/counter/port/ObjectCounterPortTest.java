package cn.nexus.infrastructure.adapter.counter.port;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.domain.counter.model.valobj.ObjectCounterTarget;
import cn.nexus.domain.counter.model.valobj.ObjectCounterType;
import cn.nexus.domain.social.model.valobj.ReactionTargetTypeEnumVO;
import cn.nexus.infrastructure.adapter.counter.support.CountRedisCodec;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class ObjectCounterPortTest {

    @Test
    void getCountReadsPostLikeFromCntSnapshot() {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("cnt:v1:post:42"))
                .thenReturn(CountRedisCodec.toRedisValue(CountRedisCodec.encodeSlots(new long[]{0L, 7L, 0L, 0L, 0L}, 5)));

        ObjectCounterPort port = new ObjectCounterPort(redisTemplate);

        long count = port.getCount(target(ReactionTargetTypeEnumVO.POST, 42L, ObjectCounterType.LIKE));

        assertEquals(7L, count);
    }

    @Test
    void batchGetCountReadsLikeAndReturnsZeroForReply() {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.multiGet(List.of("cnt:v1:comment:8", "cnt:v1:comment:8", "cnt:v1:comment:9")))
                .thenReturn(List.of(
                        CountRedisCodec.toRedisValue(CountRedisCodec.encodeSlots(new long[]{0L, 5L, 0L, 0L, 0L}, 5)),
                        CountRedisCodec.toRedisValue(CountRedisCodec.encodeSlots(new long[]{0L, 5L, 0L, 0L, 0L}, 5)),
                        "not-base64"));

        ObjectCounterPort port = new ObjectCounterPort(redisTemplate);

        Map<String, Long> counts = port.batchGetCount(List.of(
                target(ReactionTargetTypeEnumVO.COMMENT, 8L, ObjectCounterType.LIKE),
                target(ReactionTargetTypeEnumVO.COMMENT, 8L, ObjectCounterType.REPLY),
                target(ReactionTargetTypeEnumVO.COMMENT, 9L, ObjectCounterType.REPLY)
        ));

        assertEquals(5L, counts.get(target(ReactionTargetTypeEnumVO.COMMENT, 8L, ObjectCounterType.LIKE).hashTag()));
        assertEquals(0L, counts.get(target(ReactionTargetTypeEnumVO.COMMENT, 8L, ObjectCounterType.REPLY).hashTag()));
        assertEquals(0L, counts.get(target(ReactionTargetTypeEnumVO.COMMENT, 9L, ObjectCounterType.REPLY).hashTag()));
    }

    @Test
    void malformedOrMissingSnapshotReturnsZeroWithoutLegacyFallback() {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("cnt:v1:comment:77")).thenReturn(null);

        ObjectCounterPort port = new ObjectCounterPort(redisTemplate);

        long count = port.getCount(target(ReactionTargetTypeEnumVO.COMMENT, 77L, ObjectCounterType.REPLY));

        assertEquals(0L, count);
        verify(valueOperations, never()).set(any(), any());
    }

    @Test
    void missingPostLikeSnapshotRebuildsFromBitmapTruthWhenGuardsAllow() {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        @SuppressWarnings("unchecked")
        HashOperations<String, Object, Object> hashOperations = Mockito.mock(HashOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(valueOperations.get("cnt:v1:post:42")).thenReturn(null);
        when(valueOperations.setIfAbsent(eq("count:rate-limit:object:{POST:42:like}"), eq("1"), anyLong(), eq(java.util.concurrent.TimeUnit.SECONDS)))
                .thenReturn(Boolean.TRUE);
        when(valueOperations.setIfAbsent(eq("count:rebuild-lock:object:{POST:42:like}"), eq("1"), anyLong(), eq(java.util.concurrent.TimeUnit.SECONDS)))
                .thenReturn(Boolean.TRUE);
        when(redisTemplate.execute(any(RedisCallback.class)))
                .thenReturn(7L);

        ObjectCounterPort port = new ObjectCounterPort(redisTemplate);

        long count = port.getCount(target(ReactionTargetTypeEnumVO.POST, 42L, ObjectCounterType.LIKE));

        assertEquals(7L, count);
        verify(hashOperations).delete("agg:v1:post:42", "1");
        verify(valueOperations).set("cnt:v1:post:42",
                CountRedisCodec.toRedisValue(CountRedisCodec.encodeSlots(new long[]{0L, 7L, 0L, 0L, 0L}, 5)));
        verify(redisTemplate).delete("count:rebuild-lock:object:{POST:42:like}");
    }

    @Test
    void malformedCommentLikeSnapshotReturnsZeroWhenRebuildLockIsBusy() {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        @SuppressWarnings("unchecked")
        HashOperations<String, Object, Object> hashOperations = Mockito.mock(HashOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(valueOperations.get("cnt:v1:comment:77")).thenReturn("not-base64");
        when(valueOperations.setIfAbsent(eq("count:rate-limit:object:{COMMENT:77:like}"), eq("1"), anyLong(), eq(java.util.concurrent.TimeUnit.SECONDS)))
                .thenReturn(Boolean.TRUE);
        when(valueOperations.setIfAbsent(eq("count:rebuild-lock:object:{COMMENT:77:like}"), eq("1"), anyLong(), eq(java.util.concurrent.TimeUnit.SECONDS)))
                .thenReturn(Boolean.FALSE);

        ObjectCounterPort port = new ObjectCounterPort(redisTemplate);

        long count = port.getCount(target(ReactionTargetTypeEnumVO.COMMENT, 77L, ObjectCounterType.LIKE));

        assertEquals(0L, count);
        verify(redisTemplate, never()).execute(any(RedisCallback.class));
        verify(hashOperations, never()).delete(any(), any());
        verify(valueOperations, never()).set(eq("cnt:v1:comment:77"), any());
        verify(redisTemplate, never()).delete("count:rebuild-lock:object:{COMMENT:77:like}");
    }

    @Test
    void setIncrementAndEvictOperateOnSnapshotKeys() {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        @SuppressWarnings("unchecked")
        HashOperations<String, Object, Object> hashOperations = Mockito.mock(HashOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(valueOperations.get("cnt:v1:comment:5"))
                .thenReturn(CountRedisCodec.toRedisValue(CountRedisCodec.encodeSlots(new long[]{0L, 4L, 0L, 0L, 0L}, 5)))
                .thenReturn(CountRedisCodec.toRedisValue(CountRedisCodec.encodeSlots(new long[]{0L, 6L, 0L, 0L, 0L}, 5)))
                .thenReturn(CountRedisCodec.toRedisValue(CountRedisCodec.encodeSlots(new long[]{0L, 6L, 0L, 0L, 0L}, 5)));

        ObjectCounterPort port = new ObjectCounterPort(redisTemplate);
        ObjectCounterTarget target = target(ReactionTargetTypeEnumVO.COMMENT, 5L, ObjectCounterType.LIKE);

        port.setCount(target, 6L);
        long incremented = port.increment(target, -9L);
        port.evict(target);

        assertEquals(0L, incremented);
        verify(hashOperations).increment("agg:v1:comment:5", "1", -9L);
        verify(valueOperations).set("cnt:v1:comment:5",
                CountRedisCodec.toRedisValue(CountRedisCodec.encodeSlots(new long[]{0L, 6L, 0L, 0L, 0L}, 5)));
        verify(valueOperations).set("cnt:v1:comment:5",
                CountRedisCodec.toRedisValue(CountRedisCodec.encodeSlots(new long[]{0L, 0L, 0L, 0L, 0L}, 5)));
        verify(redisTemplate).delete("cnt:v1:comment:5");
    }

    @Test
    void incrementReplyIsIgnoredAfterSchemaCutover() {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        @SuppressWarnings("unchecked")
        HashOperations<String, Object, Object> hashOperations = Mockito.mock(HashOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);

        ObjectCounterPort port = new ObjectCounterPort(redisTemplate);

        long updated = port.increment(target(ReactionTargetTypeEnumVO.COMMENT, 23L, ObjectCounterType.REPLY), 3L);

        assertEquals(0L, updated);
        verify(hashOperations, never()).increment(any(), any(), anyLong());
        verify(valueOperations, never()).set(any(), any());
    }

    private ObjectCounterTarget target(ReactionTargetTypeEnumVO type, Long id, ObjectCounterType counterType) {
        return ObjectCounterTarget.builder()
                .targetType(type)
                .targetId(id)
                .counterType(counterType)
                .build();
    }
}
