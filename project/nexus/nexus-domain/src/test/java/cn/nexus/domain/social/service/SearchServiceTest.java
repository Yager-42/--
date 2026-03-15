package cn.nexus.domain.social.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.domain.social.adapter.port.ISearchEnginePort;
import cn.nexus.domain.social.adapter.repository.IFeedCardStatRepository;
import cn.nexus.domain.social.adapter.repository.IReactionRepository;
import cn.nexus.domain.social.model.valobj.FeedCardStatVO;
import cn.nexus.domain.social.model.valobj.ReactionTargetVO;
import cn.nexus.domain.social.model.valobj.SearchDocumentVO;
import cn.nexus.domain.social.model.valobj.SearchEngineQueryVO;
import cn.nexus.domain.social.model.valobj.SearchEngineResultVO;
import cn.nexus.domain.social.model.valobj.SearchResultVO;
import cn.nexus.domain.social.model.valobj.SearchSuggestVO;
import cn.nexus.types.exception.AppException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class SearchServiceTest {

    private ISearchEnginePort searchEnginePort;
    private IFeedCardStatRepository feedCardStatRepository;
    private IReactionRepository reactionRepository;
    private SearchService searchService;

    @BeforeEach
    void setUp() {
        searchEnginePort = Mockito.mock(ISearchEnginePort.class);
        feedCardStatRepository = Mockito.mock(IFeedCardStatRepository.class);
        reactionRepository = Mockito.mock(IReactionRepository.class);
        searchService = new SearchService(searchEnginePort, feedCardStatRepository, reactionRepository);
    }

    @Test
    void search_shouldNormalizeQueryAndLikedState() {
        SearchDocumentVO doc = SearchDocumentVO.builder()
                .contentId(101L)
                .title("title")
                .description("desc")
                .imgUrls(List.of("cover"))
                .tags(List.of("t1"))
                .authorNickname("nick")
                .authorAvatar("avatar")
                .likeCount(1L)
                .build();
        when(searchEnginePort.search(any())).thenReturn(SearchEngineResultVO.builder()
                .hits(List.of(SearchEngineResultVO.SearchHitVO.builder().source(doc).highlightBody("hit").build()))
                .hasMore(true)
                .nextAfter("after-1")
                .build());
        when(feedCardStatRepository.getBatch(List.of(101L))).thenReturn(Map.of(
                101L, FeedCardStatVO.builder().postId(101L).likeCount(8L).build()
        ));
        when(reactionRepository.batchExists(any(ReactionTargetVO.class), Mockito.eq(9L), Mockito.eq(List.of(101L))))
                .thenReturn(Set.of(101L));

        SearchResultVO result = searchService.search(9L, "  hello   world  ", 5, "a, b,a", "cursor");

        ArgumentCaptor<SearchEngineQueryVO> queryCaptor = ArgumentCaptor.forClass(SearchEngineQueryVO.class);
        verify(searchEnginePort).search(queryCaptor.capture());
        assertEquals("hello world", queryCaptor.getValue().getKeyword());
        assertEquals(List.of("a", "b"), queryCaptor.getValue().getTags());
        assertEquals(1, result.getItems().size());
        assertEquals(8L, result.getItems().get(0).getLikeCount());
        assertEquals(true, result.getItems().get(0).getLiked());
        assertEquals("after-1", result.getNextAfter());
        assertEquals(true, result.isHasMore());
    }

    @Test
    void search_shouldFallbackToReactionCountWhenStatCacheMiss() {
        SearchDocumentVO doc = SearchDocumentVO.builder()
                .contentId(101L)
                .title("title")
                .description("desc")
                .build();
        when(searchEnginePort.search(any())).thenReturn(SearchEngineResultVO.builder()
                .hits(List.of(SearchEngineResultVO.SearchHitVO.builder().source(doc).build()))
                .build());
        when(feedCardStatRepository.getBatch(List.of(101L))).thenReturn(Map.of());
        when(reactionRepository.getCount(any())).thenReturn(12L);

        SearchResultVO result = searchService.search(null, "keyword", null, null, null);

        assertEquals(12L, result.getItems().get(0).getLikeCount());
        verify(feedCardStatRepository).saveBatch(any());
    }

    @Test
    void suggest_shouldNormalizePrefixAndUseDefaultSize() {
        when(searchEnginePort.suggest("hello", 10)).thenReturn(List.of("hello1", "hello2"));

        SearchSuggestVO result = searchService.suggest("  hello  ", null);

        assertEquals(List.of("hello1", "hello2"), result.getItems());
    }

    @Test
    void search_shouldRejectInvalidSize() {
        assertThrows(AppException.class, () -> searchService.search(1L, "ok", 0, null, null));
    }
}
