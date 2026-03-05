package cn.nexus.trigger.mq.consumer.strategy;

import cn.nexus.trigger.mq.config.CountPostLikeMqConfig;
import cn.nexus.types.event.interaction.LikeUnlikePostEvent;
import cn.nexus.types.event.interaction.ReactionCountDeltaEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class DeltaPostLikeCountAggregateStrategy implements PostLikeCountAggregateStrategy {

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public void handle(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }

        List<ReactionCountDeltaEvent> deltas = new ArrayList<>(messages.size() * 2);
        Set<String> dedup = new HashSet<>(messages.size() * 2);

        for (Message m : messages) {
            LikeUnlikePostEvent e = parse(m);
            if (e == null || e.getType() == null) {
                continue;
            }
            long d = e.getType() == 1 ? 1L : -1L;

            if (e.getPostId() != null) {
                addDelta(deltas, dedup, e.getEventId(), "POST", e.getPostId(), "LIKE", d);
            }
            if (e.getPostCreatorId() != null) {
                addDelta(deltas, dedup, e.getEventId(), "USER", e.getPostCreatorId(), "LIKE", d);
            }
        }

        if (deltas.isEmpty()) {
            return;
        }

        rabbitTemplate.convertAndSend(CountPostLikeMqConfig.EXCHANGE, CountPostLikeMqConfig.ROUTING_KEY, deltas);
    }

    private LikeUnlikePostEvent parse(Message m) {
        if (m == null || m.getBody() == null) {
            return null;
        }
        try {
            String json = new String(m.getBody(), StandardCharsets.UTF_8);
            if (json.isBlank()) {
                return null;
            }
            return objectMapper.readValue(json, LikeUnlikePostEvent.class);
        } catch (Exception e) {
            log.warn("parse like/unlike message failed", e);
            return null;
        }
    }

    private void addDelta(List<ReactionCountDeltaEvent> out,
                          Set<String> dedup,
                          String eventId,
                          String targetType,
                          Long targetId,
                          String reactionType,
                          long d) {
        if (out == null || dedup == null) {
            return;
        }
        if (eventId == null || eventId.isBlank()) {
            return;
        }
        if (targetType == null || targetType.isBlank() || targetId == null || reactionType == null || reactionType.isBlank()) {
            return;
        }
        if (d == 0L) {
            return;
        }

        String key = eventId + "|" + targetType + "|" + targetId + "|" + reactionType;
        if (!dedup.add(key)) {
            return;
        }
        out.add(delta(eventId, targetType, targetId, reactionType, d));
    }

    private ReactionCountDeltaEvent delta(String eventId, String targetType, Long targetId, String reactionType, long d) {
        ReactionCountDeltaEvent e = new ReactionCountDeltaEvent();
        e.setEventId(eventId);
        e.setTargetType(targetType);
        e.setTargetId(targetId);
        e.setReactionType(reactionType);
        e.setDelta(d);
        return e;
    }
}
