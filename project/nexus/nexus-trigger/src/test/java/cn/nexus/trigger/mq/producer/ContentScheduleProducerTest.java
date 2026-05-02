package cn.nexus.trigger.mq.producer;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import cn.nexus.infrastructure.mq.reliable.ReliableMqOutboxService;
import cn.nexus.trigger.mq.config.ContentScheduleDelayConfig;
import cn.nexus.trigger.mq.event.ContentScheduleTriggerEvent;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class ContentScheduleProducerTest {

    @Test
    void sendDelay_shouldSaveOutboxWithDelayHeader() {
        ReliableMqOutboxService reliableMqOutboxService = Mockito.mock(ReliableMqOutboxService.class);
        ContentScheduleProducer producer = new ContentScheduleProducer(reliableMqOutboxService);

        producer.sendDelay(123L, 5000L);

        ArgumentCaptor<ContentScheduleTriggerEvent> payloadCaptor =
                ArgumentCaptor.forClass(ContentScheduleTriggerEvent.class);
        verify(reliableMqOutboxService).save(
                Mockito.anyString(),
                Mockito.eq(ContentScheduleDelayConfig.EXCHANGE),
                Mockito.eq(ContentScheduleDelayConfig.ROUTING_KEY),
                payloadCaptor.capture(),
                Mockito.eq(Map.of("x-delay", 5000L))
        );
        org.junit.jupiter.api.Assertions.assertEquals(123L, payloadCaptor.getValue().getTaskId());
    }

    @Test
    void sendDelay_shouldClampNegativeDelayHeaderToZero() {
        ReliableMqOutboxService reliableMqOutboxService = Mockito.mock(ReliableMqOutboxService.class);
        ContentScheduleProducer producer = new ContentScheduleProducer(reliableMqOutboxService);

        producer.sendDelay(123L, -1L);

        verify(reliableMqOutboxService).save(
                Mockito.anyString(),
                Mockito.eq(ContentScheduleDelayConfig.EXCHANGE),
                Mockito.eq(ContentScheduleDelayConfig.ROUTING_KEY),
                Mockito.any(ContentScheduleTriggerEvent.class),
                Mockito.eq(Map.of("x-delay", 0L))
        );
    }

    @Test
    void sendDelay_shouldKeepExistingNullTaskNoopValidation() {
        ReliableMqOutboxService reliableMqOutboxService = Mockito.mock(ReliableMqOutboxService.class);
        ContentScheduleProducer producer = new ContentScheduleProducer(reliableMqOutboxService);

        producer.sendDelay(null, 5000L);

        verify(reliableMqOutboxService, never()).save(Mockito.anyString(), Mockito.anyString(), Mockito.anyString(),
                Mockito.any(), anyMap());
    }
}
