package cn.nexus.api.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 短信验证码登录请求。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthSmsLoginRequestDTO {
    private String phone;
    private String smsCode;
}
