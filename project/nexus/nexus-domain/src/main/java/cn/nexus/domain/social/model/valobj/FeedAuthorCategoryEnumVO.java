package cn.nexus.domain.social.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Feed 作者类别枚举值对象。
 *
 * <p>注意：进入 domain 后必须用枚举 code 表达作者类别，避免字符串到处乱飞。</p>
 *
 * @author codex
 * @since 2026-02-28
 */
@Getter
@AllArgsConstructor
public enum FeedAuthorCategoryEnumVO {
    NORMAL(0, "普通用户"),
    BIGV(1, "大V");

    private final int code;
    private final String desc;
}

