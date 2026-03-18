package cn.nexus.api.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 当前登录用户信息。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthMeResponseDTO {
    private Long userId;
    private String phone;
    private String status;
    private String nickname;
    private String avatarUrl;
}
