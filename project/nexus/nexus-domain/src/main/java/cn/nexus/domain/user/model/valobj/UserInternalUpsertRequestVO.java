package cn.nexus.domain.user.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 网关内部同步写请求。
 *
 * <p>这是 `update-only` 请求：只允许同步更新，不负责创建用户。</p>
 *
 * @author rr
 * @author codex
 * @since 2026-02-03
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserInternalUpsertRequestVO {
    /** 必填：用户 ID。 */
    private Long userId;
    /** 必填：不可变 `username`，只拿来做一致性校验。 */
    private String username;

    /** 可选：展示昵称，可更新。 */
    private String nickname;
    /** 可选：头像 URL，允许清空。 */
    private String avatarUrl;
    /** 可选：是否需要关注审批；`null = 不改`。 */
    private Boolean needApproval;
    /** 可选：`ACTIVE / DEACTIVATED`；`null = 不改`。 */
    private String status;
}
