package cn.nexus.api.user.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户隐私设置更新请求：当前仅 needApproval。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserPrivacyUpdateRequestDTO {
    /** 是否需要关注审批；必填。 */
    private Boolean needApproval;
}

