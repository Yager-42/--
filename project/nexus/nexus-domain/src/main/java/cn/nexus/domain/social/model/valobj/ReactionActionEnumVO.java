package cn.nexus.domain.social.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 点赞动作枚举值对象（set-state，不是 toggle）。
 *
 * <p>ADD 表示 desiredState=1；REMOVE 表示 desiredState=0。</p>
 *
 * @author codex
 * @since 2026-01-20
 */
@Getter
@AllArgsConstructor
public enum ReactionActionEnumVO {
    ADD("ADD", "点赞"),
    REMOVE("REMOVE", "取消点赞");

    private final String code;
    private final String desc;

    /**
     * 解析动作。
     *
     * @param raw 原始值 {@link String}
     * @return {@link ReactionActionEnumVO}，解析失败返回 {@code null}
     */
    public static ReactionActionEnumVO from(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = raw.trim().toUpperCase();
        for (ReactionActionEnumVO e : values()) {
            if (e.code.equals(v)) {
                return e;
            }
        }
        return null;
    }

    /**
     * 将动作映射为 desiredState。
     *
     * @return {@code int} 1=想要点赞，0=想要取消
     */
    public int desiredState() {
        return this == ADD ? 1 : 0;
    }
}

