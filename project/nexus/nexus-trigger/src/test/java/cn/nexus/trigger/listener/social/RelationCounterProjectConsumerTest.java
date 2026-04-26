package cn.nexus.trigger.listener.social;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.domain.social.service.RelationCounterProjectionProcessor;
import cn.nexus.infrastructure.mq.reliable.ReliableMqConsumerRecordService;
import cn.nexus.infrastructure.mq.reliable.ReliableMqConsumerRecordService.StartResult;
import cn.nexus.types.event.relation.RelationCounterProjectEvent;
import com.rabbitmq.client.Channel;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class RelationCounterProjectConsumerTest {

    @Test
    void onPost_shouldProcessUnderPersistentIdempotencyBeforeAck() throws Exception {
        RelationCounterProjectionProcessor processor = Mockito.mock(RelationCounterProjectionProcessor.class);
        ReliableMqConsumerRecordService consumerRecordService = Mockito.mock(ReliableMqConsumerRecordService.class);
        RecordingTransactionManager transactionManager = new RecordingTransactionManager();
        RelationCounterProjectConsumer consumer = new RelationCounterProjectConsumer(
                processor,
                consumerRecordService,
                new TransactionTemplate(transactionManager));
        RelationCounterProjectEvent event = new RelationCounterProjectEvent();
        event.setEventId("relation-counter:900");
        event.setRelationEventId(900L);
        event.setEventType("POST");
        event.setSourceId(11L);
        event.setTargetId(101L);
        event.setStatus("PUBLISHED");
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(77L);
        Message message = new Message(new byte[0], properties);
        Channel channel = Mockito.mock(Channel.class);
        when(consumerRecordService.startManual("relation-counter:900", "RelationCounterProjectConsumer", "{}"))
                .thenReturn(StartResult.STARTED);

        consumer.onPost(event, message, channel);

        verify(consumerRecordService).startManual("relation-counter:900", "RelationCounterProjectConsumer", "{}");
        verify(processor).process(event);
        verify(consumerRecordService).markDone("relation-counter:900", "RelationCounterProjectConsumer");
        verify(channel).basicAck(77L, false);
        org.assertj.core.api.Assertions.assertThat(transactionManager.events)
                .containsExactly("begin", "commit");
        org.mockito.Mockito.inOrder(consumerRecordService, processor, channel)
                .verify(consumerRecordService).startManual("relation-counter:900", "RelationCounterProjectConsumer", "{}");
        org.mockito.Mockito.inOrder(consumerRecordService, processor, channel)
                .verify(processor).process(event);
        org.mockito.Mockito.inOrder(consumerRecordService, processor, channel)
                .verify(consumerRecordService).markDone("relation-counter:900", "RelationCounterProjectConsumer");
        org.mockito.Mockito.inOrder(consumerRecordService, processor, channel)
                .verify(channel).basicAck(77L, false);
    }

    @Test
    void onPost_shouldRequeueWithoutProjectionWhenAnotherConsumerIsProcessing() throws Exception {
        RelationCounterProjectionProcessor processor = Mockito.mock(RelationCounterProjectionProcessor.class);
        ReliableMqConsumerRecordService consumerRecordService = Mockito.mock(ReliableMqConsumerRecordService.class);
        RecordingTransactionManager transactionManager = new RecordingTransactionManager();
        RelationCounterProjectConsumer consumer = new RelationCounterProjectConsumer(
                processor,
                consumerRecordService,
                new TransactionTemplate(transactionManager));
        RelationCounterProjectEvent event = new RelationCounterProjectEvent();
        event.setEventId("relation-counter:901");
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(78L);
        Message message = new Message(new byte[0], properties);
        Channel channel = Mockito.mock(Channel.class);
        when(consumerRecordService.startManual("relation-counter:901", "RelationCounterProjectConsumer", "{}"))
                .thenReturn(ReliableMqConsumerRecordService.StartResult.IN_PROGRESS);

        consumer.onPost(event, message, channel);

        verify(processor, Mockito.never()).process(event);
        verify(channel).basicNack(78L, false, true);
        verify(channel, Mockito.never()).basicAck(78L, false);
    }

    static class RecordingTransactionManager extends AbstractPlatformTransactionManager {
        final List<String> events = new ArrayList<>();

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            events.add("begin");
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            events.add("commit");
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            events.add("rollback");
        }
    }
}
