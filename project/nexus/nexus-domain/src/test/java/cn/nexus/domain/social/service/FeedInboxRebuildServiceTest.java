package cn.nexus.domain.social.service;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.domain.social.adapter.port.IRelationAdjacencyCachePort;
import cn.nexus.domain.social.adapter.repository.IContentRepository;
import cn.nexus.domain.social.adapter.repository.IFeedAuthorCategoryRepository;
import cn.nexus.domain.social.adapter.repository.IFeedTimelineRepository;
import cn.nexus.domain.social.model.entity.ContentPostEntity;
import cn.nexus.domain.social.model.valobj.ContentPostPageVO;
import cn.nexus.domain.social.model.valobj.FeedAuthorCategoryEnumVO;
import cn.nexus.domain.social.model.valobj.FeedInboxEntryVO;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

class FeedInboxRebuildServiceTest {

    private IRelationAdjacencyCachePort relationAdjacencyCachePort;
    private IContentRepository contentRepository;
    private IFeedAuthorCategoryRepository feedAuthorCategoryRepository;
    private IFeedTimelineRepository feedTimelineRepository;
    private FeedInboxRebuildService feedInboxRebuildService;

    @BeforeEach
    void setUp() {
        relationAdjacencyCachePort = Mockito.mock(IRelationAdjacencyCachePort.class);
        contentRepository = Mockito.mock(IContentRepository.class);
        feedAuthorCategoryRepository = Mockito.mock(IFeedAuthorCategoryRepository.class);
        feedTimelineRepository = Mockito.mock(IFeedTimelineRepository.class);
        feedInboxRebuildService = new FeedInboxRebuildService(
                relationAdjacencyCachePort,
                contentRepository,
                feedAuthorCategoryRepository,
                feedTimelineRepository
        );
        ReflectionTestUtils.setField(feedInboxRebuildService, "perFollowingLimit", 20);
        ReflectionTestUtils.setField(feedInboxRebuildService, "inboxSize", 200);
        ReflectionTestUtils.setField(feedInboxRebuildService, "maxFollowings", 2000);
    }

    @Test
    void rebuildIfNeeded_shouldExcludeBigVAuthorsFromInboxRebuild() {
        when(feedTimelineRepository.inboxExists(1L)).thenReturn(false);
        when(relationAdjacencyCachePort.listFollowing(1L, 2000)).thenReturn(List.of(200L, 300L));
        when(feedAuthorCategoryRepository.batchGetCategory(List.of(200L, 300L))).thenReturn(Map.of(
                200L, FeedAuthorCategoryEnumVO.NORMAL.getCode(),
                300L, FeedAuthorCategoryEnumVO.BIGV.getCode()
        ));
        when(contentRepository.listUserPosts(1L, null, 20)).thenReturn(ContentPostPageVO.builder()
                .posts(List.of(ContentPostEntity.builder().postId(10L).createTime(1000L).build()))
                .build());
        when(contentRepository.listUserPosts(200L, null, 20)).thenReturn(ContentPostPageVO.builder()
                .posts(List.of(ContentPostEntity.builder().postId(20L).createTime(900L).build()))
                .build());

        feedInboxRebuildService.rebuildIfNeeded(1L);

        ArgumentCaptor<List<FeedInboxEntryVO>> captor = ArgumentCaptor.forClass(List.class);
        verify(feedTimelineRepository).replaceInbox(Mockito.eq(1L), captor.capture());
        List<FeedInboxEntryVO> entries = captor.getValue();
        org.junit.jupiter.api.Assertions.assertEquals(List.of(10L, 20L), entries.stream().map(FeedInboxEntryVO::getPostId).toList());
        verify(contentRepository, never()).listUserPosts(300L, null, 20);
    }
}
