package cn.nexus.types.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 内容可见性枚举（对应 content_post.visibility）。
 *
 * <p>约束：值映射需与 {@code ContentService.parseVisibility(...)} 保持一致。</p>
 */
@Getter
@AllArgsConstructor
public enum ContentPostVisibilityEnumVO {

    PUBLIC(0, "公开"),
    PRIVATE(2, "仅自己可见");

    private final int code;
    private final String desc;

    public static ContentPostVisibilityEnumVO fromCode(Integer code) {
        if (code == null) {
            return null;
        }
        for (ContentPostVisibilityEnumVO e : values()) {
            if (e.code == code) {
                return e;
            }
        }
        return null;
    }
}
