package cn.nexus.infrastructure.adapter.counter.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.domain.counter.model.valobj.ObjectCounterType;
import cn.nexus.domain.social.model.valobj.ReactionTargetTypeEnumVO;
import cn.nexus.infrastructure.adapter.counter.support.CountRedisCodec;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.HashOperations;
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
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(valueOperations.setBit("bm:like:post:42:0", 7L, true))
                .thenReturn(Boolean.FALSE)
                .thenReturn(Boolean.TRUE);

        ObjectCounterService service = new ObjectCounterService(redisTemplate);

        assertTrue(service.like(ReactionTargetTypeEnumVO.POST, 42L, 7L));
        assertFalse(service.like(ReactionTargetTypeEnumVO.POST, 42L, 7L));

        verify(hashOperations).increment("agg:v1:post:42", "1", 1L);
    }

    @Test
    void unlikeShouldEmitNegativeDeltaOnlyOnStateTransition() {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        @SuppressWarnings("unchecked")
        HashOperations<String, Object, Object> hashOperations = Mockito.mock(HashOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(valueOperations.setBit("bm:like:post:42:0", 7L, false))
                .thenReturn(Boolean.TRUE)
                .thenReturn(Boolean.FALSE);

        ObjectCounterService service = new ObjectCounterService(redisTemplate);

        assertTrue(service.unlike(ReactionTargetTypeEnumVO.POST, 42L, 7L));
        assertFalse(service.unlike(ReactionTargetTypeEnumVO.POST, 42L, 7L));

        verify(hashOperations).increment("agg:v1:post:42", "1", -1L);
    }

    @Test
    void isLikedShouldReadBitmapTruth() {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.getBit("bm:like:post:99:1", 0L)).thenReturn(Boolean.TRUE);

        ObjectCounterService service = new ObjectCounterService(redisTemplate);

        assertTrue(service.isLiked(ReactionTargetTypeEnumVO.POST, 99L, 32768L));
    }

    @Test
    void getCountsBatchShouldReturnZeroForMissingOrMalformedSnapshotsWithoutRebuild() {
        StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.multiGet(List.of("cnt:v1:post:11", "cnt:v1:post:12")))
                .thenReturn(List.of(
                        CountRedisCodec.toRedisValue(CountRedisCodec.encodeSlots(new long[]{0L, 6L, 0L, 0L, 0L}, 5)),
                        "not-base64"));

        ObjectCounterService service = new ObjectCounterService(redisTemplate);

        Map<Long, Map<String, Long>> values = service.getCountsBatch(
                ReactionTargetTypeEnumVO.POST,
                List.of(11L, 12L),
                List.of(ObjectCounterType.LIKE));

        assertEquals(6L, values.get(11L).get("like"));
        assertEquals(0L, values.get(12L).get("like"));
        verify(redisTemplate, never()).execute(Mockito.any(org.springframework.data.redis.core.RedisCallback.class));
    }
}
