package cn.nexus.trigger.mq.consumer.strategy;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.domain.counter.adapter.port.IObjectCounterPort;
import cn.nexus.domain.counter.model.valobj.ObjectCounterTarget;
import cn.nexus.domain.counter.model.valobj.ObjectCounterType;
import cn.nexus.domain.social.model.valobj.ReactionTargetTypeEnumVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

class SnapshotPostLikeCountAggregateStrategyTest {

    @Test
    void handle_shouldReadPostLikeCountsFromObjectCounter() throws Exception {
        IObjectCounterPort objectCounterPort = Mockito.mock(IObjectCounterPort.class);
        ObjectMapper mapper = new ObjectMapper();
        SnapshotPostLikeCountAggregateStrategy strategy =
                new SnapshotPostLikeCountAggregateStrategy(objectCounterPort, mapper);

        when(objectCounterPort.getCount(counterTarget(101L))).thenReturn(5L);
        when(objectCounterPort.getCount(counterTarget(202L))).thenReturn(7L);

        Message first = jsonMessage("""
                {"userId":1,"postId":101,"postCreatorId":8,"type":1,"createTime":1000}
                """);
        Message second = jsonMessage("""
                {"userId":2,"postId":202,"postCreatorId":9,"type":1,"createTime":1000}
                """);

        strategy.handle(List.of(first, second));

        verify(objectCounterPort).getCount(counterTarget(101L));
        verify(objectCounterPort).getCount(counterTarget(202L));
    }

    @Test
    void handle_shouldIgnoreInvalidMessagesWithoutWritingSnapshots() {
        IObjectCounterPort objectCounterPort = Mockito.mock(IObjectCounterPort.class);
        SnapshotPostLikeCountAggregateStrategy strategy =
                new SnapshotPostLikeCountAggregateStrategy(objectCounterPort, new ObjectMapper());

        Message invalid = new Message("not-json".getBytes(java.nio.charset.StandardCharsets.UTF_8), new MessageProperties());

        strategy.handle(List.of(invalid));

        verify(objectCounterPort, never()).getCount(any());
    }

    private ObjectCounterTarget counterTarget(Long postId) {
        return ObjectCounterTarget.builder()
                .targetType(ReactionTargetTypeEnumVO.POST)
                .targetId(postId)
                .counterType(ObjectCounterType.LIKE)
                .build();
    }

    private Message jsonMessage(String json) {
        return new Message(json.getBytes(StandardCharsets.UTF_8), new MessageProperties());
    }
}
