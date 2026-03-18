package cn.nexus.api.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 注册请求。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthRegisterRequestDTO {
    private String phone;
    private String smsCode;
    private String password;
    private String nickname;
    private String avatarUrl;
}
