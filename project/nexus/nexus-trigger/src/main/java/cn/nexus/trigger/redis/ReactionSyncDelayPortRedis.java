package cn.nexus.trigger.redis;

import cn.nexus.domain.social.adapter.port.IReactionDelayPort;
import cn.nexus.domain.social.model.valobj.ReactionTargetVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 点赞同步：Redis ZSET 延迟队列入队端口（替换 RabbitMQ x-delay）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "reaction.sync.mode", havingValue = "redis", matchIfMissing = true)
public class ReactionSyncDelayPortRedis implements IReactionDelayPort {

    private static final String KEY_DELAY = "interact:reaction:sync:{rs}:delay";
    private static final String KEY_DLQ = "interact:reaction:sync:{rs}:dlq";

    private static final DefaultRedisScript<Long> ENQUEUE_SCRIPT = script("lua/reaction_sync/enqueue.lua", Long.class);

    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public void sendDelay(ReactionTargetVO target, long delayMs) {
        String job = buildJob(target);
        if (job == null) {
            return;
        }
        long dueMs = System.currentTimeMillis() + Math.max(0L, delayMs);
        try {
            stringRedisTemplate.execute(ENQUEUE_SCRIPT, List.of(KEY_DELAY), job, String.valueOf(dueMs));
        } catch (Exception e) {
            log.warn("reaction sync enqueue failed, job={}, dueMs={}", job, dueMs, e);
            sendToDLQ(job + "|enqueue_failed");
        }
    }

    @Override
    public void sendToDLQ(String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) {
            return;
        }
        try {
            stringRedisTemplate.opsForList().leftPush(KEY_DLQ, rawMessage);
        } catch (Exception e) {
            log.warn("reaction sync dlq push failed, raw={}", rawMessage, e);
        }
    }

    private String buildJob(ReactionTargetVO target) {
        if (target == null || target.getTargetType() == null || target.getTargetId() == null || target.getReactionType() == null) {
            return null;
        }
        return target.getTargetType().getCode() + ":" + target.getTargetId() + ":" + target.getReactionType().getCode();
    }

    private static <T> DefaultRedisScript<T> script(String classpath, Class<T> resultType) {
        DefaultRedisScript<T> s = new DefaultRedisScript<>();
        s.setLocation(new ClassPathResource(classpath));
        s.setResultType(resultType);
        return s;
    }
}

