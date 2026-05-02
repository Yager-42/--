package cn.nexus.infrastructure.config;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * HotKey 统一桥接层，基于本地分片滑动窗口计数识别热点 key。
 */
@Component
public class HotKeyStoreBridge {

    private static final int DEFAULT_WINDOW_SECONDS = 60;
    private static final int DEFAULT_SEGMENT_SECONDS = 10;

    private final HotKeyProperties hotKeyProperties;
    private final int segmentCount;
    private final long rotateDelayMillis;
    private final ConcurrentMap<String, LongAdder[]> counters = new ConcurrentHashMap<>();
    private final AtomicInteger activeSegment = new AtomicInteger();

    public enum Level {
        NONE,
        LOW,
        MEDIUM,
        HIGH
    }

    public HotKeyStoreBridge(HotKeyProperties hotKeyProperties) {
        this.hotKeyProperties = hotKeyProperties;
        this.segmentCount = normalizeSegmentCount(hotKeyProperties);
        this.rotateDelayMillis = (long) normalizedSegmentSecondsForSchedule(hotKeyProperties) * 1000L;
    }

    public boolean isHotKey(String key) {
        if (!hotKeyProperties.isEnabled() || !StringUtils.hasText(key)) {
            return false;
        }
        LongAdder[] segments = counters.computeIfAbsent(key, ignored -> newSegments());
        segments[activeSegment.get()].increment();
        return heat(segments) >= hotKeyProperties.getLevelLow();
    }

    public int heat(String key) {
        if (!hotKeyProperties.isEnabled() || !StringUtils.hasText(key)) {
            return 0;
        }
        LongAdder[] segments = counters.get(key);
        if (segments == null) {
            return 0;
        }
        return heat(segments);
    }

    public Level level(String key) {
        int heat = heat(key);
        if (heat >= hotKeyProperties.getLevelHigh()) {
            return Level.HIGH;
        }
        if (heat >= hotKeyProperties.getLevelMedium()) {
            return Level.MEDIUM;
        }
        if (heat >= hotKeyProperties.getLevelLow()) {
            return Level.LOW;
        }
        return Level.NONE;
    }

    @Scheduled(
            initialDelayString = "#{@hotKeyStoreBridge.rotateDelayMillis()}",
            fixedDelayString = "#{@hotKeyStoreBridge.rotateDelayMillis()}"
    )
    public synchronized void rotate() {
        int nextSegment = (activeSegment.get() + 1) % segmentCount;
        counters.entrySet().removeIf(entry -> {
            LongAdder[] segments = entry.getValue();
            segments[nextSegment].reset();
            return isCold(segments);
        });
        activeSegment.set(nextSegment);
    }

    public void reset(String key) {
        if (!StringUtils.hasText(key)) {
            return;
        }
        counters.remove(key);
    }

    public void startClient() {
        // Compatibility hook for HotKeyClientInitializer. Local detection needs no external client.
    }

    public long rotateDelayMillis() {
        return rotateDelayMillis;
    }

    private LongAdder[] newSegments() {
        LongAdder[] segments = new LongAdder[segmentCount];
        for (int i = 0; i < segmentCount; i++) {
            segments[i] = new LongAdder();
        }
        return segments;
    }

    private int heat(LongAdder[] segments) {
        long total = 0;
        for (LongAdder segment : segments) {
            total += segment.sum();
            if (total >= Integer.MAX_VALUE) {
                return Integer.MAX_VALUE;
            }
        }
        return (int) total;
    }

    private boolean isCold(LongAdder[] segments) {
        for (LongAdder segment : segments) {
            if (segment.sum() > 0) {
                return false;
            }
        }
        return true;
    }

    private int normalizeSegmentCount(HotKeyProperties properties) {
        int windowSeconds = properties.getWindowSeconds();
        if (windowSeconds <= 0) {
            return 1;
        }
        int segmentSeconds = Math.max(1, properties.getSegmentSeconds());
        return ((windowSeconds - 1) / segmentSeconds) + 1;
    }

    private int normalizedSegmentSecondsForSchedule(HotKeyProperties properties) {
        return positiveOrDefault(properties.getSegmentSeconds(), DEFAULT_SEGMENT_SECONDS);
    }

    private int positiveOrDefault(int value, int defaultValue) {
        if (value > 0) {
            return value;
        }
        return defaultValue;
    }
}
