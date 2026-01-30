package cn.nexus.types.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 内容状态枚举（对应 content_post.status）。
 *
 * <p>约束：这是跨模块契约，禁止在业务代码里写魔法数字。</p>
 */
@Getter
@AllArgsConstructor
public enum ContentPostStatusEnumVO {

    DRAFT(0, "草稿"),
    PENDING_REVIEW(1, "审核中"),
    PUBLISHED(2, "已发布"),
    REJECTED(3, "审核拒绝"),
    DELETED(6, "删除");

    private final int code;
    private final String desc;

    public static ContentPostStatusEnumVO fromCode(Integer code) {
        if (code == null) {
            return null;
        }
        for (ContentPostStatusEnumVO e : values()) {
            if (e.code == code) {
                return e;
            }
        }
        return null;
    }
}

