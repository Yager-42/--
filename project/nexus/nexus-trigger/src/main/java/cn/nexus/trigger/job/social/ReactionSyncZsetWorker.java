package cn.nexus.trigger.job.social;

import cn.nexus.domain.social.model.valobj.ReactionTargetTypeEnumVO;
import cn.nexus.domain.social.model.valobj.ReactionTargetVO;
import cn.nexus.domain.social.model.valobj.ReactionTypeEnumVO;
import cn.nexus.domain.social.service.IReactionLikeService;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 点赞同步：Redis ZSET + Lua 延迟队列 worker（替换 RabbitMQ 固定 1s 重试）。
 *
 * @author m0_52354773
 * @author codex
 * @since 2026-03-01
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "reaction.sync.mode", havingValue = "redis", matchIfMissing = true)
public class ReactionSyncZsetWorker {

    private static final String KEY_DELAY = "interact:reaction:sync:{rs}:delay";
    private static final String KEY_PROCESSING = "interact:reaction:sync:{rs}:processing";
    private static final String KEY_ATTEMPT = "interact:reaction:sync:{rs}:attempt";
    private static final String KEY_DLQ = "interact:reaction:sync:{rs}:dlq";

    private static final int POLL_LIMIT = 50;
    private static final long LEASE_MS = 60_000L;

    private static final long BASE_MS = 200L;
    private static final long MAX_MS = 600_000L;
    private static final long JITTER_MS = 200L;
    private static final int MAX_ATTEMPT = 30;

    private static final DefaultRedisScript<List> POLL_SCRIPT = script("lua/reaction_sync/poll.lua", List.class);
    private static final DefaultRedisScript<Long> ACK_SCRIPT = script("lua/reaction_sync/ack.lua", Long.class);
    private static final DefaultRedisScript<List> FAIL_SCRIPT = script("lua/reaction_sync/fail.lua", List.class);

    private final StringRedisTemplate stringRedisTemplate;
    private final IReactionLikeService reactionLikeService;

    /**
     * 执行 pollAndSync 逻辑。
     *
     */
    @Scheduled(fixedDelay = 200)
    public void pollAndSync() {
        List<?> out;
        try {
            out = stringRedisTemplate.execute(
                    POLL_SCRIPT,
                    List.of(KEY_DELAY, KEY_PROCESSING, KEY_ATTEMPT),
                    String.valueOf(System.currentTimeMillis()),
                    String.valueOf(POLL_LIMIT),
                    String.valueOf(LEASE_MS));
        } catch (Exception e) {
            log.warn("reaction sync poll failed", e);
            return;
        }

        if (out == null || out.isEmpty()) {
            return;
        }

        // Lua 返回：job1, attempt1, job2, attempt2 ...
        for (int i = 0; i + 1 < out.size(); i += 2) {
            String job = String.valueOf(out.get(i));
            int attempt = parseInt(out.get(i + 1), 0);
            handle(job, attempt);
        }
    }

    private void handle(String job, int attempt) {
        Parsed parsed = parseJob(job);
        if (parsed == null) {
            ack(job);
            pushDlq(job + "|" + attempt + "|parse_failed");
            return;
        }

        try {
            reactionLikeService.syncTarget(parsed.target());
            ack(job);
        } catch (Exception e) {
            long dueMs = System.currentTimeMillis() + backoffMs(Math.max(1, attempt));
            fail(job, dueMs, "sync_failed");
            log.error("reaction sync failed, job={}, attempt={}", job, attempt, e);
        }
    }

    private void ack(String job) {
        try {
            stringRedisTemplate.execute(ACK_SCRIPT, List.of(KEY_PROCESSING, KEY_ATTEMPT), job);
        } catch (Exception e) {
            log.warn("reaction sync ack failed, job={}", job, e);
        }
    }

    private void fail(String job, long dueMs, String reason) {
        try {
            stringRedisTemplate.execute(
                    FAIL_SCRIPT,
                    List.of(KEY_PROCESSING, KEY_DELAY, KEY_ATTEMPT, KEY_DLQ),
                    job,
                    String.valueOf(dueMs),
                    String.valueOf(MAX_ATTEMPT),
                    reason == null ? "" : reason);
        } catch (Exception e) {
            log.warn("reaction sync fail script failed, job={}, dueMs={}", job, dueMs, e);
        }
    }

    private void pushDlq(String rawMessage) {
        try {
            stringRedisTemplate.opsForList().leftPush(KEY_DLQ, rawMessage);
        } catch (Exception e) {
            log.warn("reaction sync dlq push failed, raw={}", rawMessage, e);
        }
    }

    private long backoffMs(int attempt) {
        long delay = BASE_MS;
        int a = Math.max(1, attempt);
        for (int i = 1; i < a; i++) {
            if (delay >= MAX_MS) {
                delay = MAX_MS;
                break;
            }
            delay = Math.min(MAX_MS, delay * 2);
        }
        long jitter = ThreadLocalRandom.current().nextLong(0L, JITTER_MS + 1);
        return delay + jitter;
    }

    private Parsed parseJob(String job) {
        if (job == null || job.isBlank()) {
            return null;
        }
        String[] parts = job.split(":");
        if (parts.length != 3) {
            return null;
        }
        ReactionTargetTypeEnumVO targetType = ReactionTargetTypeEnumVO.from(parts[0]);
        Long targetId;
        try {
            targetId = Long.valueOf(parts[1]);
        } catch (Exception e) {
            return null;
        }
        ReactionTypeEnumVO reactionType = ReactionTypeEnumVO.from(parts[2]);
        if (targetType == null || targetId == null || reactionType == null) {
            return null;
        }
        ReactionTargetVO target = ReactionTargetVO.builder()
                .targetType(targetType)
                .targetId(targetId)
                .reactionType(reactionType)
                .build();
        return new Parsed(target);
    }

    private int parseInt(Object v, int defaultValue) {
        if (v == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private static <T> DefaultRedisScript<T> script(String classpath, Class<T> resultType) {
        DefaultRedisScript<T> s = new DefaultRedisScript<>();
        s.setLocation(new ClassPathResource(classpath));
        s.setResultType(resultType);
        return s;
    }

    private record Parsed(ReactionTargetVO target) {
    }
}
