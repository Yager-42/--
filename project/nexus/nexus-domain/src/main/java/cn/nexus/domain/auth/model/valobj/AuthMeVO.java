package cn.nexus.domain.auth.model.valobj;

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
public class AuthMeVO {
    private Long userId;
    private String phone;
    private String status;
    private String nickname;
    private String avatarUrl;
}
