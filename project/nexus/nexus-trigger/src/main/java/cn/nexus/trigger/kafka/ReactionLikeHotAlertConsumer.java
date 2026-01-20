package cn.nexus.trigger.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 消费 Flink 热点告警：最小实现只打 warn 日志。
 *
 * @author codex
 * @since 2026-01-20
 */
@Slf4j
@Component
public class ReactionLikeHotAlertConsumer {

    private static final String TOPIC = "topic_like_hot_alert";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @KafkaListener(topics = TOPIC)
    public void onMessage(String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) {
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(rawMessage);
            String targetType = text(root, "targetType");
            Long targetId = longVal(root, "targetId");
            String reactionType = text(root, "reactionType");
            Long likeAddCount = longVal(root, "like_add_count");
            Long threshold = longVal(root, "threshold");
            log.warn("like hot alert, targetType={}, targetId={}, reactionType={}, like_add_count={}, threshold={}",
                    targetType, targetId, reactionType, likeAddCount, threshold);
        } catch (Exception e) {
            log.error("consume like_hot_alert failed, raw={}", rawMessage, e);
        }
    }

    private String text(JsonNode root, String field) {
        if (root == null || field == null) {
            return null;
        }
        JsonNode v = root.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private Long longVal(JsonNode root, String field) {
        if (root == null || field == null) {
            return null;
        }
        JsonNode v = root.get(field);
        return v == null || v.isNull() ? null : v.asLong();
    }
}

