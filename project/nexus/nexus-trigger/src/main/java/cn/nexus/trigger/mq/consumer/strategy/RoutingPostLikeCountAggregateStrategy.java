package cn.nexus.trigger.mq.consumer.strategy;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.amqp.core.Message;

import java.util.List;

@Slf4j
@Primary
@Component
public class RoutingPostLikeCountAggregateStrategy implements PostLikeCountAggregateStrategy {

    private final String model;
    private final SnapshotPostLikeCountAggregateStrategy snapshotStrategy;
    private final DeltaPostLikeCountAggregateStrategy deltaStrategy;

    public RoutingPostLikeCountAggregateStrategy(@Value("${reaction.count.model:snapshot}") String model,
                                                 SnapshotPostLikeCountAggregateStrategy snapshotStrategy,
                                                 DeltaPostLikeCountAggregateStrategy deltaStrategy) {
        this.model = model;
        this.snapshotStrategy = snapshotStrategy;
        this.deltaStrategy = deltaStrategy;
    }

    @Override
    public void handle(List<Message> messages) {
        select().handle(messages);
    }

    private PostLikeCountAggregateStrategy select() {
        if ("delta".equalsIgnoreCase(model)) {
            return deltaStrategy;
        }
        if (!"snapshot".equalsIgnoreCase(model)) {
            log.warn("unknown reaction.count.model={}, fallback to snapshot", model);
        }
        return snapshotStrategy;
    }
}

