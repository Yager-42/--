package cn.nexus.api.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 改密码请求。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthChangePasswordRequestDTO {
    private String oldPassword;
    private String newPassword;
}
