package cn.nexus.api.response;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * 统一响应包装，遵循DDD规范。
 *
 * @param <T> 业务数据类型
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Response<T> {
    /** 业务编码 */
    private String code;
    /** 业务信息 */
    private String info;
    /** 业务数据 */
    private T data;

    /**
    * 快速构造成功响应。
    */
    public static <T> Response<T> success(String code, String info, T data) {
        return Response.<T>builder()
                .code(code)
                .info(info)
                .data(data)
                .build();
    }
}
