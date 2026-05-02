package cn.nexus.trigger.mq.consumer;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.infrastructure.mq.reliable.ReliableMqConsumerRecordService;
import cn.nexus.infrastructure.mq.reliable.ReliableMqConsumerRecordService.StartResult;
import cn.nexus.infrastructure.mq.reliable.exception.ReliableMqPermanentFailureException;
import cn.nexus.trigger.search.support.SearchIndexUpsertService;
import cn.nexus.types.event.search.PostChangedCdcEvent;
import cn.nexus.types.event.UserNicknameChangedEvent;
import org.aspectj.lang.ProceedingJoinPoint;
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
        SearchIndexCdcConsumer consumer = new SearchIndexCdcConsumer(upsertService);
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
        SearchIndexCdcConsumer consumer = new SearchIndexCdcConsumer(upsertService);
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
        SearchIndexCdcConsumer consumer = new SearchIndexCdcConsumer(mock(SearchIndexUpsertService.class));

        PostChangedCdcEvent event = new PostChangedCdcEvent();
        event.setPostId(303L);

        assertThrows(ReliableMqPermanentFailureException.class, () -> consumer.onPostChanged(event));
    }

    @Test
    void onPostChanged_duplicateDoneThroughReliableAspectSkipsBusinessDependency() throws Throwable {
        SearchIndexCdcFixture fixture = new SearchIndexCdcFixture();
        PostChangedCdcEvent event = postChangedEvent("cdc-evt-1", 501L);
        when(fixture.consumerRecordService.startManual(Mockito.eq("cdc-evt-1"),
                Mockito.eq("search-index-cdc"), Mockito.anyString()))
                .thenReturn(StartResult.DUPLICATE_DONE);

        fixture.invokeThroughAspect(event);

        verify(fixture.upsertService, never()).upsertPost(Mockito.anyLong());
        verify(fixture.consumerRecordService, never()).markDone(Mockito.any(), Mockito.any());
    }

    @Test
    void onPostChanged_successThroughReliableAspectMarksDone() throws Throwable {
        SearchIndexCdcFixture fixture = new SearchIndexCdcFixture();
        PostChangedCdcEvent event = postChangedEvent("cdc-evt-2", 502L);
        when(fixture.consumerRecordService.startManual(Mockito.eq("cdc-evt-2"),
                Mockito.eq("search-index-cdc"), Mockito.anyString()))
                .thenReturn(StartResult.STARTED);
        when(fixture.upsertService.upsertPost(502L))
                .thenReturn(new SearchIndexUpsertService.SearchIndexAction(502L, true, false, null));

        fixture.invokeThroughAspect(event);

        verify(fixture.upsertService).upsertPost(502L);
        verify(fixture.consumerRecordService).markDone("cdc-evt-2", "search-index-cdc");
    }

    @Test
    void onPostChanged_businessFailureThroughReliableAspectMarksFailAndRethrows() throws Throwable {
        SearchIndexCdcFixture fixture = new SearchIndexCdcFixture();
        PostChangedCdcEvent event = postChangedEvent("cdc-evt-3", 503L);
        RuntimeException failure = new RuntimeException("search unavailable");
        when(fixture.consumerRecordService.startManual(Mockito.eq("cdc-evt-3"),
                Mockito.eq("search-index-cdc"), Mockito.anyString()))
                .thenReturn(StartResult.STARTED);
        when(fixture.upsertService.upsertPost(503L)).thenThrow(failure);

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> fixture.invokeThroughAspect(event));

        org.junit.jupiter.api.Assertions.assertSame(failure, thrown);
        verify(fixture.consumerRecordService).markFail("cdc-evt-3", "search-index-cdc", "search unavailable");
    }

    @Test
    void onPostChanged_invalidPayloadThroughReliableAspectMarksPermanentFailure() throws Throwable {
        SearchIndexCdcFixture fixture = new SearchIndexCdcFixture();
        PostChangedCdcEvent event = postChangedEvent("cdc-evt-4", null);
        when(fixture.consumerRecordService.startManual(Mockito.eq("cdc-evt-4"),
                Mockito.eq("search-index-cdc"), Mockito.anyString()))
                .thenReturn(StartResult.STARTED);

        assertThrows(ReliableMqPermanentFailureException.class, () -> fixture.invokeThroughAspect(event));

        verify(fixture.upsertService, never()).upsertPost(Mockito.anyLong());
        verify(fixture.consumerRecordService).markFail(Mockito.eq("cdc-evt-4"),
                Mockito.eq("search-index-cdc"), Mockito.contains("post.changed.cdc missing required fields"));
    }

    private SearchIndexConsumer newConsumer() {
        return new SearchIndexConsumer(
                Mockito.mock(SearchIndexUpsertService.class));
    }

    private static PostChangedCdcEvent postChangedEvent(String eventId, Long postId) {
        PostChangedCdcEvent event = new PostChangedCdcEvent();
        event.setPostId(postId);
        event.setTsMs(30L);
        event.setSource("canal");
        event.setTable("content_post");
        event.setEventId(eventId);
        return event;
    }

    private static final class SearchIndexCdcFixture {
        private final SearchIndexUpsertService upsertService = Mockito.mock(SearchIndexUpsertService.class);
        private final ReliableMqConsumerRecordService consumerRecordService = Mockito.mock(ReliableMqConsumerRecordService.class);
        private final SearchIndexCdcConsumer consumer = new SearchIndexCdcConsumer(upsertService);

        private void invokeThroughAspect(PostChangedCdcEvent event) throws Throwable {
            ProceedingJoinPoint joinPoint = ReliableConsumerAspectTestSupport.joinPoint(
                    consumer, "onPostChanged", "event", event, () -> consumer.onPostChanged(event));
            ReliableConsumerAspectTestSupport.aspect(consumerRecordService).around(
                    joinPoint,
                    ReliableConsumerAspectTestSupport.annotation(
                            SearchIndexCdcConsumer.class, "onPostChanged", PostChangedCdcEvent.class));
        }
    }
}
