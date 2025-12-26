package cn.nexus.types.exception;

import lombok.Getter;

/**
 * 业务异常，携带编码与描述。
 */
@Getter
public class AppException extends RuntimeException {
    private final String code;
    private final String info;

    public AppException(String code, String info) {
        super(info);
        this.code = code;
        this.info = info;
    }

    public AppException(String code, String info, Throwable cause) {
        super(info, cause);
        this.code = code;
        this.info = info;
    }
}
