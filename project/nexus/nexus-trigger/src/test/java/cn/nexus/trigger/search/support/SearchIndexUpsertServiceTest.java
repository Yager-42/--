package cn.nexus.trigger.search.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.domain.social.adapter.port.ISearchEnginePort;
import cn.nexus.domain.social.adapter.repository.IContentRepository;
import cn.nexus.domain.social.adapter.repository.IUserBaseRepository;
import cn.nexus.domain.social.model.entity.ContentPostEntity;
import cn.nexus.domain.social.model.valobj.SearchDocumentVO;
import cn.nexus.domain.social.model.valobj.UserBriefVO;
import cn.nexus.types.enums.ContentPostStatusEnumVO;
import cn.nexus.types.enums.ContentPostVisibilityEnumVO;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class SearchIndexUpsertServiceTest {

    @Test
    void upsertPost_shouldAssembleAndUpsertIndexablePost() {
        ISearchEnginePort searchEnginePort = Mockito.mock(ISearchEnginePort.class);
        IContentRepository contentRepository = Mockito.mock(IContentRepository.class);
        IUserBaseRepository userBaseRepository = Mockito.mock(IUserBaseRepository.class);
        SearchDocumentAssembler assembler = Mockito.mock(SearchDocumentAssembler.class);
        SearchIndexUpsertService service = new SearchIndexUpsertService(
                searchEnginePort, contentRepository, userBaseRepository, assembler);

        ContentPostEntity post = post(9L, 7L);
        when(contentRepository.findPostBypassCache(9L)).thenReturn(post);
        when(userBaseRepository.listByUserIds(List.of(7L)))
                .thenReturn(List.of(UserBriefVO.builder().userId(7L).nickname("author").avatarUrl("avatar").build()));
        SearchDocumentVO doc = SearchDocumentVO.builder().contentId(9L).build();
        when(assembler.assemble(eq(9L), eq(7L), eq("title"), eq("summary"), eq("body"),
                eq(List.of("tag")), eq("avatar"), eq("author"), eq(123L), eq("m1")))
                .thenReturn(doc);

        SearchIndexUpsertService.SearchIndexAction action = service.upsertPost(9L);

        assertThat(action.upserted()).isTrue();
        verify(searchEnginePort).upsert(doc);
    }

    @Test
    void upsertPost_shouldSoftDeleteWhenPostNotIndexable() {
        ISearchEnginePort searchEnginePort = Mockito.mock(ISearchEnginePort.class);
        IContentRepository contentRepository = Mockito.mock(IContentRepository.class);
        SearchIndexUpsertService service = new SearchIndexUpsertService(
                searchEnginePort,
                contentRepository,
                Mockito.mock(IUserBaseRepository.class),
                Mockito.mock(SearchDocumentAssembler.class));
        when(contentRepository.findPostBypassCache(15L)).thenReturn(ContentPostEntity.builder()
                .postId(15L)
                .userId(3L)
                .title("")
                .status(ContentPostStatusEnumVO.PUBLISHED.getCode())
                .visibility(ContentPostVisibilityEnumVO.PUBLIC.getCode())
                .publishTime(1L)
                .build());

        SearchIndexUpsertService.SearchIndexAction action = service.upsertPost(15L);

        assertThat(action.softDeleted()).isTrue();
        assertThat(action.reason()).isEqualTo("NOT_INDEXABLE");
        verify(searchEnginePort).softDelete(15L);
    }

    @Test
    void updateAuthorNickname_shouldTrimNicknameBeforeUpdate() {
        ISearchEnginePort searchEnginePort = Mockito.mock(ISearchEnginePort.class);
        IUserBaseRepository userBaseRepository = Mockito.mock(IUserBaseRepository.class);
        SearchIndexUpsertService service = new SearchIndexUpsertService(
                searchEnginePort,
                Mockito.mock(IContentRepository.class),
                userBaseRepository,
                Mockito.mock(SearchDocumentAssembler.class));
        when(userBaseRepository.listByUserIds(List.of(8L)))
                .thenReturn(List.of(UserBriefVO.builder().userId(8L).nickname("  newer  ").build()));
        when(searchEnginePort.updateAuthorNickname(8L, "newer")).thenReturn(3L);

        long affected = service.updateAuthorNickname(8L);

        assertThat(affected).isEqualTo(3L);
        verify(searchEnginePort).updateAuthorNickname(8L, "newer");
    }

    @Test
    void upsertPost_shouldUpsertWithNullAuthorWhenUserMissing() {
        ISearchEnginePort searchEnginePort = Mockito.mock(ISearchEnginePort.class);
        IContentRepository contentRepository = Mockito.mock(IContentRepository.class);
        IUserBaseRepository userBaseRepository = Mockito.mock(IUserBaseRepository.class);
        SearchDocumentAssembler assembler = Mockito.mock(SearchDocumentAssembler.class);
        SearchIndexUpsertService service = new SearchIndexUpsertService(
                searchEnginePort, contentRepository, userBaseRepository, assembler);

        ContentPostEntity post = post(19L, 7L);
        when(contentRepository.findPostBypassCache(19L)).thenReturn(post);
        when(userBaseRepository.listByUserIds(List.of(7L))).thenReturn(List.of());
        SearchDocumentVO doc = SearchDocumentVO.builder().contentId(19L).build();
        when(assembler.assemble(eq(19L), eq(7L), eq("title"), eq("summary"), eq("body"),
                eq(List.of("tag")), eq(null), eq(null), eq(123L), eq("m1")))
                .thenReturn(doc);

        SearchIndexUpsertService.SearchIndexAction action = service.upsertPost(19L);

        assertThat(action.upserted()).isTrue();
        verify(searchEnginePort).upsert(doc);
    }

    private ContentPostEntity post(Long postId, Long userId) {
        return ContentPostEntity.builder()
                .postId(postId)
                .userId(userId)
                .title("title")
                .summary("summary")
                .contentText("body")
                .postTypes(List.of("tag"))
                .mediaInfo("m1")
                .status(ContentPostStatusEnumVO.PUBLISHED.getCode())
                .visibility(ContentPostVisibilityEnumVO.PUBLIC.getCode())
                .publishTime(123L)
                .build();
    }
}
