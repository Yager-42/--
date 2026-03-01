package cn.nexus.trigger.mq.consumer;

import cn.nexus.domain.social.model.valobj.ReactionTargetTypeEnumVO;
import cn.nexus.domain.social.model.valobj.ReactionTargetVO;
import cn.nexus.domain.social.model.valobj.ReactionTypeEnumVO;
import cn.nexus.domain.social.service.IReactionLikeService;
import cn.nexus.trigger.mq.config.ReactionSyncDelayConfig;
import cn.nexus.trigger.mq.producer.ReactionSyncProducer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * 点赞同步延迟消息消费者：触发对某个 target 的“延迟落库”。
 *
 * @author codex
 * @since 2026-01-20
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "reaction.sync.mode", havingValue = "rabbit")
public class ReactionSyncConsumer {

    private static final int MAX_ATTEMPT = 30;
    private static final long RETRY_DELAY_MS = 1000L;

    private final IReactionLikeService reactionLikeService;
    private final ReactionSyncProducer reactionSyncProducer;
    private final StringRedisTemplate stringRedisTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @RabbitListener(queues = ReactionSyncDelayConfig.QUEUE)
    public void onMessage(String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) {
            return;
        }

        Parsed parsed = parse(rawMessage);
        if (parsed == null) {
            reactionSyncProducer.sendToDLQ(rawMessage);
            return;
        }

        ReactionTargetVO target = parsed.target();
        int attempt = parsed.attempt();

        String lockKey = "interact:reaction:lock:" + target.hashTag();
        String lockVal = UUID.randomUUID().toString();
        Boolean locked = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, lockVal, Duration.ofSeconds(60));
        if (Boolean.FALSE.equals(locked)) {
            rescheduleOrDlq(target, attempt, rawMessage, "lock_exists");
            return;
        }

        try {
            reactionLikeService.syncTarget(target);
        } catch (Exception e) {
            log.error("reaction sync failed, tag={}, attempt={}, raw={}", target.hashTag(), attempt, rawMessage, e);
            rescheduleOrDlq(target, attempt, rawMessage, "sync_failed");
        } finally {
            String val = stringRedisTemplate.opsForValue().get(lockKey);
            if (lockVal.equals(val)) {
                stringRedisTemplate.delete(lockKey);
            }
        }
    }

    private void rescheduleOrDlq(ReactionTargetVO target, int attempt, String rawMessage, String reason) {
        if (attempt >= MAX_ATTEMPT) {
            log.error("reaction sync dead-lettered, reason={}, tag={}, attempt={}, raw={}",
                    reason, target.hashTag(), attempt, rawMessage);
            reactionSyncProducer.sendToDLQ(rawMessage);
            return;
        }
        int next = attempt + 1;
        reactionSyncProducer.sendDelayWithAttempt(target, next, RETRY_DELAY_MS);
        log.warn("reaction sync rescheduled, reason={}, tag={}, attempt={} -> {}",
                reason, target.hashTag(), attempt, next);
    }

    private Parsed parse(String raw) {
        try {
            JsonNode root = objectMapper.readTree(raw);
            String targetTypeRaw = text(root, "targetType");
            Long targetId = longVal(root, "targetId");
            String reactionTypeRaw = text(root, "reactionType");
            Integer attempt = intVal(root, "attempt");

            ReactionTargetTypeEnumVO targetType = ReactionTargetTypeEnumVO.from(targetTypeRaw);
            ReactionTypeEnumVO reactionType = ReactionTypeEnumVO.from(reactionTypeRaw);
            if (targetType == null || reactionType == null || targetId == null) {
                return null;
            }
            int a = attempt == null ? 0 : Math.max(0, attempt);
            ReactionTargetVO target = ReactionTargetVO.builder()
                    .targetType(targetType)
                    .targetId(targetId)
                    .reactionType(reactionType)
                    .build();
            return new Parsed(target, a);
        } catch (Exception e) {
            log.error("parse reaction sync message failed, raw={}", raw, e);
            return null;
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

    private Integer intVal(JsonNode root, String field) {
        if (root == null || field == null) {
            return null;
        }
        JsonNode v = root.get(field);
        return v == null || v.isNull() ? null : v.asInt();
    }

    private record Parsed(ReactionTargetVO target, int attempt) {
    }
}

