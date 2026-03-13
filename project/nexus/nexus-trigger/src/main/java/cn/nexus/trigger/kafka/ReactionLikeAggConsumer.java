package cn.nexus.trigger.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 消费 Flink 5 分钟聚合结果：回写 Redis 热榜 + 写入 window_ms（动态窗口）。
 *
 * @author codex
 * @since 2026-01-20
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReactionLikeAggConsumer {

    private static final String TOPIC = "topic_like_5m_agg";
    private static final String HOT_ZSET_PREFIX = "hot:like:5m:";
    private static final String WINDOW_MS_PREFIX = "interact:reaction:window_ms:";
    private static final Duration WINDOW_TTL = Duration.ofSeconds(60);

    private final StringRedisTemplate stringRedisTemplate;
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
            Long score = longVal(root, "like_add_count");
            if (targetType == null || targetId == null || reactionType == null || score == null) {
                return;
            }

            // 1) 热榜：hot:like:5m:{targetType} -> ZSET(member=targetId, score=like_add_count)
            stringRedisTemplate.opsForZSet().add(HOT_ZSET_PREFIX + targetType, String.valueOf(targetId), score.doubleValue());

            // 2) window_ms：按热度分段映射；低于阈值则不写（让 key 自然过期回到 defaultMs）
            Long windowMs = mapWindowMs(score);
            if (windowMs == null) {
                return;
            }
            String tag = "{" + targetType + ":" + targetId + ":" + reactionType + "}";
            stringRedisTemplate.opsForValue().set(WINDOW_MS_PREFIX + tag, String.valueOf(windowMs), WINDOW_TTL);
        } catch (Exception e) {
            log.error("consume like_5m_agg failed, raw={}", rawMessage, e);
        }
    }

    private Long mapWindowMs(long score) {
        if (score >= 5000L) {
            return 1000L;
        }
        if (score >= 2000L) {
            return 3000L;
        }
        if (score >= 500L) {
            return 10000L;
        }
        return null;
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

