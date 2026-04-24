package cn.nexus.trigger.mq.consumer.strategy;

import cn.nexus.domain.counter.adapter.port.IObjectCounterPort;
import cn.nexus.domain.counter.model.valobj.ObjectCounterTarget;
import cn.nexus.domain.counter.model.valobj.ObjectCounterType;
import cn.nexus.domain.social.model.valobj.ReactionTargetTypeEnumVO;
import cn.nexus.types.event.interaction.LikeUnlikePostEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class SnapshotPostLikeCountAggregateStrategy {

    private final IObjectCounterPort objectCounterPort;
    private final ObjectMapper objectMapper;

    public void handle(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }

        LinkedHashSet<Long> postIds = new LinkedHashSet<>();
        for (Message m : messages) {
            LikeUnlikePostEvent e = parse(m);
            if (e == null) {
                continue;
            }
            if (e.getPostId() != null) {
                postIds.add(e.getPostId());
            }
        }

        if (postIds.isEmpty()) {
            return;
        }

        for (Long postId : postIds) {
            objectCounterPort.getCount(counterTarget(postId));
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

    private ObjectCounterTarget counterTarget(Long postId) {
        return ObjectCounterTarget.builder()
                .targetType(ReactionTargetTypeEnumVO.POST)
                .targetId(postId)
                .counterType(ObjectCounterType.LIKE)
                .build();
    }
}
