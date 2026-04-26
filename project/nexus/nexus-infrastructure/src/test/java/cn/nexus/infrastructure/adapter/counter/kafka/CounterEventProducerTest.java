package cn.nexus.infrastructure.adapter.counter.kafka;

import static org.mockito.Mockito.verify;

import cn.nexus.domain.counter.model.event.CounterDeltaEvent;
import cn.nexus.domain.counter.model.event.CounterTopics;
import cn.nexus.domain.counter.model.valobj.ObjectCounterType;
import cn.nexus.domain.social.model.valobj.ReactionTargetTypeEnumVO;
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
                .entityType(ReactionTargetTypeEnumVO.POST)
                .entityId(42L)
                .metric(ObjectCounterType.LIKE)
                .idx(1)
                .userId(7L)
                .delta(1L)
                .build());

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(Mockito.eq(CounterTopics.COUNTER_EVENTS), Mockito.eq("POST:42"), payload.capture());
        assertTrue(payload.getValue().contains("\"entityType\":\"POST\""));
        assertTrue(payload.getValue().contains("\"entityId\":42"));
        assertTrue(payload.getValue().contains("\"metric\":\"LIKE\""));
        assertTrue(payload.getValue().contains("\"idx\":1"));
        assertTrue(payload.getValue().contains("\"userId\":7"));
        assertTrue(payload.getValue().contains("\"delta\":1"));
    }
}
