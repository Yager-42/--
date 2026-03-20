package cn.nexus.trigger.mq.consumer;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.domain.social.adapter.port.ISearchEnginePort;
import cn.nexus.domain.social.adapter.repository.IContentRepository;
import cn.nexus.domain.social.adapter.repository.IReactionRepository;
import cn.nexus.domain.social.adapter.repository.IUserBaseRepository;
import cn.nexus.domain.social.model.entity.ContentPostEntity;
import cn.nexus.domain.social.model.valobj.ReactionTargetVO;
import cn.nexus.domain.social.model.valobj.SearchDocumentVO;
import cn.nexus.domain.social.model.valobj.UserBriefVO;
import cn.nexus.trigger.search.support.SearchDocumentAssembler;
import cn.nexus.types.enums.ContentPostStatusEnumVO;
import cn.nexus.types.enums.ContentPostVisibilityEnumVO;
import cn.nexus.types.event.PostDeletedEvent;
import cn.nexus.types.event.PostPublishedEvent;
import cn.nexus.types.event.PostUpdatedEvent;
import cn.nexus.types.event.UserNicknameChangedEvent;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;

class SearchIndexConsumerTest {

    @Test
    void onUserNicknameChanged_shouldRejectInvalidEvent() {
        SearchIndexConsumer consumer = newConsumer();

        UserNicknameChangedEvent event = new UserNicknameChangedEvent();
        event.setTsMs(1L);

        assertThrows(AmqpRejectAndDontRequeueException.class, () -> consumer.onUserNicknameChanged(event));
    }

    @Test
    void onUserNicknameChanged_shouldReloadLatestNicknameFromUserBase() {
        ISearchEnginePort searchEnginePort = Mockito.mock(ISearchEnginePort.class);
        IUserBaseRepository userBaseRepository = Mockito.mock(IUserBaseRepository.class);
        SearchIndexConsumer consumer = new SearchIndexConsumer(
                searchEnginePort,
                Mockito.mock(IContentRepository.class),
                userBaseRepository,
                Mockito.mock(IReactionRepository.class),
                Mockito.mock(SearchDocumentAssembler.class));
        when(userBaseRepository.listByUserIds(List.of(8L)))
                .thenReturn(List.of(UserBriefVO.builder().userId(8L).nickname("  new-name  ").build()));

        UserNicknameChangedEvent event = new UserNicknameChangedEvent();
        event.setUserId(8L);
        event.setTsMs(1L);
        consumer.onUserNicknameChanged(event);

        verify(searchEnginePort).updateAuthorNickname(8L, "new-name");
    }

    @Test
    void onPostPublished_shouldSoftDeleteWhenPostNotIndexable() {
        ISearchEnginePort searchEnginePort = Mockito.mock(ISearchEnginePort.class);
        IContentRepository contentRepository = Mockito.mock(IContentRepository.class);
        SearchIndexConsumer consumer = new SearchIndexConsumer(
                searchEnginePort,
                contentRepository,
                Mockito.mock(IUserBaseRepository.class),
                Mockito.mock(IReactionRepository.class),
                Mockito.mock(SearchDocumentAssembler.class));
        when(contentRepository.findPost(101L)).thenReturn(ContentPostEntity.builder()
                .postId(101L)
                .userId(9L)
                .status(ContentPostStatusEnumVO.PUBLISHED.getCode())
                .visibility(ContentPostVisibilityEnumVO.PUBLIC.getCode())
                .title("")
                .publishTime(10L)
                .build());

        PostPublishedEvent event = new PostPublishedEvent();
        event.setPostId(101L);
        event.setAuthorId(9L);
        event.setPublishTimeMs(10L);
        consumer.onPostPublished(event);

        verify(searchEnginePort).softDelete(101L);
        verify(searchEnginePort, never()).upsert(any());
    }

    @Test
    void onPostUpdated_shouldAssembleDocumentAndClampLikeCount() {
        ISearchEnginePort searchEnginePort = Mockito.mock(ISearchEnginePort.class);
        IContentRepository contentRepository = Mockito.mock(IContentRepository.class);
        IUserBaseRepository userBaseRepository = Mockito.mock(IUserBaseRepository.class);
        IReactionRepository reactionRepository = Mockito.mock(IReactionRepository.class);
        SearchDocumentAssembler assembler = Mockito.mock(SearchDocumentAssembler.class);
        SearchIndexConsumer consumer = new SearchIndexConsumer(
                searchEnginePort,
                contentRepository,
                userBaseRepository,
                reactionRepository,
                assembler);

        ContentPostEntity post = ContentPostEntity.builder()
                .postId(202L)
                .userId(20L)
                .title("title")
                .summary("summary")
                .contentText("body")
                .postTypes(List.of("tech"))
                .mediaInfo("m1")
                .status(ContentPostStatusEnumVO.PUBLISHED.getCode())
                .visibility(ContentPostVisibilityEnumVO.PUBLIC.getCode())
                .publishTime(20L)
                .build();
        when(contentRepository.findPost(202L)).thenReturn(post);
        when(userBaseRepository.listByUserIds(List.of(20L)))
                .thenReturn(List.of(UserBriefVO.builder().userId(20L).nickname("author").avatarUrl("avatar").build()));
        when(reactionRepository.getCount(any(ReactionTargetVO.class))).thenReturn(-5L);
        SearchDocumentVO document = SearchDocumentVO.builder().contentId(202L).authorNickname("author").build();
        when(assembler.assemble(eq(202L), eq(20L), eq("title"), eq("summary"), eq("body"),
                eq(List.of("tech")), eq("avatar"), eq("author"), eq(20L), eq(0L), eq("m1")))
                .thenReturn(document);

        PostUpdatedEvent event = new PostUpdatedEvent();
        event.setPostId(202L);
        event.setOperatorId(20L);
        event.setTsMs(30L);
        consumer.onPostUpdated(event);

        verify(assembler).assemble(eq(202L), eq(20L), eq("title"), eq("summary"), eq("body"),
                eq(List.of("tech")), eq("avatar"), eq("author"), eq(20L), eq(0L), eq("m1"));
        verify(searchEnginePort).upsert(document);
    }

    @Test
    void onPostDeleted_shouldSoftDeleteWhenEventValid() {
        ISearchEnginePort searchEnginePort = Mockito.mock(ISearchEnginePort.class);
        SearchIndexConsumer consumer = new SearchIndexConsumer(
                searchEnginePort,
                Mockito.mock(IContentRepository.class),
                Mockito.mock(IUserBaseRepository.class),
                Mockito.mock(IReactionRepository.class),
                Mockito.mock(SearchDocumentAssembler.class));

        PostDeletedEvent event = new PostDeletedEvent();
        event.setPostId(303L);
        event.setOperatorId(3L);
        event.setTsMs(30L);
        consumer.onPostDeleted(event);

        verify(searchEnginePort).softDelete(303L);
    }

    private SearchIndexConsumer newConsumer() {
        return new SearchIndexConsumer(
                Mockito.mock(ISearchEnginePort.class),
                Mockito.mock(IContentRepository.class),
                Mockito.mock(IUserBaseRepository.class),
                Mockito.mock(IReactionRepository.class),
                Mockito.mock(SearchDocumentAssembler.class));
    }
}
