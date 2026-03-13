package cn.nexus.domain.social.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 态势类型枚举值对象。
 *
 * <p>当前链路主要处理 LIKE；保留枚举是为了将来扩展 LOVE/ANGRY。</p>
 *
 * @author codex
 * @since 2026-01-20
 */
@Getter
@AllArgsConstructor
public enum ReactionTypeEnumVO {
    LIKE("LIKE", "点赞"),
    LOVE("LOVE", "喜欢"),
    ANGRY("ANGRY", "生气");

    private final String code;
    private final String desc;

    /**
     * 解析态势类型。
     *
     * @param raw 原始值 {@link String}
     * @return {@link ReactionTypeEnumVO}，解析失败返回 {@code null}
     */
    public static ReactionTypeEnumVO from(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = raw.trim().toUpperCase();
        for (ReactionTypeEnumVO e : values()) {
            if (e.code.equals(v)) {
                return e;
            }
        }
        return null;
    }
}

