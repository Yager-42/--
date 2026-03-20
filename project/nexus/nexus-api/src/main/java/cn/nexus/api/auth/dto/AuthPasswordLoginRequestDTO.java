package cn.nexus.api.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 密码登录请求。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthPasswordLoginRequestDTO {
    private String phone;
    private String password;
}
