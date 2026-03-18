package cn.nexus.api.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 管理员详情。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthAdminDTO {
    private Long userId;
    private String phone;
    private String status;
    private String nickname;
    private String avatarUrl;
}
