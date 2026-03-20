package cn.nexus.domain.auth.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 管理员详情视图。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthAdminVO {
    private Long userId;
    private String phone;
    private String status;
    private String nickname;
    private String avatarUrl;
}
