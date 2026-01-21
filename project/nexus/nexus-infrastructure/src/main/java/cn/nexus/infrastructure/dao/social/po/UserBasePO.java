package cn.nexus.infrastructure.dao.social.po;

import lombok.Data;

/**
 * 用户基础表映射，对应 user_base。
 *
 * @author codex
 * @since 2026-01-20
 */
@Data
public class UserBasePO {
    private Long userId;
    private String username;
    private String avatarUrl;
}

