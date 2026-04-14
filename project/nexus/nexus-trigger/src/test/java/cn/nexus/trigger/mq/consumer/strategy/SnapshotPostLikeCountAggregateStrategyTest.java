package cn.nexus.trigger.mq.consumer.strategy;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.domain.counter.adapter.port.IObjectCounterPort;
import cn.nexus.domain.counter.model.valobj.ObjectCounterTarget;
import cn.nexus.domain.counter.model.valobj.ObjectCounterType;
import cn.nexus.domain.social.model.valobj.ReactionTargetTypeEnumVO;
import cn.nexus.trigger.mq.config.CountPostLike2SearchIndexMqConfig;
import cn.nexus.types.event.interaction.ReactionCountSnapshotEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class SnapshotPostLikeCountAggregateStrategyTest {

    @Test
    void handle_shouldPublishPostLikeSnapshotsFromObjectCounterReads() throws Exception {
        IObjectCounterPort objectCounterPort = Mockito.mock(IObjectCounterPort.class);
        RabbitTemplate rabbitTemplate = Mockito.mock(RabbitTemplate.class);
        ObjectMapper mapper = new ObjectMapper();
        SnapshotPostLikeCountAggregateStrategy strategy =
                new SnapshotPostLikeCountAggregateStrategy(objectCounterPort, rabbitTemplate, mapper);

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
        ArgumentCaptor<List> payloadCaptor = ArgumentCaptor.forClass(List.class);
        verify(rabbitTemplate).convertAndSend(
                eq(CountPostLike2SearchIndexMqConfig.EXCHANGE),
                eq(CountPostLike2SearchIndexMqConfig.ROUTING_KEY),
                payloadCaptor.capture());
        @SuppressWarnings("unchecked")
        List<ReactionCountSnapshotEvent> snapshots = payloadCaptor.getValue();
        long postSnapshotCount = snapshots.stream()
                .filter(event -> event != null
                        && "POST".equalsIgnoreCase(event.getTargetType())
                        && "LIKE".equalsIgnoreCase(event.getReactionType()))
                .count();
        org.junit.jupiter.api.Assertions.assertEquals(2, postSnapshotCount);
        org.junit.jupiter.api.Assertions.assertTrue(snapshots.stream().anyMatch(event -> event != null
                && "POST".equalsIgnoreCase(event.getTargetType())
                && Long.valueOf(101L).equals(event.getTargetId())
                && Long.valueOf(5L).equals(event.getCount())));
        org.junit.jupiter.api.Assertions.assertTrue(snapshots.stream().anyMatch(event -> event != null
                && "POST".equalsIgnoreCase(event.getTargetType())
                && Long.valueOf(202L).equals(event.getTargetId())
                && Long.valueOf(7L).equals(event.getCount())));
        org.junit.jupiter.api.Assertions.assertTrue(snapshots.stream().noneMatch(event -> event != null
                && "USER".equalsIgnoreCase(event.getTargetType())));
    }

    @Test
    void handle_shouldIgnoreInvalidMessagesWithoutWritingSnapshots() {
        IObjectCounterPort objectCounterPort = Mockito.mock(IObjectCounterPort.class);
        RabbitTemplate rabbitTemplate = Mockito.mock(RabbitTemplate.class);
        SnapshotPostLikeCountAggregateStrategy strategy =
                new SnapshotPostLikeCountAggregateStrategy(objectCounterPort, rabbitTemplate, new ObjectMapper());

        Message invalid = new Message("not-json".getBytes(java.nio.charset.StandardCharsets.UTF_8), new MessageProperties());

        strategy.handle(List.of(invalid));

        verify(objectCounterPort, never()).getCount(any());
        verify(rabbitTemplate, never()).convertAndSend(
                Mockito.anyString(),
                Mockito.anyString(),
                Mockito.<Object>any());
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
