package cn.nexus.trigger.mq.consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.nexus.domain.social.adapter.repository.IContentRepository;
import cn.nexus.domain.social.adapter.repository.IFeedAuthorTimelineRepository;
import cn.nexus.domain.social.adapter.repository.IFeedBigVPoolRepository;
import cn.nexus.domain.social.adapter.repository.IFeedGlobalLatestRepository;
import cn.nexus.domain.social.adapter.repository.IFeedOutboxRepository;
import cn.nexus.domain.social.model.entity.ContentPostEntity;
import cn.nexus.infrastructure.mq.reliable.annotation.ReliableMqConsume;
import cn.nexus.infrastructure.mq.reliable.exception.ReliableMqPermanentFailureException;
import cn.nexus.trigger.mq.config.FeedFanoutConfig;
import cn.nexus.types.enums.ContentPostStatusEnumVO;
import cn.nexus.types.event.PostDeletedEvent;
import cn.nexus.types.event.PostUpdatedEvent;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

class FeedIndexCleanupConsumerTest {

    @Test
    void classLevelListenerCreatesSingleQueueConsumerForBothHandlers() {
        RabbitListener rabbitListener = FeedIndexCleanupConsumer.class.getAnnotation(RabbitListener.class);
        long methodListenerCount = Arrays.stream(FeedIndexCleanupConsumer.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(RabbitListener.class))
                .count();
        long handlerCount = Arrays.stream(FeedIndexCleanupConsumer.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(RabbitHandler.class))
                .count();

        assertEquals(List.of(FeedFanoutConfig.Q_FEED_INDEX_CLEANUP), Arrays.asList(rabbitListener.queues()));
        assertEquals("reliableMqListenerContainerFactory", rabbitListener.containerFactory());
        assertEquals(0L, methodListenerCount);
        assertEquals(2L, handlerCount);
    }

    @Test
    void updateHandlerUsesCleanupQueueAndReliableConsumerName() throws Exception {
        Method listener = FeedIndexCleanupConsumer.class.getDeclaredMethod("onUpdated", PostUpdatedEvent.class);
        ReliableMqConsume reliableMqConsume = listener.getAnnotation(ReliableMqConsume.class);

        assertEquals("FeedIndexCleanupUpdatedConsumer", reliableMqConsume.consumerName());
        assertEquals("#event.eventId", reliableMqConsume.eventId());
        assertEquals("#event", reliableMqConsume.payload());
    }

    @Test
    void deleteHandlerUsesCleanupQueueAndSeparateReliableConsumerName() throws Exception {
        Method listener = FeedIndexCleanupConsumer.class.getDeclaredMethod("onDeleted", PostDeletedEvent.class);
        ReliableMqConsume reliableMqConsume = listener.getAnnotation(ReliableMqConsume.class);

        assertEquals("FeedIndexCleanupDeletedConsumer", reliableMqConsume.consumerName());
        assertEquals("#event.eventId", reliableMqConsume.eventId());
        assertEquals("#event", reliableMqConsume.payload());
    }

    @Test
    void onUpdated_invalidPayloadThrowsPermanentFailure() {
        Fixture fixture = new Fixture();
        PostUpdatedEvent event = new PostUpdatedEvent();
        event.setEventId(" ");
        event.setPostId(10L);

        assertThrows(ReliableMqPermanentFailureException.class, () -> fixture.consumer.onUpdated(event));

        verify(fixture.contentRepository, never()).findPostBypassCache(Mockito.any());
        verifyNoInteractions(fixture.authorTimelineRepository);
    }

    @Test
    void onDeleted_invalidPayloadThrowsPermanentFailure() {
        Fixture fixture = new Fixture();
        PostDeletedEvent event = new PostDeletedEvent();
        event.setEventId("evt");

        assertThrows(ReliableMqPermanentFailureException.class, () -> fixture.consumer.onDeleted(event));

        verify(fixture.contentRepository, never()).findPostBypassCache(Mockito.any());
        verifyNoInteractions(fixture.authorTimelineRepository);
    }

    @Test
    void onDeleted_dbNullSkipsAndDoesNotRemoveTimeline() {
        Fixture fixture = new Fixture();
        PostDeletedEvent event = deletedEvent();
        event.setOperatorId(999L);
        when(fixture.contentRepository.findPostBypassCache(42L)).thenReturn(null);

        fixture.consumer.onDeleted(event);

        verify(fixture.contentRepository).findPostBypassCache(42L);
        verifyNoInteractions(fixture.authorTimelineRepository);
    }

    @Test
    void onUpdated_publishedPostDoesNotRemoveTimeline() {
        Fixture fixture = new Fixture();
        when(fixture.contentRepository.findPostBypassCache(42L))
                .thenReturn(post(88L, ContentPostStatusEnumVO.PUBLISHED.getCode()));

        fixture.consumer.onUpdated(updatedEvent());

        verifyNoInteractions(fixture.authorTimelineRepository);
    }

    @Test
    void onUpdated_nonPublishedPostRemovesAuthorTimelineByDbAuthor() {
        Fixture fixture = new Fixture();
        PostUpdatedEvent event = updatedEvent();
        event.setOperatorId(999L);
        when(fixture.contentRepository.findPostBypassCache(42L))
                .thenReturn(post(88L, ContentPostStatusEnumVO.DELETED.getCode()));

        fixture.consumer.onUpdated(event);

        verify(fixture.authorTimelineRepository).removeFromTimeline(88L, 42L);
        verify(fixture.authorTimelineRepository, never()).removeFromTimeline(999L, 42L);
    }

    @Test
    void onUpdated_contentLookupFailureRethrowsForRetry() {
        Fixture fixture = new Fixture();
        RuntimeException failure = new RuntimeException("db down");
        when(fixture.contentRepository.findPostBypassCache(42L)).thenThrow(failure);

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> fixture.consumer.onUpdated(updatedEvent()));

        assertSame(failure, thrown);
        verifyNoInteractions(fixture.authorTimelineRepository);
    }

    @Test
    void consumerDoesNotDependOnOutboxBigVPoolOrGlobalLatestRepositories() {
        List<Class<?>> constructorTypes = Arrays.stream(FeedIndexCleanupConsumer.class.getDeclaredConstructors())
                .map(Constructor::getParameterTypes)
                .flatMap(Arrays::stream)
                .toList();
        List<Class<?>> fieldTypes = Arrays.stream(FeedIndexCleanupConsumer.class.getDeclaredFields())
                .map(Field::getType)
                .toList();

        assertTrue(constructorTypes.stream().noneMatch(IFeedOutboxRepository.class::equals));
        assertTrue(constructorTypes.stream().noneMatch(IFeedBigVPoolRepository.class::equals));
        assertTrue(constructorTypes.stream().noneMatch(IFeedGlobalLatestRepository.class::equals));
        assertTrue(fieldTypes.stream().noneMatch(IFeedOutboxRepository.class::equals));
        assertTrue(fieldTypes.stream().noneMatch(IFeedBigVPoolRepository.class::equals));
        assertTrue(fieldTypes.stream().noneMatch(IFeedGlobalLatestRepository.class::equals));
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
        private final IFeedAuthorTimelineRepository authorTimelineRepository = Mockito.mock(IFeedAuthorTimelineRepository.class);
        private final FeedIndexCleanupConsumer consumer = new FeedIndexCleanupConsumer(
                contentRepository,
                authorTimelineRepository);
    }
}
