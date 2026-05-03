package cn.nexus.trigger.mq.consumer;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.domain.social.adapter.repository.IContentRepository;
import cn.nexus.domain.social.adapter.repository.IFeedBigVPoolRepository;
import cn.nexus.domain.social.adapter.repository.IFeedGlobalLatestRepository;
import cn.nexus.domain.social.adapter.repository.IFeedOutboxRepository;
import cn.nexus.domain.social.model.entity.ContentPostEntity;
import cn.nexus.infrastructure.mq.reliable.exception.ReliableMqPermanentFailureException;
import cn.nexus.types.enums.ContentPostStatusEnumVO;
import cn.nexus.types.event.PostDeletedEvent;
import cn.nexus.types.event.PostUpdatedEvent;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class FeedIndexCleanupConsumerTest {

    @Test
    void onUpdated_invalidPayloadThrowsPermanentFailure() {
        Fixture fixture = new Fixture();
        PostUpdatedEvent event = new PostUpdatedEvent();
        event.setEventId(" ");
        event.setPostId(10L);

        assertThrows(ReliableMqPermanentFailureException.class, () -> fixture.consumer.onUpdated(event));

        verify(fixture.contentRepository, never()).findPostBypassCache(Mockito.any());
    }

    @Test
    void onDeleted_invalidPayloadThrowsPermanentFailure() {
        Fixture fixture = new Fixture();
        PostDeletedEvent event = new PostDeletedEvent();
        event.setEventId("evt");

        assertThrows(ReliableMqPermanentFailureException.class, () -> fixture.consumer.onDeleted(event));

        verify(fixture.contentRepository, never()).findPostBypassCache(Mockito.any());
    }

    @Test
    void onDeleted_dbNullRemovesOnlyLatest() {
        Fixture fixture = new Fixture();
        PostDeletedEvent event = deletedEvent();
        event.setOperatorId(999L);
        when(fixture.contentRepository.findPostBypassCache(42L)).thenReturn(null);

        fixture.consumer.onDeleted(event);

        verify(fixture.latestRepository).removeFromLatest(42L);
        verify(fixture.outboxRepository, never()).removeFromOutbox(Mockito.any(), Mockito.any());
        verify(fixture.poolRepository, never()).removeFromPool(Mockito.any(), Mockito.any());
    }

    @Test
    void onUpdated_publishedPostDoesNotRemoveAnyIndex() {
        Fixture fixture = new Fixture();
        when(fixture.contentRepository.findPostBypassCache(42L)).thenReturn(post(88L, ContentPostStatusEnumVO.PUBLISHED.getCode()));

        fixture.consumer.onUpdated(updatedEvent());

        verify(fixture.outboxRepository, never()).removeFromOutbox(Mockito.any(), Mockito.any());
        verify(fixture.poolRepository, never()).removeFromPool(Mockito.any(), Mockito.any());
        verify(fixture.latestRepository, never()).removeFromLatest(Mockito.any());
    }

    @Test
    void onUpdated_nonPublishedPostRemovesIndexesByDbAuthor() {
        Fixture fixture = new Fixture();
        PostUpdatedEvent event = updatedEvent();
        event.setOperatorId(999L);
        when(fixture.contentRepository.findPostBypassCache(42L)).thenReturn(post(88L, ContentPostStatusEnumVO.DELETED.getCode()));

        fixture.consumer.onUpdated(event);

        verify(fixture.outboxRepository).removeFromOutbox(88L, 42L);
        verify(fixture.poolRepository).removeFromPool(88L, 42L);
        verify(fixture.latestRepository).removeFromLatest(42L);
        verify(fixture.outboxRepository, never()).removeFromOutbox(999L, 42L);
    }

    @Test
    void onDeleted_singleDeleteFailureDoesNotBlockOtherIndexes() {
        Fixture fixture = new Fixture();
        when(fixture.contentRepository.findPostBypassCache(42L)).thenReturn(post(88L, null));
        doThrow(new RuntimeException("outbox down")).when(fixture.outboxRepository).removeFromOutbox(88L, 42L);

        fixture.consumer.onDeleted(deletedEvent());

        verify(fixture.outboxRepository).removeFromOutbox(88L, 42L);
        verify(fixture.poolRepository).removeFromPool(88L, 42L);
        verify(fixture.latestRepository).removeFromLatest(42L);
    }

    @Test
    void onUpdated_contentLookupFailureRethrowsForRetry() {
        Fixture fixture = new Fixture();
        RuntimeException failure = new RuntimeException("db down");
        when(fixture.contentRepository.findPostBypassCache(42L)).thenThrow(failure);

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> fixture.consumer.onUpdated(updatedEvent()));

        assertSame(failure, thrown);
    }

    private static PostUpdatedEvent updatedEvent() {
        PostUpdatedEvent event = new PostUpdatedEvent();
        event.setEventId("evt-updated");
        event.setPostId(42L);
        event.setOperatorId(7L);
        return event;
    }

    private static PostDeletedEvent deletedEvent() {
        PostDeletedEvent event = new PostDeletedEvent();
        event.setEventId("evt-deleted");
        event.setPostId(42L);
        event.setOperatorId(7L);
        return event;
    }

    private static ContentPostEntity post(Long userId, Integer status) {
        return ContentPostEntity.builder()
                .postId(42L)
                .userId(userId)
                .status(status)
                .build();
    }

    private static final class Fixture {
        private final IContentRepository contentRepository = Mockito.mock(IContentRepository.class);
        private final IFeedOutboxRepository outboxRepository = Mockito.mock(IFeedOutboxRepository.class);
        private final IFeedBigVPoolRepository poolRepository = Mockito.mock(IFeedBigVPoolRepository.class);
        private final IFeedGlobalLatestRepository latestRepository = Mockito.mock(IFeedGlobalLatestRepository.class);
        private final FeedIndexCleanupConsumer consumer = new FeedIndexCleanupConsumer(
                contentRepository,
                outboxRepository,
                poolRepository,
                latestRepository);
    }
}
