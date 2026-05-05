package cn.nexus.trigger.mq.consumer;

import cn.nexus.domain.social.model.valobj.RelationCounterRouting;
import cn.nexus.domain.social.service.IFeedFollowCompensationService;
import cn.nexus.infrastructure.mq.reliable.ReliableMqConsumerRecordService;
import cn.nexus.infrastructure.mq.reliable.ReliableMqConsumerRecordService.StartResult;
import cn.nexus.infrastructure.mq.reliable.annotation.ReliableMqConsume;
import cn.nexus.infrastructure.mq.reliable.exception.ReliableMqPermanentFailureException;
import cn.nexus.types.event.relation.RelationCounterProjectEvent;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FollowFeedCompensationConsumerTest {

    @Test
    void onMessage_activeCallsFollowCompensationService() {
        Fixture fixture = new Fixture();

        fixture.consumer.onMessage(event(101L, 202L, " ACTIVE "));

        verify(fixture.compensationService).onFollow(101L, 202L);
        verify(fixture.compensationService, never()).onUnfollow(Mockito.any(), Mockito.any());
    }

    @Test
    void onMessage_unfollowCallsUnfollowCompensationService() {
        Fixture fixture = new Fixture();

        fixture.consumer.onMessage(event(303L, 404L, "unfollow"));

        verify(fixture.compensationService).onUnfollow(303L, 404L);
        verify(fixture.compensationService, never()).onFollow(Mockito.any(), Mockito.any());
    }

    @Test
    void onMessage_nullEventSourceOrTargetSkips() {
        Fixture fixture = new Fixture();

        fixture.consumer.onMessage(null);
        fixture.consumer.onMessage(event(null, 202L, "ACTIVE"));
        fixture.consumer.onMessage(event(101L, null, "UNFOLLOW"));

        verify(fixture.compensationService, never()).onFollow(Mockito.any(), Mockito.any());
        verify(fixture.compensationService, never()).onUnfollow(Mockito.any(), Mockito.any());
    }

    @Test
    void onMessage_blankOrUnknownStatusSkips() {
        Fixture fixture = new Fixture();

        fixture.consumer.onMessage(event(101L, 202L, null));
        fixture.consumer.onMessage(event(101L, 202L, " "));
        fixture.consumer.onMessage(event(101L, 202L, "BLOCKED"));

        verify(fixture.compensationService, never()).onFollow(Mockito.any(), Mockito.any());
        verify(fixture.compensationService, never()).onUnfollow(Mockito.any(), Mockito.any());
    }

    @Test
    void onMessage_compensationExceptionPropagatesForRetryOrDlq() {
        Fixture fixture = new Fixture();
        RuntimeException failure = new RuntimeException("feed compensation down");
        Mockito.doThrow(failure).when(fixture.compensationService).onFollow(101L, 202L);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> fixture.consumer.onMessage(event(101L, 202L, "ACTIVE")));

        assertSame(failure, thrown);
    }

    @Test
    void onMessage_usesCompensationQueueAndReliableConsumerMetadata() throws NoSuchMethodException {
        Method method = FollowFeedCompensationConsumer.class.getMethod("onMessage", RelationCounterProjectEvent.class);

        RabbitListener rabbitListener = method.getAnnotation(RabbitListener.class);
        assertArrayEquals(new String[] {RelationCounterRouting.Q_FOLLOW_FEED_COMPENSATE}, rabbitListener.queues());
        assertTrue(rabbitListener.queues().length == 1
                && !RelationCounterRouting.Q_FOLLOW.equals(rabbitListener.queues()[0]));
        assertEquals("reliableMqListenerContainerFactory", rabbitListener.containerFactory());

        ReliableMqConsume reliableMqConsume = method.getAnnotation(ReliableMqConsume.class);
        assertEquals("FollowFeedCompensationConsumer", reliableMqConsume.consumerName());
        assertEquals("#event == null ? 'relation-counter:unknown' "
                + ": (#event.eventId != null && !#event.eventId.isBlank() "
                + "? #event.eventId : (#event.relationEventId != null "
                + "? 'relation-counter:' + #event.relationEventId : null))", reliableMqConsume.eventId());
        assertTrue(reliableMqConsume.payload().contains("#event"));
    }

    @Test
    void onMessage_runtimeReliableConsumePrefersEventIdWhenPresent() throws Throwable {
        Fixture fixture = new Fixture();
        RelationCounterProjectEvent event = event(101L, 202L, "ACTIVE");
        event.setEventId("evt-follow-feed-compensate-1");
        event.setRelationEventId(1L);
        when(fixture.consumerRecordService.startManual(Mockito.eq("evt-follow-feed-compensate-1"),
                Mockito.eq("FollowFeedCompensationConsumer"), Mockito.anyString()))
                .thenReturn(StartResult.STARTED);

        fixture.invokeThroughAspect(event);

        verify(fixture.consumerRecordService).startManual(Mockito.eq("evt-follow-feed-compensate-1"),
                Mockito.eq("FollowFeedCompensationConsumer"), Mockito.anyString());
        verify(fixture.consumerRecordService).markDone("evt-follow-feed-compensate-1", "FollowFeedCompensationConsumer");
    }

    @Test
    void onMessage_runtimeReliableConsumeFallsBackToRelationEventIdWhenEventIdMissing() throws Throwable {
        Fixture fixture = new Fixture();
        RelationCounterProjectEvent blankEventIdEvent = event(101L, 202L, "ACTIVE");
        blankEventIdEvent.setEventId(" ");
        blankEventIdEvent.setRelationEventId(11L);
        RelationCounterProjectEvent nullEventIdEvent = event(303L, 404L, "UNFOLLOW");
        nullEventIdEvent.setEventId(null);
        nullEventIdEvent.setRelationEventId(22L);
        when(fixture.consumerRecordService.startManual(Mockito.eq("relation-counter:11"),
                Mockito.eq("FollowFeedCompensationConsumer"), Mockito.anyString()))
                .thenReturn(StartResult.STARTED);
        when(fixture.consumerRecordService.startManual(Mockito.eq("relation-counter:22"),
                Mockito.eq("FollowFeedCompensationConsumer"), Mockito.anyString()))
                .thenReturn(StartResult.STARTED);

        fixture.invokeThroughAspect(blankEventIdEvent);
        fixture.invokeThroughAspect(nullEventIdEvent);

        verify(fixture.consumerRecordService).startManual(Mockito.eq("relation-counter:11"),
                Mockito.eq("FollowFeedCompensationConsumer"), Mockito.anyString());
        verify(fixture.consumerRecordService).markDone("relation-counter:11", "FollowFeedCompensationConsumer");
        verify(fixture.consumerRecordService).startManual(Mockito.eq("relation-counter:22"),
                Mockito.eq("FollowFeedCompensationConsumer"), Mockito.anyString());
        verify(fixture.consumerRecordService).markDone("relation-counter:22", "FollowFeedCompensationConsumer");
    }

    @Test
    void onMessage_runtimeReliableConsumeAllowsNullEventToSkipInMethodBody() throws Throwable {
        Fixture fixture = new Fixture();
        when(fixture.consumerRecordService.startManual(Mockito.eq("relation-counter:unknown"),
                Mockito.eq("FollowFeedCompensationConsumer"), Mockito.anyString()))
                .thenReturn(StartResult.STARTED);

        fixture.invokeThroughAspect(null);

        verify(fixture.compensationService, never()).onFollow(Mockito.any(), Mockito.any());
        verify(fixture.compensationService, never()).onUnfollow(Mockito.any(), Mockito.any());
        verify(fixture.consumerRecordService).markDone("relation-counter:unknown", "FollowFeedCompensationConsumer");
    }

    @Test
    void onMessage_runtimeReliableConsumeRejectsNonNullEventMissingStableIdsBeforeCompensation() {
        Fixture fixture = new Fixture();
        RelationCounterProjectEvent event = event(101L, 202L, "ACTIVE");
        event.setEventId(" ");
        event.setRelationEventId(null);

        ReliableMqPermanentFailureException thrown = assertThrows(ReliableMqPermanentFailureException.class,
                () -> fixture.invokeThroughAspect(event));

        assertTrue(thrown.getMessage().contains("invalid reliable mq consume eventId"));
        verify(fixture.compensationService, never()).onFollow(Mockito.any(), Mockito.any());
        verify(fixture.compensationService, never()).onUnfollow(Mockito.any(), Mockito.any());
        verify(fixture.consumerRecordService, never()).startManual(Mockito.any(), Mockito.any(), Mockito.any());
    }

    private static RelationCounterProjectEvent event(Long sourceId, Long targetId, String status) {
        RelationCounterProjectEvent event = new RelationCounterProjectEvent();
        event.setEventId("evt-follow-feed-compensate");
        event.setRelationEventId(null);
        event.setSourceId(sourceId);
        event.setTargetId(targetId);
        event.setStatus(status);
        return event;
    }

    private static final class Fixture {
        private final IFeedFollowCompensationService compensationService =
                Mockito.mock(IFeedFollowCompensationService.class);
        private final ReliableMqConsumerRecordService consumerRecordService =
                Mockito.mock(ReliableMqConsumerRecordService.class);
        private final FollowFeedCompensationConsumer consumer =
                new FollowFeedCompensationConsumer(compensationService);

        private void invokeThroughAspect(RelationCounterProjectEvent event) throws Throwable {
            ProceedingJoinPoint joinPoint = joinPoint(event);
            ReliableConsumerAspectTestSupport.aspect(consumerRecordService).around(
                    joinPoint,
                    ReliableConsumerAspectTestSupport.annotation(
                            FollowFeedCompensationConsumer.class, "onMessage", RelationCounterProjectEvent.class));
        }

        private ProceedingJoinPoint joinPoint(RelationCounterProjectEvent event) throws Throwable {
            Method method = FollowFeedCompensationConsumer.class.getMethod("onMessage", RelationCounterProjectEvent.class);
            ProceedingJoinPoint joinPoint = Mockito.mock(ProceedingJoinPoint.class);
            MethodSignature signature = Mockito.mock(MethodSignature.class);
            when(signature.getMethod()).thenReturn(method);
            when(signature.getParameterNames()).thenReturn(new String[] {"event"});
            when(joinPoint.getSignature()).thenReturn(signature);
            when(joinPoint.getTarget()).thenReturn(consumer);
            when(joinPoint.getArgs()).thenReturn(new Object[] {event});
            when(joinPoint.proceed()).thenAnswer(invocation -> {
                consumer.onMessage(event);
                return null;
            });
            return joinPoint;
        }
    }
}
