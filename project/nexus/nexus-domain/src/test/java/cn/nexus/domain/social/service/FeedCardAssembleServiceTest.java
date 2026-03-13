package cn.nexus.domain.social.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.domain.social.adapter.port.IReactionCachePort;
import cn.nexus.domain.social.adapter.repository.IContentRepository;
import cn.nexus.domain.social.adapter.repository.IFeedCardRepository;
import cn.nexus.domain.social.adapter.repository.IFeedFollowSeenRepository;
import cn.nexus.domain.social.adapter.repository.IReactionRepository;
import cn.nexus.domain.social.adapter.repository.IUserBaseRepository;
import cn.nexus.domain.social.model.valobj.FeedCardBaseVO;
import cn.nexus.domain.social.model.valobj.FeedInboxEntryVO;
import cn.nexus.domain.social.model.valobj.FeedItemVO;
import cn.nexus.domain.social.model.valobj.ReactionTargetTypeEnumVO;
import cn.nexus.domain.social.model.valobj.ReactionTargetVO;
import cn.nexus.domain.social.model.valobj.ReactionTypeEnumVO;
import cn.nexus.domain.social.model.valobj.UserBriefVO;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class FeedCardAssembleServiceTest {

    @Test
    void assemble_shouldLoadLikeCountFromReactionCacheBatch() {
        IFeedCardRepository feedCardRepository = Mockito.mock(IFeedCardRepository.class);
        IReactionCachePort reactionCachePort = Mockito.mock(IReactionCachePort.class);
        IContentRepository contentRepository = Mockito.mock(IContentRepository.class);
        IUserBaseRepository userBaseRepository = Mockito.mock(IUserBaseRepository.class);
        IReactionRepository reactionRepository = Mockito.mock(IReactionRepository.class);
        RelationQueryService relationQueryService = Mockito.mock(RelationQueryService.class);
        IFeedFollowSeenRepository feedFollowSeenRepository = Mockito.mock(IFeedFollowSeenRepository.class);

        FeedCardAssembleService service = new FeedCardAssembleService(
                feedCardRepository,
                reactionCachePort,
                contentRepository,
                userBaseRepository,
                reactionRepository,
                relationQueryService,
                feedFollowSeenRepository
        );

        FeedCardBaseVO base = FeedCardBaseVO.builder()
                .postId(101L)
                .authorId(201L)
                .text("hello")
                .summary("sum")
                .build();
        when(feedCardRepository.getOrLoadBatch(eq(List.of(101L)), any()))
                .thenReturn(Map.of(101L, base));
        when(userBaseRepository.listByUserIds(List.of(201L)))
                .thenReturn(List.of(UserBriefVO.builder().userId(201L).nickname("author").avatarUrl("a.png").build()));
        when(reactionRepository.batchExists(any(), eq(1L), eq(List.of(101L)))).thenReturn(Set.of());
        when(relationQueryService.batchFollowing(eq(1L), eq(List.of(201L)))).thenReturn(Set.of());
        when(feedFollowSeenRepository.batchSeen(eq(1L), eq(List.of(101L)))).thenReturn(Set.of());

        ReactionTargetVO target = ReactionTargetVO.builder()
                .targetType(ReactionTargetTypeEnumVO.POST)
                .targetId(101L)
                .reactionType(ReactionTypeEnumVO.LIKE)
                .build();
        when(reactionCachePort.batchGetCount(any()))
                .thenReturn(Map.of(target.hashTag(), 8L));

        List<FeedItemVO> items = service.assemble(
                1L,
                "FOLLOW",
                List.of(FeedInboxEntryVO.builder().postId(101L).build()),
                20
        );

        assertEquals(1, items.size());
        assertEquals(8L, items.get(0).getLikeCount());
        verify(reactionCachePort).batchGetCount(any());
    }
}
