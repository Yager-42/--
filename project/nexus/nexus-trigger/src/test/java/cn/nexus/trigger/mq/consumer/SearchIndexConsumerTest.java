package cn.nexus.trigger.mq.consumer;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.domain.social.adapter.port.ISearchEnginePort;
import cn.nexus.trigger.search.support.SearchIndexUpsertService;
import cn.nexus.types.event.PostDeletedEvent;
import cn.nexus.types.event.PostPublishedEvent;
import cn.nexus.types.event.PostUpdatedEvent;
import cn.nexus.types.event.UserNicknameChangedEvent;
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
        SearchIndexUpsertService upsertService = Mockito.mock(SearchIndexUpsertService.class);
        SearchIndexConsumer consumer = new SearchIndexConsumer(searchEnginePort, upsertService);
        when(upsertService.updateAuthorNickname(8L)).thenReturn(2L);

        UserNicknameChangedEvent event = new UserNicknameChangedEvent();
        event.setUserId(8L);
        event.setTsMs(1L);
        consumer.onUserNicknameChanged(event);

        verify(upsertService).updateAuthorNickname(8L);
    }

    @Test
    void onPostPublished_shouldSoftDeleteWhenPostNotIndexable() {
        ISearchEnginePort searchEnginePort = Mockito.mock(ISearchEnginePort.class);
        SearchIndexUpsertService upsertService = Mockito.mock(SearchIndexUpsertService.class);
        SearchIndexConsumer consumer = new SearchIndexConsumer(searchEnginePort, upsertService);
        when(upsertService.upsertPost(101L))
                .thenReturn(new SearchIndexUpsertService.SearchIndexAction(101L, false, true, "NOT_INDEXABLE"));

        PostPublishedEvent event = new PostPublishedEvent();
        event.setPostId(101L);
        event.setAuthorId(9L);
        event.setPublishTimeMs(10L);
        consumer.onPostPublished(event);

        verify(upsertService).upsertPost(101L);
    }

    @Test
    void onPostUpdated_shouldDelegateToUpsertService() {
        ISearchEnginePort searchEnginePort = Mockito.mock(ISearchEnginePort.class);
        SearchIndexUpsertService upsertService = Mockito.mock(SearchIndexUpsertService.class);
        SearchIndexConsumer consumer = new SearchIndexConsumer(searchEnginePort, upsertService);
        when(upsertService.upsertPost(202L))
                .thenReturn(new SearchIndexUpsertService.SearchIndexAction(202L, true, false, null));

        PostUpdatedEvent event = new PostUpdatedEvent();
        event.setPostId(202L);
        event.setOperatorId(20L);
        event.setTsMs(30L);
        consumer.onPostUpdated(event);

        verify(upsertService).upsertPost(202L);
    }

    @Test
    void onPostDeleted_shouldSoftDeleteWhenEventValid() {
        ISearchEnginePort searchEnginePort = Mockito.mock(ISearchEnginePort.class);
        SearchIndexConsumer consumer = new SearchIndexConsumer(
                searchEnginePort,
                Mockito.mock(SearchIndexUpsertService.class));

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
                Mockito.mock(SearchIndexUpsertService.class));
    }
}
