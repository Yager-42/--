package cn.nexus.domain.social.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户最小展示信息：给评论/通知等读接口补全 nickname/avatar。
 *
 * <p>注意：nickname 来自 user_base.nickname；迁移期若 nickname 为空，允许在仓储层 fallback 到 username（调用方不要写补丁）。</p>
 *
 * @author codex
 * @since 2026-01-20
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserBriefVO {
    private Long userId;
    private String nickname;
    private String avatarUrl;
}
