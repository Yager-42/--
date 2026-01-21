package cn.nexus.domain.social.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户最小展示信息：给评论/通知等读接口补全 nickname/avatar。
 *
 * <p>注意：当前建议从 user_base 表读取，其中 nickname = username。</p>
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

