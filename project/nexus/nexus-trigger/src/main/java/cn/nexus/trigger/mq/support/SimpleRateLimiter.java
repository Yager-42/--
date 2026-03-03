package cn.nexus.trigger.mq.support;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Very small leaky-bucket rate limiter (no extra deps).
 *
 * <p>It blocks the caller to keep a stable processing rate.</p>
 */
public final class SimpleRateLimiter {

    private final long intervalNanos;
    private final AtomicLong nextNanos = new AtomicLong(0L);

    /**
        * @param permitsPerSecond permits per second, <=0 means unlimited
        */
    public SimpleRateLimiter(double permitsPerSecond) {
        if (permitsPerSecond <= 0) {
            this.intervalNanos = 0L;
            return;
        }
        double pps = Math.max(0.0001d, permitsPerSecond);
        this.intervalNanos = (long) (1_000_000_000d / pps);
    }

    public void acquire() {
        if (intervalNanos <= 0L) {
            return;
        }
        long now = System.nanoTime();
        while (true) {
            long prev = nextNanos.get();
            long at = Math.max(now, prev);
            long next = at + intervalNanos;
            if (nextNanos.compareAndSet(prev, next)) {
                long wait = at - now;
                if (wait > 0L) {
                    LockSupport.parkNanos(wait);
                }
                return;
            }
        }
    }
}
