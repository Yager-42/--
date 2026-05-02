package cn.nexus.trigger.listener.social;

import cn.nexus.domain.social.service.RelationCounterProjectionProcessor;
import cn.nexus.infrastructure.mq.reliable.ReliableMqConsumerRecordService;
import cn.nexus.infrastructure.mq.reliable.ReliableMqConsumerRecordService.StartResult;
import cn.nexus.types.event.relation.RelationCounterProjectEvent;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RelationCounterProjectConsumerTest {

    private static final String CONSUMER_NAME = "RelationCounterProjectConsumer";
    private static final long DELIVERY_TAG = 42L;

    @Test
    void duplicateDoneCallsBasicAck() throws Exception {
        Fixture fixture = new Fixture();
        RelationCounterProjectEvent event = event("evt-duplicate", 1001L);
        when(fixture.consumerRecordService.startManual("evt-duplicate", CONSUMER_NAME, "{}"))
                .thenReturn(StartResult.DUPLICATE_DONE);

        fixture.consumer.onFollow(event, fixture.message, fixture.channel);

        verify(fixture.channel).basicAck(DELIVERY_TAG, false);
        verify(fixture.channel, never()).basicNack(Mockito.anyLong(), Mockito.anyBoolean(), Mockito.anyBoolean());
        verify(fixture.processor, never()).process(any());
        verify(fixture.consumerRecordService, never()).markDone(Mockito.anyString(), Mockito.anyString());
        verify(fixture.consumerRecordService, never()).markFail(Mockito.anyString(), Mockito.anyString(), Mockito.any());
    }

    @Test
    void freshProcessingDuplicateInProgressCallsBasicNackWithRequeue() throws Exception {
        Fixture fixture = new Fixture();
        RelationCounterProjectEvent event = event(null, 1002L);
        when(fixture.consumerRecordService.startManual("relation-counter:1002", CONSUMER_NAME, "{}"))
                .thenReturn(StartResult.IN_PROGRESS);

        fixture.consumer.onFollow(event, fixture.message, fixture.channel);

        verify(fixture.channel).basicNack(DELIVERY_TAG, false, true);
        verify(fixture.channel, never()).basicAck(Mockito.anyLong(), Mockito.anyBoolean());
        verify(fixture.processor, never()).process(any());
        verify(fixture.consumerRecordService, never()).markDone(Mockito.anyString(), Mockito.anyString());
        verify(fixture.consumerRecordService, never()).markFail(Mockito.anyString(), Mockito.anyString(), Mockito.any());
    }

    @Test
    void businessFailureMarksFailAndCallsBasicNackWithoutRequeue() throws Exception {
        Fixture fixture = new Fixture();
        RelationCounterProjectEvent event = event("evt-failure", 1003L);
        RuntimeException failure = new RuntimeException("projection failed");
        when(fixture.consumerRecordService.startManual("evt-failure", CONSUMER_NAME, "{}"))
                .thenReturn(StartResult.STARTED);
        doThrow(failure).when(fixture.processor).process(event);

        fixture.consumer.onBlock(event, fixture.message, fixture.channel);

        verify(fixture.consumerRecordService).markFail("evt-failure", CONSUMER_NAME, "projection failed");
        verify(fixture.channel).basicNack(DELIVERY_TAG, false, false);
        verify(fixture.channel, never()).basicAck(Mockito.anyLong(), Mockito.anyBoolean());
        verify(fixture.consumerRecordService, never()).markDone(Mockito.anyString(), Mockito.anyString());
    }

    @Test
    void successfulProcessingMarksDoneBeforeBasicAck() throws Exception {
        Fixture fixture = new Fixture();
        RelationCounterProjectEvent event = event("evt-success", 1004L);
        when(fixture.consumerRecordService.startManual("evt-success", CONSUMER_NAME, "{}"))
                .thenReturn(StartResult.STARTED);

        fixture.consumer.onFollow(event, fixture.message, fixture.channel);

        InOrder inOrder = Mockito.inOrder(fixture.processor, fixture.consumerRecordService, fixture.channel);
        inOrder.verify(fixture.processor).process(event);
        inOrder.verify(fixture.consumerRecordService).markDone("evt-success", CONSUMER_NAME);
        inOrder.verify(fixture.channel).basicAck(DELIVERY_TAG, false);
        verify(fixture.channel, never()).basicNack(Mockito.anyLong(), Mockito.anyBoolean(), Mockito.anyBoolean());
        verify(fixture.consumerRecordService, never()).markFail(Mockito.anyString(), Mockito.anyString(), Mockito.any());
    }

    private static RelationCounterProjectEvent event(String eventId, Long relationEventId) {
        RelationCounterProjectEvent event = new RelationCounterProjectEvent();
        event.setEventId(eventId);
        event.setRelationEventId(relationEventId);
        return event;
    }

    private static final class Fixture {
        private final RelationCounterProjectionProcessor processor = Mockito.mock(RelationCounterProjectionProcessor.class);
        private final ReliableMqConsumerRecordService consumerRecordService =
                Mockito.mock(ReliableMqConsumerRecordService.class);
        private final TransactionTemplate transactionTemplate = Mockito.mock(TransactionTemplate.class);
        private final Channel channel = Mockito.mock(Channel.class);
        private final Message message = Mockito.mock(Message.class);
        private final MessageProperties messageProperties = Mockito.mock(MessageProperties.class);
        private final RelationCounterProjectConsumer consumer =
                new RelationCounterProjectConsumer(processor, consumerRecordService, transactionTemplate);

        private Fixture() {
            when(message.getMessageProperties()).thenReturn(messageProperties);
            when(messageProperties.getDeliveryTag()).thenReturn(DELIVERY_TAG);
            doAnswer(invocation -> {
                @SuppressWarnings("unchecked")
                TransactionCallback<Object> callback = invocation.getArgument(0);
                return callback.doInTransaction(Mockito.mock(TransactionStatus.class));
            }).when(transactionTemplate).execute(any());
        }
    }
}
