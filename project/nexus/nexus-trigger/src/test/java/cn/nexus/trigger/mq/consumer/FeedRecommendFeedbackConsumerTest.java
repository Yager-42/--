package cn.nexus.trigger.mq.consumer;

import cn.nexus.domain.social.adapter.port.IRecommendationPort;
import cn.nexus.infrastructure.mq.reliable.ReliableMqConsumerRecordService;
import cn.nexus.infrastructure.mq.reliable.ReliableMqConsumerRecordService.StartResult;
import cn.nexus.infrastructure.mq.reliable.exception.ReliableMqPermanentFailureException;
import cn.nexus.types.event.recommend.RecommendFeedbackEvent;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FeedRecommendFeedbackConsumerTest {

    @Test
    void onMessage_duplicateDoneDoesNotInvokeBusinessDependency() throws Throwable {
        Fixture fixture = new Fixture();
        RecommendFeedbackEvent event = validEvent("evt-feedback-1");
        when(fixture.consumerRecordService.startManual(Mockito.eq("evt-feedback-1"),
                Mockito.eq("FeedRecommendFeedbackConsumer"), Mockito.anyString()))
                .thenReturn(StartResult.DUPLICATE_DONE);

        fixture.invokeThroughAspect(event);

        verify(fixture.recommendationPort, never()).insertFeedback(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
        verify(fixture.consumerRecordService, never()).markDone(Mockito.any(), Mockito.any());
    }

    @Test
    void onMessage_startedSuccessInvokesBusinessDependencyAndMarksDone() throws Throwable {
        Fixture fixture = new Fixture();
        RecommendFeedbackEvent event = validEvent("evt-feedback-2");
        when(fixture.consumerRecordService.startManual(Mockito.eq("evt-feedback-2"),
                Mockito.eq("FeedRecommendFeedbackConsumer"), Mockito.anyString()))
                .thenReturn(StartResult.STARTED);

        fixture.invokeThroughAspect(event);

        verify(fixture.recommendationPort).insertFeedback(101L, 202L, "unlike", 303L);
        verify(fixture.consumerRecordService).markDone("evt-feedback-2", "FeedRecommendFeedbackConsumer");
    }

    @Test
    void onMessage_businessFailureMarksFailAndRethrows() throws Throwable {
        Fixture fixture = new Fixture();
        RecommendFeedbackEvent event = validEvent("evt-feedback-3");
        RuntimeException failure = new RuntimeException("recommend down");
        when(fixture.consumerRecordService.startManual(Mockito.eq("evt-feedback-3"),
                Mockito.eq("FeedRecommendFeedbackConsumer"), Mockito.anyString()))
                .thenReturn(StartResult.STARTED);
        Mockito.doThrow(failure).when(fixture.recommendationPort).insertFeedback(101L, 202L, "unlike", 303L);

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> fixture.invokeThroughAspect(event));

        assertSame(failure, thrown);
        verify(fixture.consumerRecordService).markFail("evt-feedback-3",
                "FeedRecommendFeedbackConsumer", "recommend down");
    }

    @Test
    void onMessage_invalidEventIdThrowsBeforeBusinessDependency() {
        Fixture fixture = new Fixture();
        RecommendFeedbackEvent event = validEvent(" ");

        assertThrows(ReliableMqPermanentFailureException.class, () -> fixture.invokeThroughAspect(event));

        verify(fixture.recommendationPort, never()).insertFeedback(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
        verify(fixture.consumerRecordService, never()).startManual(Mockito.any(), Mockito.any(), Mockito.any());
    }

    private static RecommendFeedbackEvent validEvent(String eventId) {
        RecommendFeedbackEvent event = new RecommendFeedbackEvent();
        event.setEventId(eventId);
        event.setFromUserId(101L);
        event.setPostId(202L);
        event.setFeedbackType("unlike");
        event.setTsMs(303L);
        return event;
    }

    private static final class Fixture {
        private final IRecommendationPort recommendationPort = Mockito.mock(IRecommendationPort.class);
        private final ReliableMqConsumerRecordService consumerRecordService = Mockito.mock(ReliableMqConsumerRecordService.class);
        private final FeedRecommendFeedbackConsumer consumer = new FeedRecommendFeedbackConsumer(recommendationPort);

        private void invokeThroughAspect(RecommendFeedbackEvent event) throws Throwable {
            ProceedingJoinPoint joinPoint = ReliableConsumerAspectTestSupport.joinPoint(
                    consumer, "onMessage", "event", event, () -> consumer.onMessage(event));
            ReliableConsumerAspectTestSupport.aspect(consumerRecordService).around(
                    joinPoint,
                    ReliableConsumerAspectTestSupport.annotation(
                            FeedRecommendFeedbackConsumer.class, "onMessage", RecommendFeedbackEvent.class));
        }
    }
}
