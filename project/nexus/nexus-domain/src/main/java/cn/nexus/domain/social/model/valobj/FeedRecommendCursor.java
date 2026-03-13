package cn.nexus.domain.social.model.valobj;

/**
 * RECOMMEND 流 cursor/token 工具：严格固定格式，避免实现者“脑补”出多套协议。
 *
 * <p>格式：{@code REC:{sessionId}:{scanIndex}}</p>
 * <ul>
 *   <li>sessionId：会话 ID（短字符串即可，不做签名/安全校验）</li>
 *   <li>scanIndex：扫描指针（不是“已返回条数”），必须为 >=0 的整数</li>
 * </ul>
 *
 * @author codex
 * @since 2026-01-26
 */
public final class FeedRecommendCursor {

    private static final String PREFIX = "REC:";

    private FeedRecommendCursor() {
    }

    /**
     * 解析 token；非法返回 null（调用方按“首页”处理）。
     */
    public static Parsed parse(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        String raw = cursor.trim();
        if (!raw.startsWith(PREFIX)) {
            return null;
        }
        String[] parts = raw.split(":", -1);
        if (parts.length != 3) {
            return null;
        }
        String sessionId = parts[1];
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        long scanIndex;
        try {
            scanIndex = Long.parseLong(parts[2]);
        } catch (NumberFormatException ignored) {
            return null;
        }
        if (scanIndex < 0) {
            return null;
        }
        return new Parsed(sessionId, scanIndex);
    }

    /**
     * 格式化 token（scanIndex 会被钳制为 >=0）。
     */
    public static String format(String sessionId, long scanIndex) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        long normalized = Math.max(0L, scanIndex);
        return PREFIX + sessionId.trim() + ":" + normalized;
    }

    public record Parsed(String sessionId, long scanIndex) {
    }
}

