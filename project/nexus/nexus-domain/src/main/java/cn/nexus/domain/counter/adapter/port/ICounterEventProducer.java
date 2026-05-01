package cn.nexus.domain.counter.adapter.port;

import cn.nexus.domain.counter.model.event.CounterDeltaEvent;

public interface ICounterEventProducer {

    void publish(CounterDeltaEvent event);
}
