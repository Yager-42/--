package cn.nexus.domain.social.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.domain.social.adapter.port.IRelationAdjacencyCachePort;
import cn.nexus.domain.social.adapter.port.IRecommendationPort;
import cn.nexus.domain.social.adapter.repository.IContentRepository;
import cn.nexus.domain.social.adapter.repository.IFeedAuthorCategoryRepository;
import cn.nexus.domain.social.adapter.repository.IFeedBigVPoolRepository;
import cn.nexus.domain.social.adapter.repository.IFeedFollowSeenRepository;
import cn.nexus.domain.social.adapter.repository.IFeedGlobalLatestRepository;
import cn.nexus.domain.social.adapter.repository.IFeedOutboxRepository;
import cn.nexus.domain.social.adapter.repository.IFeedRecommendSessionRepository;
import cn.nexus.domain.social.adapter.repository.IFeedTimelineRepository;
import cn.nexus.domain.social.adapter.repository.IRelationRepository;
import cn.nexus.domain.social.model.entity.ContentPostEntity;
import cn.nexus.domain.social.model.entity.RelationEntity;
import cn.nexus.domain.social.model.valobj.FeedItemVO;
import cn.nexus.domain.social.model.valobj.FeedNeighborsCursor;
import cn.nexus.domain.social.model.valobj.FeedPopularCursor;
import cn.nexus.domain.social.model.valobj.FeedRecommendCursor;
import cn.nexus.domain.social.model.valobj.FeedTimelineVO;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

class FeedServiceTest {

    private IContentRepository contentRepository;
    private IRelationAdjacencyCachePort relationAdjacencyCachePort;
    private IRelationRepository relationRepository;
    private IFeedAuthorCategoryRepository feedAuthorCategoryRepository;
    private IFeedTimelineRepository feedTimelineRepository;
    private IFeedOutboxRepository feedOutboxRepository;
    private IFeedBigVPoolRepository feedBigVPoolRepository;
    private IFeedFollowSeenRepository feedFollowSeenRepository;
    private IFeedInboxRebuildService feedInboxRebuildService;
    private IFeedGlobalLatestRepository feedGlobalLatestRepository;
    private IFeedRecommendSessionRepository feedRecommendSessionRepository;
    private IRecommendationPort recommendationPort;
    private FeedCardAssembleService feedCardAssembleService;
    private FeedService feedService;

    @BeforeEach
    void setUp() {
        contentRepository = Mockito.mock(IContentRepository.class);
        relationAdjacencyCachePort = Mockito.mock(IRelationAdjacencyCachePort.class);
        relationRepository = Mockito.mock(IRelationRepository.class);
        feedAuthorCategoryRepository = Mockito.mock(IFeedAuthorCategoryRepository.class);
        feedTimelineRepository = Mockito.mock(IFeedTimelineRepository.class);
        feedOutboxRepository = Mockito.mock(IFeedOutboxRepository.class);
        feedBigVPoolRepository = Mockito.mock(IFeedBigVPoolRepository.class);
        feedFollowSeenRepository = Mockito.mock(IFeedFollowSeenRepository.class);
        feedInboxRebuildService = Mockito.mock(IFeedInboxRebuildService.class);
        feedGlobalLatestRepository = Mockito.mock(IFeedGlobalLatestRepository.class);
        feedRecommendSessionRepository = Mockito.mock(IFeedRecommendSessionRepository.class);
        recommendationPort = Mockito.mock(IRecommendationPort.class);
        feedCardAssembleService = Mockito.mock(FeedCardAssembleService.class);

        feedService = new FeedService(
                contentRepository,
                relationAdjacencyCachePort,
                relationRepository,
                feedAuthorCategoryRepository,
                feedTimelineRepository,
                feedOutboxRepository,
                feedBigVPoolRepository,
                feedFollowSeenRepository,
                feedInboxRebuildService,
                feedGlobalLatestRepository,
                feedRecommendSessionRepository,
                recommendationPort,
                feedCardAssembleService
        );

        ReflectionTestUtils.setField(feedService, "recommendScanFactor", 1);
        ReflectionTestUtils.setField(feedService, "recommendPrefetchFactor", 1);
        ReflectionTestUtils.setField(feedService, "recommendMaxAppendRounds", 1);
        ReflectionTestUtils.setField(feedService, "bigvFollowerThreshold", 500000);
        ReflectionTestUtils.setField(feedService, "maxFollowings", 2000);
        ReflectionTestUtils.setField(feedService, "maxBigvFollowings", 200);
        ReflectionTestUtils.setField(feedService, "perBigvLimit", 50);
        ReflectionTestUtils.setField(feedService, "bigvPoolEnabled", false);
        ReflectionTestUtils.setField(feedService, "bigvPoolBuckets", 4);
        ReflectionTestUtils.setField(feedService, "bigvPoolFetchFactor", 30);
        ReflectionTestUtils.setField(feedService, "bigvPoolTriggerFollowings", 200);
        ReflectionTestUtils.setField(feedService, "trendingRecommenderName", "trending");
        ReflectionTestUtils.setField(feedService, "latestRecommenderName", "latest");
        ReflectionTestUtils.setField(feedService, "similarRecommenderName", "similar");
    }

    @Test
    void timeline_shouldReturnEmptyWhenUserIdIsNull() {
        FeedTimelineVO result = feedService.timeline(null, null, 20, "FOLLOW");

        assertNotNull(result);
        assertEquals(List.of(), result.getItems());
        assertNull(result.getNextCursor());
    }

    @Test
    void timeline_shouldTriggerRebuildWhenFollowHomePageRequested() {
        when(feedInboxRebuildService.rebuildIfNeeded(1L)).thenReturn(true);
        when(feedTimelineRepository.pageInboxEntries(eq(1L), eq(null), eq(null), anyInt())).thenReturn(List.of());

        FeedTimelineVO result = feedService.timeline(1L, null, 20, "FOLLOW");

        assertNotNull(result);
        assertEquals(List.of(), result.getItems());
        verify(feedInboxRebuildService).rebuildIfNeeded(1L);
    }

    @Test
    void profile_shouldReturnEmptyWhenEitherSideBlocked() {
        when(relationRepository.findRelation(9L, 1L, 3))
                .thenReturn(RelationEntity.builder().sourceId(9L).targetId(1L).relationType(3).status(1).build());

        FeedTimelineVO result = feedService.profile(9L, 1L, null, 20);

        assertNotNull(result);
        assertEquals(List.of(), result.getItems());
        assertNull(result.getNextCursor());
    }

    @Test
    void timeline_recommendShouldCheckExpiredSessionAndReturnCursor() {
        String expiredCursor = FeedRecommendCursor.format("expired", 0);
        when(feedRecommendSessionRepository.sessionExists(1L, "expired")).thenReturn(false);
        when(feedRecommendSessionRepository.size(eq(1L), anyString())).thenReturn(0L, 1L);
        when(recommendationPort.recommend(1L, 1)).thenReturn(List.of(101L));
        when(feedRecommendSessionRepository.appendCandidates(eq(1L), anyString(), eq(List.of(101L)))).thenReturn(1);
        when(feedRecommendSessionRepository.range(eq(1L), anyString(), eq(0L), eq(0L))).thenReturn(List.of(101L));
        when(contentRepository.listPostsByIds(List.of(101L))).thenReturn(List.of(
                ContentPostEntity.builder().postId(101L).userId(88L).createTime(1000L).build()
        ));
        when(feedCardAssembleService.assemble(eq(1L), eq("RECOMMEND"), any(), eq(1)))
                .thenReturn(List.of(FeedItemVO.builder().postId(101L).build()));

        FeedTimelineVO result = assertDoesNotThrow(() -> feedService.timeline(1L, expiredCursor, 1, "RECOMMEND"));

        assertNotNull(result);
        assertNotNull(result.getNextCursor());
        verify(feedRecommendSessionRepository).sessionExists(1L, "expired");
    }

    @Test
    void timeline_popularShouldUseTrendingRecommenderAndReturnCursor() {
        when(recommendationPort.nonPersonalized("trending", 1L, 1, 0)).thenReturn(List.of(101L));
        when(contentRepository.listPostsByIds(List.of(101L))).thenReturn(List.of(
                ContentPostEntity.builder().postId(101L).userId(88L).createTime(1000L).build()
        ));
        when(feedCardAssembleService.assemble(eq(1L), eq("POPULAR"), any(), eq(1)))
                .thenReturn(List.of(FeedItemVO.builder().postId(101L).build()));

        FeedTimelineVO result = feedService.timeline(1L, null, 1, "POPULAR");

        assertEquals(1, result.getItems().size());
        assertEquals(FeedPopularCursor.format(1), result.getNextCursor());
        verify(recommendationPort).nonPersonalized("trending", 1L, 1, 0);
    }

    @Test
    void timeline_neighborsShouldUseSeedPostAndReturnCursor() {
        String cursor = FeedNeighborsCursor.format(500L, 0L);
        when(recommendationPort.itemToItem("similar", 500L, 1)).thenReturn(List.of(201L));
        when(contentRepository.listPostsByIds(List.of(201L))).thenReturn(List.of(
                ContentPostEntity.builder().postId(201L).userId(77L).createTime(2000L).build()
        ));
        when(feedCardAssembleService.assemble(eq(1L), eq("NEIGHBORS"), any(), eq(1)))
                .thenReturn(List.of(FeedItemVO.builder().postId(201L).build()));

        FeedTimelineVO result = feedService.timeline(1L, cursor, 1, "NEIGHBORS");

        assertEquals(1, result.getItems().size());
        assertEquals(FeedNeighborsCursor.format(500L, 1L), result.getNextCursor());
        verify(recommendationPort).itemToItem("similar", 500L, 1);
    }
}
