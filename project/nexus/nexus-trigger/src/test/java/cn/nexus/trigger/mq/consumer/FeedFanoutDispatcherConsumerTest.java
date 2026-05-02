package cn.nexus.trigger.mq.consumer;

import cn.nexus.domain.social.adapter.repository.IFeedAuthorCategoryRepository;
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
import cn.nexus.types.event.PostPublishedEvent;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FeedFanoutDispatcherConsumerTest {

    @Test
    void onMessage_duplicateDoneDoesNotInvokeBusinessDependency() throws Throwable {
        Fixture fixture = new Fixture();
        PostPublishedEvent event = validEvent("evt-fanout-1");
        when(fixture.consumerRecordService.startManual(Mockito.eq("evt-fanout-1"),
                Mockito.eq("FeedFanoutDispatcherConsumer"), Mockito.anyString()))
                .thenReturn(StartResult.DUPLICATE_DONE);

        fixture.invokeThroughAspect(event);

        verify(fixture.feedOutboxRepository, never()).addToOutbox(Mockito.any(), Mockito.any(), Mockito.any());
        verify(fixture.consumerRecordService, never()).markDone(Mockito.any(), Mockito.any());
    }

    @Test
    void onMessage_startedSuccessInvokesBusinessDependencyAndMarksDone() throws Throwable {
        Fixture fixture = new Fixture();
        PostPublishedEvent event = validEvent("evt-fanout-2");
        when(fixture.consumerRecordService.startManual(Mockito.eq("evt-fanout-2"),
                Mockito.eq("FeedFanoutDispatcherConsumer"), Mockito.anyString()))
                .thenReturn(StartResult.STARTED);
        when(fixture.feedAuthorCategoryRepository.getCategory(11L))
                .thenReturn(FeedAuthorCategoryEnumVO.BIGV.getCode());

        fixture.invokeThroughAspect(event);

        verify(fixture.feedOutboxRepository).addToOutbox(11L, 22L, 33L);
        verify(fixture.feedTimelineRepository).addToInbox(11L, 22L, 33L);
        verify(fixture.feedGlobalLatestRepository).addToLatest(22L, 33L);
        verify(fixture.feedBigVPoolRepository).addToPool(11L, 22L, 33L);
        verify(fixture.consumerRecordService).markDone("evt-fanout-2", "FeedFanoutDispatcherConsumer");
    }

    @Test
    void onMessage_businessFailureMarksFailAndRethrows() throws Throwable {
        Fixture fixture = new Fixture();
        PostPublishedEvent event = validEvent("evt-fanout-3");
        RuntimeException failure = new RuntimeException("outbox down");
        when(fixture.consumerRecordService.startManual(Mockito.eq("evt-fanout-3"),
                Mockito.eq("FeedFanoutDispatcherConsumer"), Mockito.anyString()))
                .thenReturn(StartResult.STARTED);
        Mockito.doThrow(failure).when(fixture.feedOutboxRepository).addToOutbox(11L, 22L, 33L);

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> fixture.invokeThroughAspect(event));

        assertSame(failure, thrown);
        verify(fixture.consumerRecordService).markFail("evt-fanout-3",
                "FeedFanoutDispatcherConsumer", "outbox down");
    }

    @Test
    void onMessage_invalidEventIdThrowsBeforeBusinessDependency() {
        Fixture fixture = new Fixture();
        PostPublishedEvent event = validEvent(" ");

        assertThrows(ReliableMqPermanentFailureException.class, () -> fixture.invokeThroughAspect(event));

        verify(fixture.feedOutboxRepository, never()).addToOutbox(Mockito.any(), Mockito.any(), Mockito.any());
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
        private final RabbitTemplate rabbitTemplate = Mockito.mock(RabbitTemplate.class);
        private final IFeedTimelineRepository feedTimelineRepository = Mockito.mock(IFeedTimelineRepository.class);
        private final IFeedOutboxRepository feedOutboxRepository = Mockito.mock(IFeedOutboxRepository.class);
        private final IFeedBigVPoolRepository feedBigVPoolRepository = Mockito.mock(IFeedBigVPoolRepository.class);
        private final IFeedGlobalLatestRepository feedGlobalLatestRepository = Mockito.mock(IFeedGlobalLatestRepository.class);
        private final IRelationRepository relationRepository = Mockito.mock(IRelationRepository.class);
        private final IFeedAuthorCategoryRepository feedAuthorCategoryRepository = Mockito.mock(IFeedAuthorCategoryRepository.class);
        private final FeedAuthorCategoryStateMachine feedAuthorCategoryStateMachine = Mockito.mock(FeedAuthorCategoryStateMachine.class);
        private final ReliableMqConsumerRecordService consumerRecordService = Mockito.mock(ReliableMqConsumerRecordService.class);
        private final FeedFanoutDispatcherConsumer consumer = new FeedFanoutDispatcherConsumer(
                rabbitTemplate,
                feedTimelineRepository,
                feedOutboxRepository,
                feedBigVPoolRepository,
                feedGlobalLatestRepository,
                relationRepository,
                feedAuthorCategoryRepository,
                feedAuthorCategoryStateMachine);

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
