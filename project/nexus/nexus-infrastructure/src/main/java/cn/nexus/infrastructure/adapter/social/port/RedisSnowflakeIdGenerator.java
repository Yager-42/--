package cn.nexus.infrastructure.adapter.social.port;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 基于 Redis 的 Snowflake ID 生成器。
 *
 * @author codex
 * @since 2026-01-09
 */
@Component
@RequiredArgsConstructor
public class RedisSnowflakeIdGenerator {

    private static final String WORKER_KEY = "social:id:worker";
    private static final long EPOCH = 1704067200000L;
    private static final int WORKER_BITS = 10;
    private static final int SEQ_BITS = 12;
    private static final long MAX_WORKER = 1L << WORKER_BITS;
    private static final long SEQ_MASK = (1L << SEQ_BITS) - 1;
    private static final int WORKER_SHIFT = SEQ_BITS;
    private static final int TIME_SHIFT = WORKER_BITS + SEQ_BITS;

    private final StringRedisTemplate redisTemplate;
    private final AtomicLong state = new AtomicLong(0L);
    private volatile long workerId;

    /**
     * 初始化 workerId，确保多机实例不会重复。
     *
     * @throws IllegalStateException 当 workerId 超过上限时抛出
     */
    @PostConstruct
    public void init() {
        Long allocated = redisTemplate.opsForValue().increment(WORKER_KEY);
        if (allocated == null) {
            throw new IllegalStateException("workerId 分配失败");
        }
        long candidate = allocated - 1;
        if (candidate < 0 || candidate >= MAX_WORKER) {
            throw new IllegalStateException("workerId 超出上限: " + candidate);
        }
        workerId = candidate;
    }

    /**
     * 生成全局唯一 ID。
     *
     * @return {@code long} 全局唯一 ID
     */
    public long nextId() {
        while (true) {
            long current = state.get();
            long lastTs = current >>> SEQ_BITS;
            long seq = current & SEQ_MASK;
            long ts = now();
            if (ts < lastTs) {
                ts = waitNextMillis(lastTs);
            }
            if (ts == lastTs) {
                if (seq >= SEQ_MASK) {
                    ts = waitNextMillis(lastTs);
                    seq = 0;
                } else {
                    seq++;
                }
            } else {
                seq = 0;
            }
            long newState = (ts << SEQ_BITS) | seq;
            if (state.compareAndSet(current, newState)) {
                return ((ts - EPOCH) << TIME_SHIFT) | (workerId << WORKER_SHIFT) | seq;
            }
        }
    }

    private long waitNextMillis(long lastTs) {
        long ts = now();
        while (ts <= lastTs) {
            ts = now();
        }
        return ts;
    }

    private long now() {
        return System.currentTimeMillis();
    }
}
