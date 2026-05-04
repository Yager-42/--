package cn.nexus.domain.social.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.domain.social.adapter.port.IRelationAdjacencyCachePort;
import cn.nexus.domain.social.adapter.repository.IFeedAuthorTimelineRepository;
import cn.nexus.domain.social.adapter.repository.IFeedTimelineRepository;
import cn.nexus.domain.social.model.valobj.FeedInboxEntryVO;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

class FeedInboxActivationServiceTest {

    private IRelationAdjacencyCachePort relationAdjacencyCachePort;
    private IFeedAuthorTimelineRepository feedAuthorTimelineRepository;
    private IFeedTimelineRepository feedTimelineRepository;
    private FeedInboxActivationService feedInboxActivationService;

    @BeforeEach
    void setUp() {
        relationAdjacencyCachePort = Mockito.mock(IRelationAdjacencyCachePort.class);
        feedAuthorTimelineRepository = Mockito.mock(IFeedAuthorTimelineRepository.class);
        feedTimelineRepository = Mockito.mock(IFeedTimelineRepository.class);
        feedInboxActivationService = new FeedInboxActivationService(
                relationAdjacencyCachePort,
                feedAuthorTimelineRepository,
                feedTimelineRepository
        );
        ReflectionTestUtils.setField(feedInboxActivationService, "perFollowingLimit", 20);
        ReflectionTestUtils.setField(feedInboxActivationService, "inboxSize", 3);
        ReflectionTestUtils.setField(feedInboxActivationService, "maxFollowings", 2000);
    }

    @Test
    void activateIfNeeded_shouldSkipWhenInboxExists() {
        when(feedTimelineRepository.inboxExists(1L)).thenReturn(true);

        boolean activated = feedInboxActivationService.activateIfNeeded(1L);

        assertFalse(activated);
        verify(relationAdjacencyCachePort, never()).listFollowing(anyLong(), anyInt());
        verify(feedAuthorTimelineRepository, never()).pageTimeline(anyLong(), anyLong(), anyLong(), anyInt());
        verify(feedTimelineRepository, never()).addToInbox(anyLong(), anyLong(), anyLong());
    }

    @Test
    void activateIfNeeded_shouldReadSelfPlusNormalAndBigVFollowingsFromAuthorTimeline() {
        when(feedTimelineRepository.inboxExists(1L)).thenReturn(false);
        when(relationAdjacencyCachePort.listFollowing(1L, 2000)).thenReturn(List.of(200L, 300L));
        when(feedAuthorTimelineRepository.pageTimeline(1L, null, null, 20)).thenReturn(List.of(entry(10L, 1000L)));
        when(feedAuthorTimelineRepository.pageTimeline(200L, null, null, 20)).thenReturn(List.of(entry(20L, 900L)));
        when(feedAuthorTimelineRepository.pageTimeline(300L, null, null, 20)).thenReturn(List.of(entry(30L, 1100L)));

        boolean activated = feedInboxActivationService.activateIfNeeded(1L);

        assertTrue(activated);
        verify(feedAuthorTimelineRepository).pageTimeline(1L, null, null, 20);
        verify(feedAuthorTimelineRepository).pageTimeline(200L, null, null, 20);
        verify(feedAuthorTimelineRepository).pageTimeline(300L, null, null, 20);
        verify(feedTimelineRepository).addToInbox(1L, 30L, 1100L);
        verify(feedTimelineRepository).addToInbox(1L, 10L, 1000L);
        verify(feedTimelineRepository).addToInbox(1L, 20L, 900L);
    }

    @Test
    void activateIfNeeded_shouldDeduplicateSortByMaxIdAndWriteOnlyTopEntries() {
        when(feedTimelineRepository.inboxExists(1L)).thenReturn(false);
        when(relationAdjacencyCachePort.listFollowing(1L, 2000)).thenReturn(List.of(200L, 300L));
        when(feedAuthorTimelineRepository.pageTimeline(1L, null, null, 20)).thenReturn(List.of(
                entry(10L, 1000L),
                entry(11L, 1000L)
        ));
        when(feedAuthorTimelineRepository.pageTimeline(200L, null, null, 20)).thenReturn(List.of(
                entry(20L, 1100L),
                entry(10L, 1200L)
        ));
        when(feedAuthorTimelineRepository.pageTimeline(300L, null, null, 20)).thenReturn(List.of(
                entry(30L, 1000L),
                entry(40L, 900L)
        ));

        feedInboxActivationService.activateIfNeeded(1L);

        org.mockito.InOrder inOrder = Mockito.inOrder(feedTimelineRepository);
        inOrder.verify(feedTimelineRepository).inboxExists(1L);
        inOrder.verify(feedTimelineRepository).addToInbox(1L, 10L, 1200L);
        inOrder.verify(feedTimelineRepository).addToInbox(1L, 20L, 1100L);
        inOrder.verify(feedTimelineRepository).addToInbox(1L, 30L, 1000L);
        verify(feedTimelineRepository, never()).addToInbox(1L, 11L, 1000L);
        verify(feedTimelineRepository, never()).addToInbox(1L, 40L, 900L);
    }

    @Test
    void activateIfNeeded_shouldNotWriteWhenMergedResultIsEmpty() {
        when(feedTimelineRepository.inboxExists(1L)).thenReturn(false);
        when(relationAdjacencyCachePort.listFollowing(1L, 2000)).thenReturn(List.of(200L));
        when(feedAuthorTimelineRepository.pageTimeline(1L, null, null, 20)).thenReturn(List.of());
        when(feedAuthorTimelineRepository.pageTimeline(200L, null, null, 20)).thenReturn(List.of());

        boolean activated = feedInboxActivationService.activateIfNeeded(1L);

        assertTrue(activated);
        verify(feedTimelineRepository, never()).addToInbox(anyLong(), anyLong(), anyLong());
        verify(feedTimelineRepository, never()).removeFromInbox(anyLong(), anyLong());
    }

    @Test
    void activateIfNeeded_shouldNotUseContentRepositoryOrReplaceInboxApi() {
        for (java.lang.reflect.Field field : FeedInboxActivationService.class.getDeclaredFields()) {
            org.junit.jupiter.api.Assertions.assertNotEquals("contentRepository", field.getName());
        }
        try {
            IFeedTimelineRepository.class.getDeclaredMethod("replaceInbox", Long.class, List.class);
            org.junit.jupiter.api.Assertions.fail("Unexpected replaceInbox API exists");
        } catch (NoSuchMethodException expected) {
            // expected
        }
    }

    private FeedInboxEntryVO entry(Long postId, Long publishTimeMs) {
        return FeedInboxEntryVO.builder().postId(postId).publishTimeMs(publishTimeMs).build();
    }
}
