package cn.nexus.domain.social.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户隐私设置。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserPrivacyEntity {
    private Long userId;
    /**
     * 是否需要审批关注。
     */
    private Boolean needApproval;
}
