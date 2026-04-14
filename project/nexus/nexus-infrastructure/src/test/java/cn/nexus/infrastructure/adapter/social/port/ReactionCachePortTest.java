package cn.nexus.infrastructure.adapter.social.port;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.domain.social.model.valobj.ReactionApplyResultVO;
import cn.nexus.domain.social.model.valobj.ReactionTargetTypeEnumVO;
import cn.nexus.domain.social.model.valobj.ReactionTargetVO;
import cn.nexus.domain.social.model.valobj.ReactionTypeEnumVO;
import cn.nexus.infrastructure.config.HotKeyStoreBridge;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class ReactionCachePortTest {

    @Test
    void batchGetCount_shouldReturnBitmapCountsFromCountRedisFacts() {
        StringRedisTemplate stringRedisTemplate = Mockito.mock(StringRedisTemplate.class);
        HotKeyStoreBridge hotKeyStoreBridge = Mockito.mock(HotKeyStoreBridge.class);
        when(stringRedisTemplate.keys("count:fact:post_like:{1}:*"))
                .thenReturn(Set.of("count:fact:post_like:{1}:0"));
        when(stringRedisTemplate.keys("count:fact:post_like:{2}:*"))
                .thenReturn(Set.of("count:fact:post_like:{2}:0", "count:fact:post_like:{2}:1"));
        when(stringRedisTemplate.keys("count:fact:post_like:{3}:*"))
                .thenReturn(Set.of());
        when(stringRedisTemplate.execute(any(RedisCallback.class)))
                .thenReturn(5L)
                .thenReturn(2L)
                .thenReturn(3L);

        ReactionCachePort port = new ReactionCachePort(stringRedisTemplate, hotKeyStoreBridge);
        ReactionTargetVO first = target(1L);
        ReactionTargetVO second = target(2L);
        ReactionTargetVO third = target(3L);

        Map<String, Long> result = port.batchGetCount(List.of(first, second, third));

        assertEquals(5L, result.get(first.hashTag()));
        assertEquals(5L, result.get(second.hashTag()));
        assertEquals(0L, result.get(third.hashTag()));
    }

    @Test
    void getCountFromRedis_shouldCountBitmapFactsAcrossShards() {
        StringRedisTemplate stringRedisTemplate = Mockito.mock(StringRedisTemplate.class);
        HotKeyStoreBridge hotKeyStoreBridge = Mockito.mock(HotKeyStoreBridge.class);
        when(stringRedisTemplate.keys("count:fact:post_like:{9}:*"))
                .thenReturn(Set.of("count:fact:post_like:{9}:0", "count:fact:post_like:{9}:1"));
        when(stringRedisTemplate.execute(any(RedisCallback.class)))
                .thenReturn(4L)
                .thenReturn(6L);

        ReactionCachePort port = new ReactionCachePort(stringRedisTemplate, hotKeyStoreBridge);

        assertEquals(10L, port.getCountFromRedis(target(9L)));
    }

    @Test
    void applyAtomic_shouldUseCountRedisFactKeysAndReturnApplyResult() {
        StringRedisTemplate stringRedisTemplate = Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        HotKeyStoreBridge hotKeyStoreBridge = Mockito.mock(HotKeyStoreBridge.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setBit("count:fact:post_like:{101}:0", 7L, true)).thenReturn(Boolean.FALSE);
        when(stringRedisTemplate.keys("count:fact:post_like:{101}:*"))
                .thenReturn(Set.of("count:fact:post_like:{101}:0"));
        when(stringRedisTemplate.execute(any(RedisCallback.class))).thenReturn(1L);

        ReactionCachePort port = new ReactionCachePort(stringRedisTemplate, hotKeyStoreBridge);
        ReactionTargetVO target = target(101L);

        ReactionApplyResultVO result = port.applyAtomic(7L, target, 1);

        assertNotNull(result);
        assertEquals(1L, result.getCurrentCount());
        assertEquals(1, result.getDelta());
        assertFalse(result.isFirstPending());
        verify(valueOperations).setBit("count:fact:post_like:{101}:0", 7L, true);
    }

    @Test
    void applyAtomic_shouldSkipMutationWhenDesiredStateIsUnchanged() {
        StringRedisTemplate stringRedisTemplate = Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        HotKeyStoreBridge hotKeyStoreBridge = Mockito.mock(HotKeyStoreBridge.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setBit("count:fact:post_like:{202}:0", 7L, true)).thenReturn(Boolean.TRUE);
        when(stringRedisTemplate.keys("count:fact:post_like:{202}:*"))
                .thenReturn(Set.of("count:fact:post_like:{202}:0"));
        when(stringRedisTemplate.execute(any(RedisCallback.class))).thenReturn(4L);

        ReactionCachePort port = new ReactionCachePort(stringRedisTemplate, hotKeyStoreBridge);

        ReactionApplyResultVO result = port.applyAtomic(7L, target(202L), 1);

        assertNotNull(result);
        assertEquals(4L, result.getCurrentCount());
        assertEquals(0, result.getDelta());
        verify(valueOperations).setBit("count:fact:post_like:{202}:0", 7L, true);
    }

    @Test
    void applyAtomic_shouldReturnZeroDeltaWhenPreviousStateAlreadyLiked() {
        StringRedisTemplate stringRedisTemplate = Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        HotKeyStoreBridge hotKeyStoreBridge = Mockito.mock(HotKeyStoreBridge.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setBit("count:fact:post_like:{222}:0", 7L, true)).thenReturn(Boolean.TRUE);
        when(stringRedisTemplate.keys("count:fact:post_like:{222}:*"))
                .thenReturn(Set.of("count:fact:post_like:{222}:0"));
        when(stringRedisTemplate.execute(any(RedisCallback.class))).thenReturn(1L);

        ReactionCachePort port = new ReactionCachePort(stringRedisTemplate, hotKeyStoreBridge);

        ReactionApplyResultVO result = port.applyAtomic(7L, target(222L), 1);

        assertNotNull(result);
        assertEquals(1L, result.getCurrentCount());
        assertEquals(0, result.getDelta());
        verify(valueOperations).setBit("count:fact:post_like:{222}:0", 7L, true);
    }

    @Test
    void applyRecoveryEvent_shouldWriteCountRedisBitmapFact() {
        StringRedisTemplate stringRedisTemplate = Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        HotKeyStoreBridge hotKeyStoreBridge = Mockito.mock(HotKeyStoreBridge.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.getBit("count:fact:post_like:{303}:0", 9L)).thenReturn(Boolean.FALSE);
        when(valueOperations.setBit("count:fact:post_like:{303}:0", 9L, true)).thenReturn(Boolean.FALSE);

        ReactionCachePort port = new ReactionCachePort(stringRedisTemplate, hotKeyStoreBridge);
        ReactionTargetVO target = target(303L);

        boolean applied = port.applyRecoveryEvent(9L, target, 1);

        assertTrue(applied);
        verify(valueOperations).setBit("count:fact:post_like:{303}:0", 9L, true);
    }

    @Test
    void applyRecoveryEvent_shouldReturnFalseWhenTargetIsUnsupported() {
        StringRedisTemplate stringRedisTemplate = Mockito.mock(StringRedisTemplate.class);
        HotKeyStoreBridge hotKeyStoreBridge = Mockito.mock(HotKeyStoreBridge.class);

        ReactionCachePort port = new ReactionCachePort(stringRedisTemplate, hotKeyStoreBridge);

        ReactionTargetVO unsupported = ReactionTargetVO.builder()
                .targetType(ReactionTargetTypeEnumVO.USER)
                .targetId(404L)
                .reactionType(ReactionTypeEnumVO.LIKE)
                .build();

        assertFalse(port.applyRecoveryEvent(9L, unsupported, 0));
    }

    @Test
    void recoveryCheckpoint_shouldUseCountRedisReplayCheckpointKeys() {
        StringRedisTemplate stringRedisTemplate = Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        HotKeyStoreBridge hotKeyStoreBridge = Mockito.mock(HotKeyStoreBridge.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("count:replay:checkpoint:POST:LIKE")).thenReturn("12");

        ReactionCachePort port = new ReactionCachePort(stringRedisTemplate, hotKeyStoreBridge);

        assertEquals(12L, port.getRecoveryCheckpoint("POST", "LIKE"));
        port.setRecoveryCheckpoint("POST", "LIKE", 18L);

        verify(valueOperations).set("count:replay:checkpoint:POST:LIKE", "18");
    }

    @Test
    void getWindowMs_shouldReturnDefaultMs() {
        StringRedisTemplate stringRedisTemplate = Mockito.mock(StringRedisTemplate.class);
        HotKeyStoreBridge hotKeyStoreBridge = Mockito.mock(HotKeyStoreBridge.class);

        ReactionCachePort port = new ReactionCachePort(stringRedisTemplate, hotKeyStoreBridge);

        assertEquals(1234L, port.getWindowMs(target(505L), 1234L));
    }

    private ReactionTargetVO target(Long postId) {
        return ReactionTargetVO.builder()
                .targetType(ReactionTargetTypeEnumVO.POST)
                .targetId(postId)
                .reactionType(ReactionTypeEnumVO.LIKE)
                .build();
    }
}
