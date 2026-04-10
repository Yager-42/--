package cn.nexus.trigger.mq.consumer.strategy;

import cn.nexus.trigger.search.support.SearchIndexUpsertService;
import cn.nexus.types.event.interaction.ReactionCountSnapshotEvent;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class SnapshotPostLikeCount2SearchIndexStrategy {

    private static final TypeReference<List<ReactionCountSnapshotEvent>> LIST_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;
    private final SearchIndexUpsertService searchIndexUpsertService;

    public SnapshotPostLikeCount2SearchIndexStrategy(ObjectMapper objectMapper,
                                                     SearchIndexUpsertService searchIndexUpsertService) {
        this.objectMapper = objectMapper;
        this.searchIndexUpsertService = searchIndexUpsertService;
    }

    public void handle(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }

        Map<Long, Long> postLikeSnapshots = new LinkedHashMap<>();
        for (Message message : messages) {
            List<ReactionCountSnapshotEvent> events = parseList(message);
            if (events == null || events.isEmpty()) {
                continue;
            }
            for (ReactionCountSnapshotEvent event : events) {
                if (event == null || event.getTargetId() == null || event.getCount() == null) {
                    continue;
                }
                if (!"POST".equalsIgnoreCase(event.getTargetType())) {
                    continue;
                }
                if (!"LIKE".equalsIgnoreCase(event.getReactionType())) {
                    continue;
                }
                postLikeSnapshots.put(event.getTargetId(), Math.max(0L, event.getCount()));
            }
        }

        for (Map.Entry<Long, Long> entry : postLikeSnapshots.entrySet()) {
            searchIndexUpsertService.upsertPost(entry.getKey(), entry.getValue());
        }
    }

    private List<ReactionCountSnapshotEvent> parseList(Message message) {
        if (message == null || message.getBody() == null) {
            return null;
        }
        String json = new String(message.getBody(), StandardCharsets.UTF_8);
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
            log.warn("parse count snapshot for search index failed, raw={}", json, ex);
            return null;
        }
    }
}
