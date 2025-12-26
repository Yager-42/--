package cn.nexus.infrastructure.dao.social.po;

import lombok.Data;

/**
 * 用户隐私配置 PO。
 */
@Data
public class UserPrivacyPO {
    private Long userId;
    private Boolean needApproval;
}
