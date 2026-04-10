package cn.nexus.infrastructure.adapter.social.port;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

class ReactionCachePortTest {

    @Test
    void batchGetCount_shouldReturnZeroForRedisMissWithoutDbRebuild() {
        StringRedisTemplate stringRedisTemplate = Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        HotKeyStoreBridge hotKeyStoreBridge = Mockito.mock(HotKeyStoreBridge.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        ReactionCachePort port = new ReactionCachePort(stringRedisTemplate, hotKeyStoreBridge);
        ReactionTargetVO first = target(1L);
        ReactionTargetVO second = target(2L);
        ReactionTargetVO third = target(3L);

        when(valueOperations.multiGet(anyList())).thenReturn(java.util.Arrays.asList("5", null, "bad"));

        Map<String, Long> result = port.batchGetCount(List.of(first, second, third));

        assertEquals(5L, result.get(first.hashTag()));
        assertEquals(0L, result.get(second.hashTag()));
        assertEquals(0L, result.get(third.hashTag()));
        verify(valueOperations, times(1)).multiGet(anyList());
        verify(valueOperations, never()).set(eq("interact:reaction:cnt:" + second.hashTag()), any());
        verify(valueOperations, never()).set(eq("interact:reaction:cnt:" + third.hashTag()), any());
    }

    @Test
    void getCountFromRedis_shouldReturnZeroForMissingCounter() {
        StringRedisTemplate stringRedisTemplate = Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        HotKeyStoreBridge hotKeyStoreBridge = Mockito.mock(HotKeyStoreBridge.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("interact:reaction:cnt:POST:9:LIKE")).thenReturn(null);

        ReactionCachePort port = new ReactionCachePort(stringRedisTemplate, hotKeyStoreBridge);

        assertEquals(0L, port.getCountFromRedis(target(9L)));
        verify(valueOperations, never()).set(eq("interact:reaction:cnt:POST:9:LIKE"), any());
    }

    @Test
    void applyAtomic_shouldUseRedisTruthKeysAndReturnApplyResult() {
        StringRedisTemplate stringRedisTemplate = Mockito.mock(StringRedisTemplate.class);
        HotKeyStoreBridge hotKeyStoreBridge = Mockito.mock(HotKeyStoreBridge.class);
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(List.of(8L, 1L));

        ReactionCachePort port = new ReactionCachePort(stringRedisTemplate, hotKeyStoreBridge);
        ReactionTargetVO target = target(101L);

        ReactionApplyResultVO result = port.applyAtomic(7L, target, 1);

        assertNotNull(result);
        assertEquals(8L, result.getCurrentCount());
        assertEquals(1, result.getDelta());
        assertFalse(result.isFirstPending());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Object[]> argvCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(stringRedisTemplate).execute(any(RedisScript.class), keysCaptor.capture(), argvCaptor.capture());

        assertEquals(List.of(
                "interact:reaction:bm:{POST:101:LIKE}:0",
                "interact:reaction:cnt:{POST:101:LIKE}"
        ), keysCaptor.getValue());
        assertEquals(List.of("1", "7"),
                java.util.Arrays.stream(argvCaptor.getValue()).map(String::valueOf).toList());
    }

    @Test
    void applyAtomic_shouldReturnNullWhenLuaFails() {
        StringRedisTemplate stringRedisTemplate = Mockito.mock(StringRedisTemplate.class);
        HotKeyStoreBridge hotKeyStoreBridge = Mockito.mock(HotKeyStoreBridge.class);
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(List.of(-1L, 1L));

        ReactionCachePort port = new ReactionCachePort(stringRedisTemplate, hotKeyStoreBridge);

        ReactionApplyResultVO result = port.applyAtomic(7L, target(202L), 1);

        assertNull(result);
    }

    @Test
    void applyRecoveryEvent_shouldUseRecoveryScriptWithoutEventStreamKey() {
        StringRedisTemplate stringRedisTemplate = Mockito.mock(StringRedisTemplate.class);
        HotKeyStoreBridge hotKeyStoreBridge = Mockito.mock(HotKeyStoreBridge.class);
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(List.of(3L));

        ReactionCachePort port = new ReactionCachePort(stringRedisTemplate, hotKeyStoreBridge);
        ReactionTargetVO target = target(303L);

        boolean applied = port.applyRecoveryEvent(9L, target, 1);

        assertTrue(applied);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Object[]> argvCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(stringRedisTemplate).execute(any(RedisScript.class), keysCaptor.capture(), argvCaptor.capture());

        assertEquals(List.of(
                "interact:reaction:bm:{POST:303:LIKE}:0",
                "interact:reaction:cnt:{POST:303:LIKE}"
        ), keysCaptor.getValue());
        assertEquals(List.of("1", "9"),
                java.util.Arrays.stream(argvCaptor.getValue()).map(String::valueOf).toList());
    }

    @Test
    void applyRecoveryEvent_shouldReturnFalseWhenRecoveryScriptFails() {
        StringRedisTemplate stringRedisTemplate = Mockito.mock(StringRedisTemplate.class);
        HotKeyStoreBridge hotKeyStoreBridge = Mockito.mock(HotKeyStoreBridge.class);
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(List.of(-1L));

        ReactionCachePort port = new ReactionCachePort(stringRedisTemplate, hotKeyStoreBridge);

        assertFalse(port.applyRecoveryEvent(9L, target(404L), 0));
    }

    private ReactionTargetVO target(Long postId) {
        return ReactionTargetVO.builder()
                .targetType(ReactionTargetTypeEnumVO.POST)
                .targetId(postId)
                .reactionType(ReactionTypeEnumVO.LIKE)
                .build();
    }
}
