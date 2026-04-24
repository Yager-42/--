package cn.nexus.infrastructure.adapter.social.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.domain.counter.adapter.service.IObjectCounterService;
import cn.nexus.domain.counter.model.valobj.ObjectCounterType;
import cn.nexus.domain.social.model.valobj.FeedCardStatVO;
import cn.nexus.domain.social.model.valobj.ReactionTargetTypeEnumVO;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class FeedCardStatRepositoryTest {

    @Test
    void getBatch_shouldAdaptObjectCounterService() {
        IObjectCounterService objectCounterService = Mockito.mock(IObjectCounterService.class);
        FeedCardStatRepository repository = new FeedCardStatRepository(objectCounterService);

        when(objectCounterService.getCountsBatch(
                ReactionTargetTypeEnumVO.POST,
                List.of(1L, 2L),
                List.of(ObjectCounterType.LIKE)))
                .thenReturn(Map.of(
                        1L, Map.of("like", 3L),
                        2L, Map.of("like", 9L)));

        Map<Long, FeedCardStatVO> result = repository.getBatch(List.of(1L, 2L));

        assertEquals(3L, result.get(1L).getLikeCount());
        assertEquals(9L, result.get(2L).getLikeCount());
        verify(objectCounterService).getCountsBatch(any(), any(), any());
    }
}
