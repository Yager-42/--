package cn.nexus.infrastructure.adapter.social.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.domain.social.adapter.port.IReactionCachePort;
import cn.nexus.domain.social.model.valobj.FeedCardStatVO;
import cn.nexus.domain.social.model.valobj.ReactionTargetTypeEnumVO;
import cn.nexus.domain.social.model.valobj.ReactionTargetVO;
import cn.nexus.domain.social.model.valobj.ReactionTypeEnumVO;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class FeedCardStatRepositoryTest {

    @Test
    void getBatch_shouldAdaptReactionCachePort() {
        IReactionCachePort reactionCachePort = Mockito.mock(IReactionCachePort.class);
        FeedCardStatRepository repository = new FeedCardStatRepository(reactionCachePort);

        ReactionTargetVO first = target(1L);
        ReactionTargetVO second = target(2L);
        when(reactionCachePort.batchGetCount(any()))
                .thenReturn(Map.of(first.hashTag(), 3L, second.hashTag(), 9L));

        Map<Long, FeedCardStatVO> result = repository.getBatch(List.of(1L, 2L));

        assertEquals(3L, result.get(1L).getLikeCount());
        assertEquals(9L, result.get(2L).getLikeCount());
        verify(reactionCachePort).batchGetCount(any());
    }

    private ReactionTargetVO target(Long postId) {
        return ReactionTargetVO.builder()
                .targetType(ReactionTargetTypeEnumVO.POST)
                .targetId(postId)
                .reactionType(ReactionTypeEnumVO.LIKE)
                .build();
    }
}
