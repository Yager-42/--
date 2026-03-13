package cn.nexus.domain.social.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 发布尝试状态枚举值对象。
 */
@Getter
@AllArgsConstructor
public enum ContentPublishAttemptStatusEnumVO {
    CREATED(0, "已创建"),
    RISK_REJECTED(1, "风控拒绝"),
    PENDING_REVIEW(7, "待审核"),
    TRANSCODING(2, "转码中"),
    READY_TO_PUBLISH(3, "可发布"),
    PUBLISHED(4, "已发布"),
    FAILED(5, "失败"),
    CANCELED(6, "已取消");

    private final int code;
    private final String desc;
}

