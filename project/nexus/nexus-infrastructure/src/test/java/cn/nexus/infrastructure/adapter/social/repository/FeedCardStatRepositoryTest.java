package cn.nexus.infrastructure.adapter.social.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.domain.counter.adapter.port.IObjectCounterPort;
import cn.nexus.domain.counter.model.valobj.ObjectCounterTarget;
import cn.nexus.domain.counter.model.valobj.ObjectCounterType;
import cn.nexus.domain.social.model.valobj.FeedCardStatVO;
import cn.nexus.domain.social.model.valobj.ReactionTargetTypeEnumVO;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class FeedCardStatRepositoryTest {

    @Test
    void getBatch_shouldAdaptReactionCachePort() {
        IObjectCounterPort objectCounterPort = Mockito.mock(IObjectCounterPort.class);
        FeedCardStatRepository repository = new FeedCardStatRepository(objectCounterPort);

        ObjectCounterTarget first = target(1L);
        ObjectCounterTarget second = target(2L);
        when(objectCounterPort.batchGetCount(any()))
                .thenReturn(Map.of(first.hashTag(), 3L, second.hashTag(), 9L));

        Map<Long, FeedCardStatVO> result = repository.getBatch(List.of(1L, 2L));

        assertEquals(3L, result.get(1L).getLikeCount());
        assertEquals(9L, result.get(2L).getLikeCount());
        verify(objectCounterPort).batchGetCount(any());
    }

    private ObjectCounterTarget target(Long postId) {
        return ObjectCounterTarget.builder()
                .targetType(ReactionTargetTypeEnumVO.POST)
                .targetId(postId)
                .counterType(ObjectCounterType.LIKE)
                .build();
    }
}
