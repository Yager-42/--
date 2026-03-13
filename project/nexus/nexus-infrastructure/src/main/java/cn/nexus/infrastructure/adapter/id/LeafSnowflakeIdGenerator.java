package cn.nexus.infrastructure.adapter.id;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Leaf Snowflake ID 生成器（workerId 由 Zookeeper 分配）。
 *
 * <p>实现参考 xiaohashu 的 leaf snowflake：10 bits workerId + 12 bits sequence。</p>
 */
@Component
@RequiredArgsConstructor
public class LeafSnowflakeIdGenerator {

    /**
     * Thu Nov 04 2010 09:42:54 GMT+0800（Leaf 默认 epoch）。
     */
    private static final long TWEPOCH = 1288834974657L;

    private static final long WORKER_ID_BITS = 10L;
    private static final long MAX_WORKER_ID = ~(-1L << WORKER_ID_BITS);
    private static final long SEQUENCE_BITS = 12L;
    private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;
    private static final long TIMESTAMP_LEFT_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;
    private static final long SEQUENCE_MASK = ~(-1L << SEQUENCE_BITS);

    private final ZookeeperWorkerIdAllocator workerIdAllocator;

    private long workerId;
    private long sequence = 0L;
    private long lastTimestamp = -1L;

    @PostConstruct
    public void init() {
        workerId = workerIdAllocator.workerId();
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalStateException("workerId must be between 0 and 1023, got " + workerId);
        }
        if (timeGen() <= TWEPOCH) {
            throw new IllegalStateException("currentTime must be greater than twepoch");
        }
    }

    public synchronized long nextId() {
        long timestamp = timeGen();
        if (timestamp < lastTimestamp) {
            long offset = lastTimestamp - timestamp;
            if (offset <= 5) {
                try {
                    Thread.sleep(offset << 1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("snowflake wait interrupted", e);
                }
                timestamp = timeGen();
                if (timestamp < lastTimestamp) {
                    throw new IllegalStateException("clock moved backwards");
                }
            } else {
                throw new IllegalStateException("clock moved backwards too much, offsetMs=" + offset);
            }
        }

        if (lastTimestamp == timestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0) {
                sequence = ThreadLocalRandom.current().nextInt(100);
                timestamp = tilNextMillis(lastTimestamp);
            }
        } else {
            sequence = ThreadLocalRandom.current().nextInt(100);
        }

        lastTimestamp = timestamp;
        return ((timestamp - TWEPOCH) << TIMESTAMP_LEFT_SHIFT) | (workerId << WORKER_ID_SHIFT) | sequence;
    }

    private long tilNextMillis(long lastTimestamp) {
        long timestamp = timeGen();
        while (timestamp <= lastTimestamp) {
            timestamp = timeGen();
        }
        return timestamp;
    }

    private long timeGen() {
        return System.currentTimeMillis();
    }
}

