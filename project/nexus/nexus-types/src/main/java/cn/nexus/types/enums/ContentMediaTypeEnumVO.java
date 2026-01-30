package cn.nexus.types.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 媒体类型枚举（对应 content_post.media_type）。
 */
@Getter
@AllArgsConstructor
public enum ContentMediaTypeEnumVO {

    TEXT(0, "纯文"),
    IMAGE(1, "图文"),
    VIDEO(2, "视频");

    private final int code;
    private final String desc;

    public static ContentMediaTypeEnumVO fromCode(Integer code) {
        if (code == null) {
            return null;
        }
        for (ContentMediaTypeEnumVO e : values()) {
            if (e.code == code) {
                return e;
            }
        }
        return null;
    }
}

