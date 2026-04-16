package cn.nexus.infrastructure.adapter.social.port;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.domain.social.model.valobj.ReactionApplyResultVO;
import cn.nexus.domain.social.model.valobj.ReactionTargetTypeEnumVO;
import cn.nexus.domain.social.model.valobj.ReactionTargetVO;
import cn.nexus.domain.social.model.valobj.ReactionTypeEnumVO;
import cn.nexus.infrastructure.config.HotKeyStoreBridge;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class ReactionCachePortTest {

    @Test
    void batchGetCount_shouldPreferDirectFactCountKeys() {
        StringRedisTemplate stringRedisTemplate = Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        HotKeyStoreBridge hotKeyStoreBridge = Mockito.mock(HotKeyStoreBridge.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        ReactionCachePort port = new ReactionCachePort(stringRedisTemplate, hotKeyStoreBridge);
        ReactionTargetVO first = target(1L);
        ReactionTargetVO second = target(2L);

        when(valueOperations.multiGet(List.of(
                "count:factcnt:post_like:{1}",
                "count:factcnt:post_like:{2}")))
                .thenReturn(List.of("5", "9"));

        Map<String, Long> result = port.batchGetCount(List.of(first, second));

        assertEquals(5L, result.get(first.hashTag()));
        assertEquals(9L, result.get(second.hashTag()));
    }

    @Test
    void getCountFromRedis_shouldReadFactCountKeyDirectly() {
        StringRedisTemplate stringRedisTemplate = Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        HotKeyStoreBridge hotKeyStoreBridge = Mockito.mock(HotKeyStoreBridge.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("count:factcnt:post_like:{9}")).thenReturn("10");

        ReactionCachePort port = new ReactionCachePort(stringRedisTemplate, hotKeyStoreBridge);

        assertEquals(10L, port.getCountFromRedis(target(9L)));
    }

    @Test
    void applyAtomic_shouldUseScriptAndReturnApplyResult() {
        StringRedisTemplate stringRedisTemplate = Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        HotKeyStoreBridge hotKeyStoreBridge = Mockito.mock(HotKeyStoreBridge.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(stringRedisTemplate.execute(any(), any(List.class), any(), any()))
                .thenReturn(List.of(1L, 1L));

        ReactionCachePort port = new ReactionCachePort(stringRedisTemplate, hotKeyStoreBridge);
        ReactionTargetVO target = target(101L);

        ReactionApplyResultVO result = port.applyAtomic(7L, target, 1);

        assertNotNull(result);
        assertEquals(1L, result.getCurrentCount());
        assertEquals(1, result.getDelta());
        assertFalse(result.isFirstPending());
    }

    @Test
    void applyAtomic_shouldSkipMutationWhenDesiredStateIsUnchanged() {
        StringRedisTemplate stringRedisTemplate = Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        HotKeyStoreBridge hotKeyStoreBridge = Mockito.mock(HotKeyStoreBridge.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(stringRedisTemplate.execute(any(), any(List.class), any(), any()))
                .thenReturn(List.of(4L, 0L));

        ReactionCachePort port = new ReactionCachePort(stringRedisTemplate, hotKeyStoreBridge);

        ReactionApplyResultVO result = port.applyAtomic(7L, target(202L), 1);

        assertNotNull(result);
        assertEquals(4L, result.getCurrentCount());
        assertEquals(0, result.getDelta());
    }

    @Test
    void applyAtomic_shouldReturnZeroDeltaWhenPreviousStateAlreadyLiked() {
        StringRedisTemplate stringRedisTemplate = Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        HotKeyStoreBridge hotKeyStoreBridge = Mockito.mock(HotKeyStoreBridge.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(stringRedisTemplate.execute(any(), any(List.class), any(), any()))
                .thenReturn(List.of(1L, 0L));

        ReactionCachePort port = new ReactionCachePort(stringRedisTemplate, hotKeyStoreBridge);

        ReactionApplyResultVO result = port.applyAtomic(7L, target(222L), 1);

        assertNotNull(result);
        assertEquals(1L, result.getCurrentCount());
        assertEquals(0, result.getDelta());
    }

    @Test
    void applyRecoveryEvent_shouldWriteCountRedisBitmapFact() {
        StringRedisTemplate stringRedisTemplate = Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        HotKeyStoreBridge hotKeyStoreBridge = Mockito.mock(HotKeyStoreBridge.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(stringRedisTemplate.execute(any(), any(List.class), any(), any()))
                .thenReturn(List.of(1L, 1L));

        ReactionCachePort port = new ReactionCachePort(stringRedisTemplate, hotKeyStoreBridge);
        ReactionTargetVO target = target(303L);

        boolean applied = port.applyRecoveryEvent(9L, target, 1);

        assertTrue(applied);
        verify(stringRedisTemplate, times(1)).execute(any(), eq(List.of("count:fact:post_like:{303}:0", "count:factcnt:post_like:{303}")), eq("9"), eq("1"));
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
