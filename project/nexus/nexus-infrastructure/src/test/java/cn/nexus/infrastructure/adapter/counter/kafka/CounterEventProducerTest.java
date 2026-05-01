package cn.nexus.infrastructure.adapter.counter.kafka;

import static org.mockito.Mockito.verify;

import cn.nexus.domain.counter.model.event.CounterDeltaEvent;
import cn.nexus.domain.counter.model.event.CounterTopics;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.kafka.core.KafkaTemplate;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CounterEventProducerTest {

    @Test
    void publishShouldSendCounterEventsJsonPayload() {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafkaTemplate = Mockito.mock(KafkaTemplate.class);
        CounterEventProducer producer = new CounterEventProducer(kafkaTemplate, new ObjectMapper());

        producer.publish(CounterDeltaEvent.builder()
                .targetType("post")
                .targetId(42L)
                .metric("like")
                .slot(1)
                .actorUserId(7L)
                .delta(1L)
                .build());

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(Mockito.eq(CounterTopics.COUNTER_EVENTS), Mockito.eq("post:42"), payload.capture());
        assertTrue(payload.getValue().contains("\"targetType\":\"post\""));
        assertTrue(payload.getValue().contains("\"targetId\":42"));
        assertTrue(payload.getValue().contains("\"metric\":\"like\""));
        assertTrue(payload.getValue().contains("\"slot\":1"));
        assertTrue(payload.getValue().contains("\"actorUserId\":7"));
        assertTrue(payload.getValue().contains("\"delta\":1"));
    }
}
