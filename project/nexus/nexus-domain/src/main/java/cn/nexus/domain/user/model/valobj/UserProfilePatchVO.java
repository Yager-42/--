package cn.nexus.domain.user.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户 Profile Patch：null=不改；""=清空（仅 avatarUrl 支持清空）；nickname 空白一律非法。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserProfilePatchVO {
    private String nickname;
    private String avatarUrl;
}

