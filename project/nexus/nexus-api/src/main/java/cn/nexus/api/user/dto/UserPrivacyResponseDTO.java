package cn.nexus.api.user.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户隐私设置返回：当前仅 needApproval。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserPrivacyResponseDTO {
    private Boolean needApproval;
}

