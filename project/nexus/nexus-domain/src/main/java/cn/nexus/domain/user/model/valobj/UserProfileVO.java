package cn.nexus.domain.user.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户 Profile 视图（领域侧）：username/nickname/avatarUrl。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserProfileVO {
    private Long userId;
    private String username;
    private String nickname;
    private String avatarUrl;
}

