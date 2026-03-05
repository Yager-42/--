package cn.nexus.trigger.mq.consumer.strategy;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Primary
@Component
public class RoutingPostLikeCount2DbStrategy implements PostLikeCount2DbStrategy {

    private final String model;
    private final SnapshotPostLikeCount2DbStrategy snapshotStrategy;
    private final DeltaPostLikeCount2DbStrategy deltaStrategy;

    public RoutingPostLikeCount2DbStrategy(@Value("${reaction.count.model:snapshot}") String model,
                                          SnapshotPostLikeCount2DbStrategy snapshotStrategy,
                                          DeltaPostLikeCount2DbStrategy deltaStrategy) {
        this.model = model;
        this.snapshotStrategy = snapshotStrategy;
        this.deltaStrategy = deltaStrategy;
    }

    @Override
    public void handle(List<Message> messages) {
        select().handle(messages);
    }

    private PostLikeCount2DbStrategy select() {
        if ("delta".equalsIgnoreCase(model)) {
            return deltaStrategy;
        }
        if (!"snapshot".equalsIgnoreCase(model)) {
            log.warn("unknown reaction.count.model={}, fallback to snapshot", model);
        }
        return snapshotStrategy;
    }
}

