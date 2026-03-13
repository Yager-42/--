package cn.nexus.domain.social.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 点赞目标类型枚举值对象。
 *
 * <p>注意：外部接口仍然收 String；进入 domain 后必须先解析为枚举，避免字符串到处乱飞。</p>
 *
 * @author codex
 * @since 2026-01-20
 */
@Getter
@AllArgsConstructor
public enum ReactionTargetTypeEnumVO {
    POST("POST", "帖子"),
    COMMENT("COMMENT", "评论"),

    /**
     * 计数/派生目标：用户（例如某用户收到的点赞数）。
     *
     * <p>注意：当前对外接口并不开放对 USER 目标的“点赞事实写入”，仅用于计数链路。</p>
     */
    USER("USER", "用户");

    private final String code;
    private final String desc;

    /**
     * 解析目标类型。
     *
     * @param raw 原始值 {@link String}
     * @return {@link ReactionTargetTypeEnumVO}，解析失败返回 {@code null}
     */
    public static ReactionTargetTypeEnumVO from(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = raw.trim().toUpperCase();
        for (ReactionTargetTypeEnumVO e : values()) {
            if (e.code.equals(v)) {
                return e;
            }
        }
        return null;
    }
}

