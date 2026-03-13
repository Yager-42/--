package cn.nexus.domain.social.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 发布尝试转码状态枚举值对象。
 */
@Getter
@AllArgsConstructor
public enum ContentPublishAttemptTranscodeStatusEnumVO {
    NOT_STARTED(0, "未开始"),
    PROCESSING(1, "处理中"),
    DONE(2, "完成"),
    FAILED(3, "失败");

    private final int code;
    private final String desc;
}

