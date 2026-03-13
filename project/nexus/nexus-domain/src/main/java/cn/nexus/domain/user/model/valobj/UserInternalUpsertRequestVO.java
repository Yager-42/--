package cn.nexus.domain.user.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 网关同步写入口请求（update-only）：仅用于系统同步更新，不负责创建用户。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserInternalUpsertRequestVO {
    /** 必填：用户 ID。 */
    private Long userId;
    /** 必填：不可变 handle，用于一致性校验。 */
    private String username;

    /** 可选：展示昵称（可改）。 */
    private String nickname;
    /** 可选：头像 URL（允许清空）。 */
    private String avatarUrl;
    /** 可选：关注是否需要审批；null=不改。 */
    private Boolean needApproval;
    /** 可选：ACTIVE/DEACTIVATED；null=不改。 */
    private String status;
}

