package cn.nexus.trigger.mq.consumer;

import cn.nexus.domain.social.adapter.repository.IFeedAuthorCategoryRepository;
import cn.nexus.domain.social.adapter.repository.IFeedAuthorTimelineRepository;
import cn.nexus.domain.social.adapter.repository.IFeedBigVPoolRepository;
import cn.nexus.domain.social.adapter.repository.IFeedGlobalLatestRepository;
import cn.nexus.domain.social.adapter.repository.IFeedOutboxRepository;
import cn.nexus.domain.social.adapter.repository.IFeedTimelineRepository;
import cn.nexus.domain.social.adapter.repository.IRelationRepository;
import cn.nexus.domain.social.model.valobj.FeedAuthorCategoryEnumVO;
import cn.nexus.domain.social.service.FeedAuthorCategoryStateMachine;
import cn.nexus.infrastructure.mq.reliable.ReliableMqConsumerRecordService;
import cn.nexus.infrastructure.mq.reliable.ReliableMqConsumerRecordService.StartResult;
import cn.nexus.infrastructure.mq.reliable.exception.ReliableMqPermanentFailureException;
import cn.nexus.trigger.mq.producer.FeedFanoutTaskProducer;
import cn.nexus.types.event.PostPublishedEvent;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FeedFanoutDispatcherConsumerTest {

    @Test
    void consumerDoesNotDependOnReadSideFanoutRepositories() {
        Set<Class<?>> forbiddenDependencies = Set.of(
                IFeedTimelineRepository.class,
                IFeedOutboxRepository.class,
                IFeedBigVPoolRepository.class,
                IFeedGlobalLatestRepository.class);

        for (Field field : FeedFanoutDispatcherConsumer.class.getDeclaredFields()) {
            assertFalse(forbiddenDependencies.contains(field.getType()),
                    () -> "FeedFanoutDispatcherConsumer field depends on " + field.getType().getName());
        }
        for (Constructor<?> constructor : FeedFanoutDispatcherConsumer.class.getDeclaredConstructors()) {
            for (Class<?> parameterType : constructor.getParameterTypes()) {
                assertFalse(forbiddenDependencies.contains(parameterType),
                        () -> "FeedFanoutDispatcherConsumer constructor depends on " + parameterType.getName());
            }
        }
    }

    @Test
    void onMessage_duplicateDoneDoesNotInvokeBusinessDependency() throws Throwable {
        Fixture fixture = new Fixture();
        PostPublishedEvent event = validEvent("evt-fanout-1");
        when(fixture.consumerRecordService.startManual(Mockito.eq("evt-fanout-1"),
                Mockito.eq("FeedFanoutDispatcherConsumer"), Mockito.anyString()))
                .thenReturn(StartResult.DUPLICATE_DONE);

        fixture.invokeThroughAspect(event);

        verify(fixture.feedAuthorTimelineRepository, never()).addToTimeline(Mockito.any(), Mockito.any(), Mockito.any());
        verify(fixture.consumerRecordService, never()).markDone(Mockito.any(), Mockito.any());
    }

    @Test
    void onMessage_startedSuccessWritesAuthorTimelineOnlyForPublishSideStoresAndMarksDone() throws Throwable {
        Fixture fixture = new Fixture();
        PostPublishedEvent event = validEvent("evt-fanout-2");
        when(fixture.consumerRecordService.startManual(Mockito.eq("evt-fanout-2"),
                Mockito.eq("FeedFanoutDispatcherConsumer"), Mockito.anyString()))
                .thenReturn(StartResult.STARTED);
        when(fixture.feedAuthorCategoryRepository.getCategory(11L))
                .thenReturn(FeedAuthorCategoryEnumVO.BIGV.getCode());

        fixture.invokeThroughAspect(event);

        verify(fixture.feedAuthorTimelineRepository).addToTimeline(11L, 22L, 33L);
        verify(fixture.consumerRecordService).markDone("evt-fanout-2", "FeedFanoutDispatcherConsumer");
    }

    @Test
    void onMessage_bigvAuthorWritesAuthorTimelineAndDoesNotPublishFanoutTasks() throws Throwable {
        Fixture fixture = new Fixture();
        PostPublishedEvent event = validEvent("evt-fanout-bigv");
        when(fixture.consumerRecordService.startManual(Mockito.eq("evt-fanout-bigv"),
                Mockito.eq("FeedFanoutDispatcherConsumer"), Mockito.anyString()))
                .thenReturn(StartResult.STARTED);
        when(fixture.feedAuthorCategoryRepository.getCategory(11L))
                .thenReturn(FeedAuthorCategoryEnumVO.BIGV.getCode());

        fixture.invokeThroughAspect(event);

        verify(fixture.feedAuthorTimelineRepository).addToTimeline(11L, 22L, 33L);
        verify(fixture.feedFanoutTaskProducer, never()).publish(Mockito.any());
    }

    @Test
    void onMessage_normalAuthorWritesAuthorTimelineAndPublishesExpectedFanoutTasks() throws Throwable {
        Fixture fixture = new Fixture();
        PostPublishedEvent event = validEvent("evt-fanout-4");
        when(fixture.consumerRecordService.startManual(Mockito.eq("evt-fanout-4"),
                Mockito.eq("FeedFanoutDispatcherConsumer"), Mockito.anyString()))
                .thenReturn(StartResult.STARTED);
        when(fixture.feedAuthorCategoryRepository.getCategory(11L))
                .thenReturn(FeedAuthorCategoryEnumVO.NORMAL.getCode());
        when(fixture.relationRepository.countFollowerIds(11L)).thenReturn(401);

        fixture.invokeThroughAspect(event);

        verify(fixture.feedAuthorTimelineRepository).addToTimeline(11L, 22L, 33L);
        verify(fixture.feedFanoutTaskProducer).publish(argThat(task ->
                task != null
                        && "evt-fanout-4:0:200".equals(task.eventId())
                        && Long.valueOf(22L).equals(task.postId())
                        && Long.valueOf(11L).equals(task.authorId())
                        && Long.valueOf(33L).equals(task.publishTimeMs())
                        && Integer.valueOf(0).equals(task.offset())
                        && Integer.valueOf(200).equals(task.limit())));
        verify(fixture.feedFanoutTaskProducer).publish(argThat(task ->
                task != null
                        && "evt-fanout-4:200:200".equals(task.eventId())
                        && Integer.valueOf(200).equals(task.offset())
                        && Integer.valueOf(200).equals(task.limit())));
        verify(fixture.feedFanoutTaskProducer).publish(argThat(task ->
                task != null
                        && "evt-fanout-4:400:200".equals(task.eventId())
                        && Integer.valueOf(400).equals(task.offset())
                        && Integer.valueOf(200).equals(task.limit())));
    }

    @Test
    void onMessage_businessFailureMarksFailAndRethrows() throws Throwable {
        Fixture fixture = new Fixture();
        PostPublishedEvent event = validEvent("evt-fanout-3");
        RuntimeException failure = new RuntimeException("timeline down");
        when(fixture.consumerRecordService.startManual(Mockito.eq("evt-fanout-3"),
                Mockito.eq("FeedFanoutDispatcherConsumer"), Mockito.anyString()))
                .thenReturn(StartResult.STARTED);
        doThrow(failure).when(fixture.feedAuthorTimelineRepository).addToTimeline(11L, 22L, 33L);

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> fixture.invokeThroughAspect(event));

        assertSame(failure, thrown);
        verify(fixture.consumerRecordService).markFail("evt-fanout-3",
                "FeedFanoutDispatcherConsumer", "timeline down");
    }

    @Test
    void onMessage_invalidEventIdThrowsBeforeBusinessDependency() {
        Fixture fixture = new Fixture();
        PostPublishedEvent event = validEvent(" ");

        assertThrows(ReliableMqPermanentFailureException.class, () -> fixture.invokeThroughAspect(event));

        verify(fixture.feedAuthorTimelineRepository, never()).addToTimeline(Mockito.any(), Mockito.any(), Mockito.any());
        verify(fixture.consumerRecordService, never()).startManual(Mockito.any(), Mockito.any(), Mockito.any());
    }

    private static PostPublishedEvent validEvent(String eventId) {
        PostPublishedEvent event = new PostPublishedEvent();
        event.setEventId(eventId);
        event.setAuthorId(11L);
        event.setPostId(22L);
        event.setPublishTimeMs(33L);
        return event;
    }

    private static final class Fixture {
        private final FeedFanoutTaskProducer feedFanoutTaskProducer = Mockito.mock(FeedFanoutTaskProducer.class);
        private final IFeedAuthorTimelineRepository feedAuthorTimelineRepository = Mockito.mock(IFeedAuthorTimelineRepository.class);
        private final IRelationRepository relationRepository = Mockito.mock(IRelationRepository.class);
        private final IFeedAuthorCategoryRepository feedAuthorCategoryRepository = Mockito.mock(IFeedAuthorCategoryRepository.class);
        private final FeedAuthorCategoryStateMachine feedAuthorCategoryStateMachine = Mockito.mock(FeedAuthorCategoryStateMachine.class);
        private final ReliableMqConsumerRecordService consumerRecordService = Mockito.mock(ReliableMqConsumerRecordService.class);
        private final FeedFanoutDispatcherConsumer consumer = new FeedFanoutDispatcherConsumer(
                feedFanoutTaskProducer,
                feedAuthorTimelineRepository,
                relationRepository,
                feedAuthorCategoryRepository,
                feedAuthorCategoryStateMachine);

        private Fixture() {
            ReflectionTestUtils.setField(consumer, "batchSize", 200);
        }

        private void invokeThroughAspect(PostPublishedEvent event) throws Throwable {
            ProceedingJoinPoint joinPoint = ReliableConsumerAspectTestSupport.joinPoint(
                    consumer, "onMessage", "event", event, () -> consumer.onMessage(event));
            ReliableConsumerAspectTestSupport.aspect(consumerRecordService).around(
                    joinPoint,
                    ReliableConsumerAspectTestSupport.annotation(
                            FeedFanoutDispatcherConsumer.class, "onMessage", PostPublishedEvent.class));
        }
    }
}
