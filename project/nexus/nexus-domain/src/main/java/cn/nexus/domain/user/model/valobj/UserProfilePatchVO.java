package cn.nexus.domain.user.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户 `Profile` Patch 值对象。
 *
 * <p>`null = 不改`；空串只允许拿来清空 `avatarUrl`；`nickname` 只要是空白字符串就视为非法。</p>
 *
 * @author rr
 * @author codex
 * @since 2026-02-03
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserProfilePatchVO {
    private String nickname;
    private String avatarUrl;
}
