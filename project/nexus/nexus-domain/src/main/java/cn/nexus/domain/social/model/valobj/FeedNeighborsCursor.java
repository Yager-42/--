package cn.nexus.domain.social.model.valobj;

/**
 * NEIGHBORS 流 cursor/token 工具：格式固定为 {@code NEI:{seedPostId}:{offset}}。
 *
 * <p>seedPostId 必填；offset 是扫描指针（不是“已返回条数”），必须为 >=0 的整数。</p>
 *
 * @author codex
 * @since 2026-01-26
 */
public final class FeedNeighborsCursor {

    private static final String PREFIX = "NEI:";

    private FeedNeighborsCursor() {
    }

    /**
     * 解析 token；非法返回 null。
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
        long seedPostId;
        try {
            seedPostId = Long.parseLong(parts[1]);
        } catch (NumberFormatException ignored) {
            return null;
        }
        if (seedPostId <= 0) {
            return null;
        }
        long offset;
        try {
            offset = Long.parseLong(parts[2]);
        } catch (NumberFormatException ignored) {
            return null;
        }
        if (offset < 0) {
            return null;
        }
        return new Parsed(seedPostId, offset);
    }

    /**
     * 格式化 token（offset 会被钳制为 >=0）。
     */
    public static String format(long seedPostId, long offset) {
        if (seedPostId <= 0) {
            return null;
        }
        long normalized = Math.max(0L, offset);
        return PREFIX + seedPostId + ":" + normalized;
    }

    public record Parsed(long seedPostId, long offset) {
    }
}

