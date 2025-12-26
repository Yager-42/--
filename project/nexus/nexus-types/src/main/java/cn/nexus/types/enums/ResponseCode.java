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
    ILLEGAL_PARAMETER("0002", "非法参数");

    private final String code;
    private final String info;
}
