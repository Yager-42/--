package cn.nexus.infrastructure.adapter.social.port;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.domain.social.model.valobj.ReactionTargetTypeEnumVO;
import cn.nexus.domain.social.model.valobj.ReactionTargetVO;
import cn.nexus.domain.social.model.valobj.ReactionTypeEnumVO;
import cn.nexus.infrastructure.dao.social.IInteractionReactionCountDao;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class ReactionCachePortTest {

    @Test
    void batchGetCount_shouldUseSingleMultiGetAndOnlyRebuildMisses() {
        StringRedisTemplate stringRedisTemplate = Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        IInteractionReactionCountDao countDao = Mockito.mock(IInteractionReactionCountDao.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        ReactionCachePort port = new ReactionCachePort(stringRedisTemplate, countDao);
        ReactionTargetVO first = target(1L);
        ReactionTargetVO second = target(2L);
        ReactionTargetVO third = target(3L);

        when(valueOperations.multiGet(any())).thenReturn(java.util.Arrays.asList("5", null, "bad"));
        when(countDao.selectCount(eq("POST"), eq(2L), eq("LIKE"))).thenReturn(7L);
        when(countDao.selectCount(eq("POST"), eq(3L), eq("LIKE"))).thenReturn(null);

        Map<String, Long> result = port.batchGetCount(List.of(first, second, third));

        assertEquals(5L, result.get(first.hashTag()));
        assertEquals(7L, result.get(second.hashTag()));
        assertEquals(0L, result.get(third.hashTag()));
        verify(valueOperations, times(1)).multiGet(any());
        verify(countDao, times(2)).selectCount(anyString(), anyLong(), anyString());
        verify(valueOperations).set(eq("interact:reaction:cnt:" + second.hashTag()), eq("7"));
        verify(valueOperations).set(eq("interact:reaction:cnt:" + third.hashTag()), eq("0"));
    }

    private ReactionTargetVO target(Long postId) {
        return ReactionTargetVO.builder()
                .targetType(ReactionTargetTypeEnumVO.POST)
                .targetId(postId)
                .reactionType(ReactionTypeEnumVO.LIKE)
                .build();
    }
}
