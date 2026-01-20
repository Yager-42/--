package cn.nexus.trigger.mq.producer;

import cn.nexus.domain.social.adapter.port.IReactionDelayPort;
import cn.nexus.domain.social.model.valobj.ReactionTargetVO;
import cn.nexus.trigger.mq.config.ReactionSyncDelayConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * 点赞同步延时消息生产者。
 *
 * <p>payload 使用 JSON 字符串（最简单可用）。</p>
 *
 * @author codex
 * @since 2026-01-20
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReactionSyncProducer implements IReactionDelayPort {

    private final RabbitTemplate rabbitTemplate;

    @Override
    public void sendDelay(ReactionTargetVO target, long delayMs) {
        sendDelayRaw(buildMessage(target, 0), delayMs);
    }

    @Override
    public void sendToDLQ(String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) {
            return;
        }
        rabbitTemplate.convertAndSend(ReactionSyncDelayConfig.DLX_EXCHANGE, ReactionSyncDelayConfig.DLX_ROUTING_KEY, rawMessage);
    }

    public void sendDelayRaw(String rawMessage, long delayMs) {
        if (rawMessage == null || rawMessage.isBlank()) {
            return;
        }
        long finalDelay = Math.max(0L, delayMs);
        rabbitTemplate.convertAndSend(
                ReactionSyncDelayConfig.EXCHANGE,
                ReactionSyncDelayConfig.ROUTING_KEY,
                rawMessage,
                msg -> {
                    msg.getMessageProperties().setHeader("x-delay", finalDelay);
                    return msg;
                });
    }

    public void sendDelayWithAttempt(ReactionTargetVO target, int attempt, long delayMs) {
        sendDelayRaw(buildMessage(target, attempt), delayMs);
    }

    private String buildMessage(ReactionTargetVO target, int attempt) {
        if (target == null || target.getTargetType() == null || target.getTargetId() == null || target.getReactionType() == null) {
            return null;
        }
        int a = Math.max(0, attempt);
        return "{"
                + "\"targetType\":\"" + target.getTargetType().getCode() + "\","
                + "\"targetId\":" + target.getTargetId() + ","
                + "\"reactionType\":\"" + target.getReactionType().getCode() + "\","
                + "\"attempt\":" + a
                + "}";
    }
}

