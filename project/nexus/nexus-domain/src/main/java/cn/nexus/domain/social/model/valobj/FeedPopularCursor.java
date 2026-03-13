package cn.nexus.domain.social.model.valobj;

/**
 * POPULAR 流 cursor/token 工具：格式固定为 {@code POP:{offset}}。
 *
 * <p>offset 是扫描指针（不是“已返回条数”），必须为 >=0 的整数。</p>
 *
 * @author codex
 * @since 2026-01-26
 */
public final class FeedPopularCursor {

    private static final String PREFIX = "POP:";

    private FeedPopularCursor() {
    }

    /**
     * 解析 token；非法返回 null（调用方按“首页 offset=0”处理）。
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
        if (parts.length != 2) {
            return null;
        }
        long offset;
        try {
            offset = Long.parseLong(parts[1]);
        } catch (NumberFormatException ignored) {
            return null;
        }
        if (offset < 0) {
            return null;
        }
        return new Parsed(offset);
    }

    /**
     * 格式化 token（offset 会被钳制为 >=0）。
     */
    public static String format(long offset) {
        long normalized = Math.max(0L, offset);
        return PREFIX + normalized;
    }

    public record Parsed(long offset) {
    }
}

