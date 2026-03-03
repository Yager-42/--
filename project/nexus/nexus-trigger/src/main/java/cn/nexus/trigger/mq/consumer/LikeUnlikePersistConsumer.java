package cn.nexus.trigger.mq.consumer;

import cn.nexus.domain.social.adapter.repository.IReactionRepository;
import cn.nexus.domain.social.model.valobj.ReactionTargetTypeEnumVO;
import cn.nexus.domain.social.model.valobj.ReactionTargetVO;
import cn.nexus.domain.social.model.valobj.ReactionTypeEnumVO;
import cn.nexus.trigger.mq.config.LikeUnlikeMqConfig;
import cn.nexus.trigger.mq.support.SimpleRateLimiter;
import cn.nexus.types.event.interaction.LikeUnlikePostEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ConsumerGroup A: persist post-like relation to DB.
 */
@Slf4j
@Component
public class LikeUnlikePersistConsumer {

    private final IReactionRepository reactionRepository;
    private final ObjectMapper objectMapper;
    private final SimpleRateLimiter limiter;

    public LikeUnlikePersistConsumer(IReactionRepository reactionRepository,
                                     ObjectMapper objectMapper,
                                     @Value("${mq-consumer.like-unlike.rate-limit:200}") double permitsPerSecond) {
        this.reactionRepository = reactionRepository;
        this.objectMapper = objectMapper;
        this.limiter = new SimpleRateLimiter(permitsPerSecond);
    }

    @RabbitListener(queues = LikeUnlikeMqConfig.QUEUE_PERSIST, containerFactory = "likeUnlikeBatchListenerContainerFactory")
    public void onMessages(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }

        Map<String, LikeUnlikePostEvent> lastByUserPost = new HashMap<>(messages.size());
        for (Message m : messages) {
            LikeUnlikePostEvent e = parse(m);
            if (e == null || e.getUserId() == null || e.getPostId() == null || e.getType() == null) {
                continue;
            }
            String key = e.getUserId() + ":" + e.getPostId();
            LikeUnlikePostEvent old = lastByUserPost.get(key);
            if (old == null || isNewer(e, old)) {
                lastByUserPost.put(key, e);
            }
        }

        if (lastByUserPost.isEmpty()) {
            return;
        }

        // Rate-limit once per batch to protect DB.
        limiter.acquire();

        Map<Long, List<Long>> likeByPost = new HashMap<>();
        Map<Long, List<Long>> unlikeByPost = new HashMap<>();
        for (LikeUnlikePostEvent e : lastByUserPost.values()) {
            if (e.getUserId() == null || e.getPostId() == null || e.getType() == null) {
                continue;
            }
            Long postId = e.getPostId();
            Long userId = e.getUserId();
            if (e.getType() == 1) {
                likeByPost.computeIfAbsent(postId, k -> new java.util.ArrayList<>()).add(userId);
            } else {
                unlikeByPost.computeIfAbsent(postId, k -> new java.util.ArrayList<>()).add(userId);
            }
        }

        // Apply last-write-wins state to DB.
        for (Map.Entry<Long, List<Long>> en : likeByPost.entrySet()) {
            ReactionTargetVO target = ReactionTargetVO.builder()
                    .targetType(ReactionTargetTypeEnumVO.POST)
                    .targetId(en.getKey())
                    .reactionType(ReactionTypeEnumVO.LIKE)
                    .build();
            reactionRepository.batchUpsert(target, en.getValue());
        }
        for (Map.Entry<Long, List<Long>> en : unlikeByPost.entrySet()) {
            ReactionTargetVO target = ReactionTargetVO.builder()
                    .targetType(ReactionTargetTypeEnumVO.POST)
                    .targetId(en.getKey())
                    .reactionType(ReactionTypeEnumVO.LIKE)
                    .build();
            reactionRepository.batchDelete(target, en.getValue());
        }
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

    private boolean isNewer(LikeUnlikePostEvent a, LikeUnlikePostEvent b) {
        Long ta = a == null ? null : a.getCreateTime();
        Long tb = b == null ? null : b.getCreateTime();
        long va = ta == null ? 0L : ta;
        long vb = tb == null ? 0L : tb;
        if (va != vb) {
            return va > vb;
        }
        return parseSeq(a == null ? null : a.getEventId()) > parseSeq(b == null ? null : b.getEventId());
    }

    private long parseSeq(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return 0L;
        }
        String v = eventId.trim();
        int idx = v.indexOf("rid-");
        if (idx >= 0) {
            v = v.substring(idx + 4);
        }
        try {
            return Long.parseLong(v);
        } catch (Exception ignored) {
            return 0L;
        }
    }
}
