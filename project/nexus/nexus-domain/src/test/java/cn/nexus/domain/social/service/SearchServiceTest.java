package cn.nexus.domain.social.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import cn.nexus.domain.social.adapter.port.IReactionCachePort;
import cn.nexus.domain.social.adapter.port.ISearchEnginePort;
import cn.nexus.domain.social.model.valobj.ReactionTargetVO;
import cn.nexus.domain.social.model.valobj.SearchDocumentVO;
import cn.nexus.domain.social.model.valobj.SearchEngineQueryVO;
import cn.nexus.domain.social.model.valobj.SearchEngineResultVO;
import cn.nexus.domain.social.model.valobj.SearchResultVO;
import cn.nexus.domain.social.model.valobj.SearchSuggestVO;
import cn.nexus.types.exception.AppException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class SearchServiceTest {

    private ISearchEnginePort searchEnginePort;
    private IReactionCachePort reactionCachePort;
    private SearchService searchService;

    @BeforeEach
    void setUp() {
        searchEnginePort = Mockito.mock(ISearchEnginePort.class);
        reactionCachePort = Mockito.mock(IReactionCachePort.class);
        searchService = new SearchService(searchEnginePort, reactionCachePort);
    }

    @Test
    void search_shouldNormalizeQueryAndLikedState() {
        SearchDocumentVO doc = SearchDocumentVO.builder()
                .contentId(101L)
                .authorId(77L)
                .title("title")
                .description("desc")
                .imgUrls(List.of("cover"))
                .tags(List.of("t1"))
                .authorNickname("nick")
                .authorAvatar("avatar")
                .build();
        when(searchEnginePort.search(any())).thenReturn(SearchEngineResultVO.builder()
                .hits(List.of(SearchEngineResultVO.SearchHitVO.builder().source(doc).highlightBody("hit").build()))
                .hasMore(true)
                .nextAfter("after-1")
                .build());
        when(reactionCachePort.getState(Mockito.eq(9L), any(ReactionTargetVO.class))).thenReturn(true);

        SearchResultVO result = searchService.search(9L, "  hello   world  ", 5, "a, b,a", "cursor");

        ArgumentCaptor<SearchEngineQueryVO> queryCaptor = ArgumentCaptor.forClass(SearchEngineQueryVO.class);
        verify(searchEnginePort).search(queryCaptor.capture());
        assertEquals("hello world", queryCaptor.getValue().getKeyword());
        assertEquals(List.of("a", "b"), queryCaptor.getValue().getTags());
        assertEquals(1, result.getItems().size());
        assertEquals("77", result.getItems().get(0).getAuthorId());
        assertEquals(true, result.getItems().get(0).getLiked());
        assertEquals("after-1", result.getNextAfter());
        assertEquals(true, result.isHasMore());
    }

    @Test
    void search_shouldNotExposeCountFieldsFromSearchDocument() {
        SearchDocumentVO doc = SearchDocumentVO.builder()
                .contentId(101L)
                .title("title")
                .description("desc")
                .build();
        when(searchEnginePort.search(any())).thenReturn(SearchEngineResultVO.builder()
                .hits(List.of(SearchEngineResultVO.SearchHitVO.builder().source(doc).build()))
                .build());

        SearchResultVO result = searchService.search(null, "keyword", null, null, null);

        assertEquals(false, result.getItems().get(0).getLiked());
        verify(reactionCachePort, never()).getState(any(), any());
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
