package cn.nexus.infrastructure.adapter.counter.kafka;

import cn.nexus.domain.counter.adapter.port.ICounterEventProducer;
import cn.nexus.domain.counter.model.event.CounterDeltaEvent;
import cn.nexus.domain.counter.model.event.CounterTopics;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CounterEventProducer implements ICounterEventProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public void publish(CounterDeltaEvent event) {
        if (event == null || event.getTargetType() == null || event.getTargetId() == null
                || event.getMetric() == null || event.getSlot() == null || event.getDelta() == null) {
            return;
        }
        try {
            String key = event.getTargetType() + ":" + event.getTargetId();
            kafkaTemplate.send(CounterTopics.COUNTER_EVENTS, key, objectMapper.writeValueAsString(event));
        } catch (Exception e) {
            log.warn("publish counter delta event failed, event={}", event, e);
        }
    }
}
