package cn.nexus.trigger.mq.consumer.strategy;

import cn.nexus.domain.social.adapter.port.IReactionCachePort;
import cn.nexus.domain.social.model.valobj.ReactionTargetTypeEnumVO;
import cn.nexus.domain.social.model.valobj.ReactionTargetVO;
import cn.nexus.domain.social.model.valobj.ReactionTypeEnumVO;
import cn.nexus.trigger.mq.config.CountPostLike2SearchIndexMqConfig;
import cn.nexus.trigger.mq.config.CountPostLikeMqConfig;
import cn.nexus.types.event.interaction.LikeUnlikePostEvent;
import cn.nexus.types.event.interaction.ReactionCountSnapshotEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class SnapshotPostLikeCountAggregateStrategy implements PostLikeCountAggregateStrategy {

    private final IReactionCachePort reactionCachePort;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public void handle(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }

        LinkedHashSet<Long> postIds = new LinkedHashSet<>();
        LinkedHashSet<Long> creatorIds = new LinkedHashSet<>();

        for (Message m : messages) {
            LikeUnlikePostEvent e = parse(m);
            if (e == null) {
                continue;
            }
            if (e.getPostId() != null) {
                postIds.add(e.getPostId());
            }
            if (e.getPostCreatorId() != null) {
                creatorIds.add(e.getPostCreatorId());
            }
        }

        if (postIds.isEmpty() && creatorIds.isEmpty()) {
            return;
        }

        List<ReactionCountSnapshotEvent> snapshots = new ArrayList<>(postIds.size() + creatorIds.size());

        for (Long postId : postIds) {
            ReactionTargetVO target = ReactionTargetVO.builder()
                    .targetType(ReactionTargetTypeEnumVO.POST)
                    .targetId(postId)
                    .reactionType(ReactionTypeEnumVO.LIKE)
                    .build();
            long cnt = reactionCachePort.getCountFromRedis(target);
            snapshots.add(snapshot("POST", postId, "LIKE", cnt));
        }

        for (Long creatorId : creatorIds) {
            ReactionTargetVO target = ReactionTargetVO.builder()
                    .targetType(ReactionTargetTypeEnumVO.USER)
                    .targetId(creatorId)
                    .reactionType(ReactionTypeEnumVO.LIKE)
                    .build();
            long cnt = reactionCachePort.getCountFromRedis(target);
            snapshots.add(snapshot("USER", creatorId, "LIKE", cnt));
        }

        if (snapshots.isEmpty()) {
            return;
        }

        rabbitTemplate.convertAndSend(CountPostLikeMqConfig.EXCHANGE, CountPostLikeMqConfig.ROUTING_KEY, snapshots);
        rabbitTemplate.convertAndSend(CountPostLike2SearchIndexMqConfig.EXCHANGE,
                CountPostLike2SearchIndexMqConfig.ROUTING_KEY,
                snapshots);
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

    private ReactionCountSnapshotEvent snapshot(String targetType, Long targetId, String reactionType, long cnt) {
        ReactionCountSnapshotEvent e = new ReactionCountSnapshotEvent();
        e.setTargetType(targetType);
        e.setTargetId(targetId);
        e.setReactionType(reactionType);
        e.setCount(Math.max(0L, cnt));
        return e;
    }
}
