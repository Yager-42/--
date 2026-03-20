package cn.nexus.domain.social.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 发布尝试状态枚举值对象。
 *
 * @author {$authorName}
 * @since 2026-01-11
 */
@Getter
@AllArgsConstructor
public enum ContentPublishAttemptStatusEnumVO {
    /** 已创建。 */
    CREATED(0, "已创建"),
    /** 风控拒绝。 */
    RISK_REJECTED(1, "风控拒绝"),
    /** 待审核。 */
    PENDING_REVIEW(7, "待审核"),
    /** 转码中。 */
    TRANSCODING(2, "转码中"),
    /** 可发布。 */
    READY_TO_PUBLISH(3, "可发布"),
    /** 已发布。 */
    PUBLISHED(4, "已发布"),
    /** 失败。 */
    FAILED(5, "失败"),
    /** 已取消。 */
    CANCELED(6, "已取消");

    private final int code;
    private final String desc;
}

