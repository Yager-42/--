package cn.nexus.domain.social.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.domain.social.adapter.repository.IFeedAuthorTimelineRepository;
import cn.nexus.domain.social.adapter.repository.IFeedTimelineRepository;
import cn.nexus.domain.social.model.valobj.FeedInboxEntryVO;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

class FeedFollowCompensationServiceTest {

    private IFeedTimelineRepository feedTimelineRepository;
    private IFeedAuthorTimelineRepository feedAuthorTimelineRepository;
    private FeedFollowCompensationService service;

    @BeforeEach
    void setUp() {
        feedTimelineRepository = Mockito.mock(IFeedTimelineRepository.class);
        feedAuthorTimelineRepository = Mockito.mock(IFeedAuthorTimelineRepository.class);
        service = new FeedFollowCompensationService(feedTimelineRepository, feedAuthorTimelineRepository);
        ReflectionTestUtils.setField(service, "recentPosts", 2);
    }

    @Test
    void onFollow_shouldReadFolloweeAuthorTimelineAndWriteOnlineFollowerInbox() {
        when(feedTimelineRepository.inboxExists(100L)).thenReturn(true);
        when(feedAuthorTimelineRepository.pageTimeline(200L, null, null, 2)).thenReturn(List.of(
                entry(10L, 1000L),
                entry(11L, 900L)
        ));

        service.onFollow(100L, 200L);

        verify(feedTimelineRepository).addToInbox(100L, 10L, 1000L);
        verify(feedTimelineRepository).addToInbox(100L, 11L, 900L);
    }

    @Test
    void onFollow_shouldNotCreateInboxForOfflineFollower() {
        when(feedTimelineRepository.inboxExists(100L)).thenReturn(false);

        service.onFollow(100L, 200L);

        verify(feedAuthorTimelineRepository, never()).pageTimeline(anyLong(), any(), any(), anyInt());
        verify(feedTimelineRepository, never()).addToInbox(anyLong(), anyLong(), anyLong());
    }

    @Test
    void onFollow_shouldSkipNullAndSelfFollowInputs() {
        service.onFollow(null, 200L);
        service.onFollow(100L, null);
        service.onFollow(100L, 100L);

        verify(feedTimelineRepository, never()).inboxExists(anyLong());
        verify(feedAuthorTimelineRepository, never()).pageTimeline(anyLong(), any(), any(), anyInt());
        verify(feedTimelineRepository, never()).addToInbox(anyLong(), anyLong(), anyLong());
    }

    @Test
    void onFollow_shouldSkipEmptyAuthorTimeline() {
        when(feedTimelineRepository.inboxExists(100L)).thenReturn(true);
        when(feedAuthorTimelineRepository.pageTimeline(200L, null, null, 2)).thenReturn(List.of());

        service.onFollow(100L, 200L);

        verify(feedTimelineRepository, never()).addToInbox(anyLong(), anyLong(), anyLong());
    }

    @Test
    void onFollow_shouldSkipMalformedTimelineEntries() {
        when(feedTimelineRepository.inboxExists(100L)).thenReturn(true);
        when(feedAuthorTimelineRepository.pageTimeline(200L, null, null, 2)).thenReturn(List.of(
                FeedInboxEntryVO.builder().postId(null).publishTimeMs(1000L).build(),
                FeedInboxEntryVO.builder().postId(11L).publishTimeMs(null).build()
        ));

        service.onFollow(100L, 200L);

        verify(feedTimelineRepository, never()).addToInbox(anyLong(), anyLong(), anyLong());
    }

    @Test
    void onUnfollow_shouldBeNoop() {
        service.onUnfollow(100L, 200L);
        service.onUnfollow(null, 200L);
        service.onUnfollow(100L, null);
        service.onUnfollow(100L, 100L);

        verify(feedTimelineRepository, never()).inboxExists(anyLong());
        verify(feedTimelineRepository, never()).removeFromInbox(anyLong(), anyLong());
        verify(feedTimelineRepository, never()).addToInbox(anyLong(), anyLong(), anyLong());
        verify(feedAuthorTimelineRepository, never()).pageTimeline(anyLong(), any(), any(), anyInt());
    }

    private FeedInboxEntryVO entry(Long postId, Long publishTimeMs) {
        return FeedInboxEntryVO.builder()
                .postId(postId)
                .publishTimeMs(publishTimeMs)
                .build();
    }
}
