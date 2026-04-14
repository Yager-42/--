package cn.nexus.trigger.job.social;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.domain.social.adapter.port.IReactionEventLogPort;
import cn.nexus.infrastructure.mq.reliable.ReliableMqConsumerRecordService;
import cn.nexus.types.event.interaction.ReactionEventLogMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;

class ReactionEventLogConsumerTest {

    @Test
    void onMessage_shouldAppendAndMarkDone() {
        IReactionEventLogPort eventLogPort = Mockito.mock(IReactionEventLogPort.class);
        ReliableMqConsumerRecordService recordService = Mockito.mock(ReliableMqConsumerRecordService.class);
        when(recordService.start(Mockito.eq("evt-1"), Mockito.anyString(), Mockito.any())).thenReturn(true);
        when(eventLogPort.append(any())).thenReturn("inserted");

        ReactionEventLogConsumer consumer = new ReactionEventLogConsumer(eventLogPort, recordService, new ObjectMapper());

        consumer.onMessage(message("evt-1", "POST", 101L, 7L, 1, 1));

        verify(eventLogPort).append(any());
        verify(recordService).markDone("evt-1", "reaction-event-log");
    }

    @Test
    void onMessage_shouldTreatDuplicateAsSuccess() {
        IReactionEventLogPort eventLogPort = Mockito.mock(IReactionEventLogPort.class);
        ReliableMqConsumerRecordService recordService = Mockito.mock(ReliableMqConsumerRecordService.class);
        when(recordService.start(Mockito.eq("evt-2"), Mockito.anyString(), Mockito.any())).thenReturn(true);
        when(eventLogPort.append(any())).thenReturn("duplicate");

        ReactionEventLogConsumer consumer = new ReactionEventLogConsumer(eventLogPort, recordService, new ObjectMapper());

        consumer.onMessage(message("evt-2", "POST", 102L, 8L, 1, 1));

        verify(recordService).markDone("evt-2", "reaction-event-log");
    }

    @Test
    void onMessage_shouldSkipWhenConsumerRecordRejectsDuplicateDelivery() {
        IReactionEventLogPort eventLogPort = Mockito.mock(IReactionEventLogPort.class);
        ReliableMqConsumerRecordService recordService = Mockito.mock(ReliableMqConsumerRecordService.class);
        when(recordService.start(Mockito.eq("evt-3"), Mockito.anyString(), Mockito.any())).thenReturn(false);

        ReactionEventLogConsumer consumer = new ReactionEventLogConsumer(eventLogPort, recordService, new ObjectMapper());

        consumer.onMessage(message("evt-3", "COMMENT", 201L, 9L, 1, 1));

        verify(eventLogPort, never()).append(any());
        verify(recordService, never()).markDone(Mockito.anyString(), Mockito.anyString());
    }

    @Test
    void onMessage_shouldMarkFailAndRejectWhenAppendThrows() {
        IReactionEventLogPort eventLogPort = Mockito.mock(IReactionEventLogPort.class);
        ReliableMqConsumerRecordService recordService = Mockito.mock(ReliableMqConsumerRecordService.class);
        when(recordService.start(Mockito.eq("evt-4"), Mockito.anyString(), Mockito.any())).thenReturn(true);
        when(eventLogPort.append(any())).thenThrow(new RuntimeException("db down"));

        ReactionEventLogConsumer consumer = new ReactionEventLogConsumer(eventLogPort, recordService, new ObjectMapper());

        assertThrows(AmqpRejectAndDontRequeueException.class,
                () -> consumer.onMessage(message("evt-4", "POST", 104L, 10L, 1, 1)));

        verify(recordService).markFail("evt-4", "reaction-event-log", "RuntimeException");
    }

    @Test
    void onMessage_shouldRejectInvalidEvent() {
        ReactionEventLogConsumer consumer = new ReactionEventLogConsumer(
                Mockito.mock(IReactionEventLogPort.class),
                Mockito.mock(ReliableMqConsumerRecordService.class),
                new ObjectMapper());

        ReactionEventLogMessage invalid = new ReactionEventLogMessage();
        invalid.setTargetId(1L);

        assertThrows(AmqpRejectAndDontRequeueException.class, () -> consumer.onMessage(invalid));
    }

    private ReactionEventLogMessage message(String eventId,
                                            String targetType,
                                            Long targetId,
                                            Long userId,
                                            Integer desiredState,
                                            Integer delta) {
        ReactionEventLogMessage message = new ReactionEventLogMessage();
        message.setEventId(eventId);
        message.setTargetType(targetType);
        message.setTargetId(targetId);
        message.setReactionType("LIKE");
        message.setUserId(userId);
        message.setDesiredState(desiredState);
        message.setDelta(delta);
        message.setEventTime(123456L);
        return message;
    }
}
