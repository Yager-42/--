package cn.nexus.trigger.mq.consumer;

import cn.nexus.domain.social.adapter.repository.IReactionRepository;
import cn.nexus.domain.social.model.valobj.ReactionTargetTypeEnumVO;
import cn.nexus.domain.social.model.valobj.ReactionTargetVO;
import cn.nexus.domain.social.model.valobj.ReactionTypeEnumVO;
import cn.nexus.trigger.mq.config.CountPostLikeMqConfig;
import cn.nexus.trigger.mq.support.SimpleRateLimiter;
import cn.nexus.types.event.interaction.ReactionCountSnapshotEvent;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * ConsumerGroup C: write count snapshots to DB (rate limited).
 */
@Slf4j
@Component
public class PostLikeCount2DbConsumer {

    private static final TypeReference<List<ReactionCountSnapshotEvent>> LIST_TYPE = new TypeReference<>() {
    };

    private final IReactionRepository reactionRepository;
    private final ObjectMapper objectMapper;
    private final SimpleRateLimiter limiter;

    public PostLikeCount2DbConsumer(IReactionRepository reactionRepository,
                                   ObjectMapper objectMapper,
                                   @Value("${mq-consumer.count-like2db.rate-limit:50}") double permitsPerSecond) {
        this.reactionRepository = reactionRepository;
        this.objectMapper = objectMapper;
        this.limiter = new SimpleRateLimiter(permitsPerSecond);
    }

    @RabbitListener(queues = CountPostLikeMqConfig.QUEUE, containerFactory = "likeUnlikeBatchListenerContainerFactory")
    public void onMessages(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }

        List<ReactionCountSnapshotEvent> all = new ArrayList<>();
        for (Message m : messages) {
            List<ReactionCountSnapshotEvent> one = parseList(m);
            if (one != null && !one.isEmpty()) {
                all.addAll(one);
            }
        }

        if (all.isEmpty()) {
            return;
        }

        limiter.acquire();

        for (ReactionCountSnapshotEvent e : all) {
            if (e == null || e.getTargetId() == null || e.getCount() == null) {
                continue;
            }
            ReactionTargetTypeEnumVO targetType = ReactionTargetTypeEnumVO.from(e.getTargetType());
            ReactionTypeEnumVO reactionType = ReactionTypeEnumVO.from(e.getReactionType());
            if (targetType == null || reactionType == null) {
                continue;
            }
            ReactionTargetVO target = ReactionTargetVO.builder()
                    .targetType(targetType)
                    .targetId(e.getTargetId())
                    .reactionType(reactionType)
                    .build();
            reactionRepository.upsertCount(target, Math.max(0L, e.getCount()));
        }
    }

    private List<ReactionCountSnapshotEvent> parseList(Message m) {
        if (m == null || m.getBody() == null) {
            return null;
        }
        String json = new String(m.getBody(), StandardCharsets.UTF_8);
        if (json.isBlank()) {
            return null;
        }
        try {
            if (json.trim().startsWith("[")) {
                return objectMapper.readValue(json, LIST_TYPE);
            }
            ReactionCountSnapshotEvent one = objectMapper.readValue(json, ReactionCountSnapshotEvent.class);
            return one == null ? null : List.of(one);
        } catch (Exception ex) {
            log.warn("parse count snapshot failed, raw={}", json, ex);
            return null;
        }
    }
}
