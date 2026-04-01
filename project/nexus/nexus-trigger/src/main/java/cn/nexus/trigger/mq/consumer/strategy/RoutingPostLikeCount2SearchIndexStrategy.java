package cn.nexus.trigger.mq.consumer.strategy;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Slf4j
@Primary
@Component
public class RoutingPostLikeCount2SearchIndexStrategy implements PostLikeCount2SearchIndexStrategy {

    private final String model;
    private final SnapshotPostLikeCount2SearchIndexStrategy snapshotStrategy;

    public RoutingPostLikeCount2SearchIndexStrategy(@Value("${reaction.count.model:snapshot}") String model,
                                                    SnapshotPostLikeCount2SearchIndexStrategy snapshotStrategy) {
        this.model = model;
        this.snapshotStrategy = snapshotStrategy;
    }

    @Override
    public void handle(List<Message> messages) {
        if (!"snapshot".equalsIgnoreCase(model)) {
            log.warn("reaction.count.model={} has no dedicated search-index strategy, fallback to snapshot", model);
        }
        snapshotStrategy.handle(messages);
    }
}
