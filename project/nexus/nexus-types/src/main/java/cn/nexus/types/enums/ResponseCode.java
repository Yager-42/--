package cn.nexus.types.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 全局响应码枚举，提供统一编码与描述。
 */
@Getter
@AllArgsConstructor
public enum ResponseCode {
    SUCCESS("0000", "成功"),
    UN_ERROR("0001", "未知失败"),
    ILLEGAL_PARAMETER("0002", "非法参数"),

    NOT_FOUND("0404", "资源不存在"),
    CONFLICT("0409", "数据冲突"),
    USER_DEACTIVATED("0410", "用户已停用");

    private final String code;
    private final String info;
}
