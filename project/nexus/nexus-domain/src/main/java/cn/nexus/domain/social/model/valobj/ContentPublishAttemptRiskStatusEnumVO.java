package cn.nexus.domain.social.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 发布尝试风控状态枚举值对象。
 */
@Getter
@AllArgsConstructor
public enum ContentPublishAttemptRiskStatusEnumVO {
    NOT_EVALUATED(0, "未评估"),
    PASSED(1, "通过"),
    REJECTED(2, "拒绝"),
    REVIEW_REQUIRED(3, "待审核");

    private final int code;
    private final String desc;
}

