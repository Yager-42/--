package cn.nexus.trigger.mq.consumer.strategy;

import cn.nexus.domain.social.adapter.repository.IReactionRepository;
import cn.nexus.domain.social.model.valobj.ReactionTargetTypeEnumVO;
import cn.nexus.domain.social.model.valobj.ReactionTargetVO;
import cn.nexus.domain.social.model.valobj.ReactionTypeEnumVO;
import cn.nexus.trigger.mq.support.SimpleRateLimiter;
import cn.nexus.types.event.interaction.ReactionCountDeltaEvent;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class DeltaPostLikeCount2DbStrategy implements PostLikeCount2DbStrategy {

    private static final TypeReference<List<ReactionCountDeltaEvent>> LIST_TYPE = new TypeReference<>() {
    };

    private final IReactionRepository reactionRepository;
    private final ObjectMapper objectMapper;
    private final SimpleRateLimiter limiter;

    public DeltaPostLikeCount2DbStrategy(IReactionRepository reactionRepository,
                                        ObjectMapper objectMapper,
                                        @Value("${mq-consumer.count-like2db.rate-limit:50}") double permitsPerSecond) {
        this.reactionRepository = reactionRepository;
        this.objectMapper = objectMapper;
        this.limiter = new SimpleRateLimiter(permitsPerSecond);
    }

    @Override
    public void handle(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }

        List<ReactionCountDeltaEvent> all = new ArrayList<>();
        for (Message m : messages) {
            List<ReactionCountDeltaEvent> one = parseList(m);
            if (one != null && !one.isEmpty()) {
                all.addAll(one);
            }
        }

        if (all.isEmpty()) {
            return;
        }

        limiter.acquire();

        for (ReactionCountDeltaEvent e : all) {
            if (e == null || e.getTargetId() == null || e.getDelta() == null) {
                continue;
            }
            long delta = e.getDelta();
            if (delta == 0L) {
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
            if (e.getEventId() == null || e.getEventId().isBlank()) {
                log.warn("delta 计数事件缺少 eventId，已跳过。targetType={}, targetId={}, reactionType={}, delta={}",
                        e.getTargetType(), e.getTargetId(), e.getReactionType(), delta);
                continue;
            }
            reactionRepository.applyCountDeltaOnce(target, e.getEventId(), delta);
        }
    }

    private List<ReactionCountDeltaEvent> parseList(Message m) {
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
            ReactionCountDeltaEvent one = objectMapper.readValue(json, ReactionCountDeltaEvent.class);
            return one == null ? null : List.of(one);
        } catch (Exception ex) {
            log.warn("parse count delta failed, raw={}", json, ex);
            return null;
        }
    }
}
