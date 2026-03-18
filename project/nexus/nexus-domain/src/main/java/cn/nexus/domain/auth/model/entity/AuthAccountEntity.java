package cn.nexus.domain.auth.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 认证账号实体。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthAccountEntity {
    private Long accountId;
    private Long userId;
    private String phone;
    private String passwordHash;
    private Long passwordUpdatedAt;
    private Long lastLoginAt;
    private Long createTime;
    private Long updateTime;
}
