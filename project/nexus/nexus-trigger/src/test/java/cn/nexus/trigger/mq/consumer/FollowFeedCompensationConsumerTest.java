package cn.nexus.trigger.mq.consumer;

import cn.nexus.domain.social.model.valobj.RelationCounterRouting;
import cn.nexus.domain.social.service.IFeedFollowCompensationService;
import cn.nexus.infrastructure.mq.reliable.annotation.ReliableMqConsume;
import cn.nexus.types.event.relation.RelationCounterProjectEvent;
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
        assertEquals("#event.eventId", reliableMqConsume.eventId());
        assertEquals("#event", reliableMqConsume.payload());
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
        private final FollowFeedCompensationConsumer consumer =
                new FollowFeedCompensationConsumer(compensationService);
    }
}
