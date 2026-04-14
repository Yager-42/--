package cn.nexus.trigger.mq.consumer;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.infrastructure.mq.reliable.ReliableMqConsumerRecordService;
import cn.nexus.trigger.search.support.SearchIndexUpsertService;
import cn.nexus.types.event.search.PostChangedCdcEvent;
import cn.nexus.types.event.UserNicknameChangedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
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
        SearchIndexUpsertService upsertService = Mockito.mock(SearchIndexUpsertService.class);
        SearchIndexConsumer consumer = new SearchIndexConsumer(upsertService);
        when(upsertService.updateAuthorNickname(8L)).thenReturn(2L);

        UserNicknameChangedEvent event = new UserNicknameChangedEvent();
        event.setUserId(8L);
        event.setTsMs(1L);
        consumer.onUserNicknameChanged(event);

        verify(upsertService).updateAuthorNickname(8L);
    }

    @Test
    void onPostChanged_shouldSoftDeleteWhenPostNotIndexable() {
        SearchIndexUpsertService upsertService = Mockito.mock(SearchIndexUpsertService.class);
        ReliableMqConsumerRecordService recordService = mock(ReliableMqConsumerRecordService.class);
        when(recordService.start(Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenReturn(true);
        SearchIndexCdcConsumer consumer = new SearchIndexCdcConsumer(
                upsertService,
                recordService,
                new ObjectMapper());
        when(upsertService.upsertPost(101L))
                .thenReturn(new SearchIndexUpsertService.SearchIndexAction(101L, false, true, "NOT_INDEXABLE"));

        PostChangedCdcEvent event = new PostChangedCdcEvent();
        event.setPostId(101L);
        event.setTsMs(10L);
        event.setSource("canal");
        event.setTable("content_post");
        event.setEventId("mysql-bin.000001:1:101");
        consumer.onPostChanged(event);

        verify(upsertService).upsertPost(101L);
    }

    @Test
    void onPostChanged_shouldDelegateToUpsertService() {
        SearchIndexUpsertService upsertService = Mockito.mock(SearchIndexUpsertService.class);
        ReliableMqConsumerRecordService recordService = mock(ReliableMqConsumerRecordService.class);
        when(recordService.start(Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenReturn(true);
        SearchIndexCdcConsumer consumer = new SearchIndexCdcConsumer(
                upsertService,
                recordService,
                new ObjectMapper());
        when(upsertService.upsertPost(202L))
                .thenReturn(new SearchIndexUpsertService.SearchIndexAction(202L, true, false, null));

        PostChangedCdcEvent event = new PostChangedCdcEvent();
        event.setPostId(202L);
        event.setTsMs(30L);
        event.setSource("canal");
        event.setTable("content_post");
        event.setEventId("mysql-bin.000001:1:202");
        consumer.onPostChanged(event);

        verify(upsertService).upsertPost(202L);
    }

    @Test
    void onPostChanged_shouldRejectInvalidEvent() {
        SearchIndexCdcConsumer consumer = new SearchIndexCdcConsumer(
                mock(SearchIndexUpsertService.class),
                mock(ReliableMqConsumerRecordService.class),
                new ObjectMapper());

        PostChangedCdcEvent event = new PostChangedCdcEvent();
        event.setPostId(303L);

        assertThrows(AmqpRejectAndDontRequeueException.class, () -> consumer.onPostChanged(event));
    }

    private SearchIndexConsumer newConsumer() {
        return new SearchIndexConsumer(
                Mockito.mock(SearchIndexUpsertService.class));
    }
}
