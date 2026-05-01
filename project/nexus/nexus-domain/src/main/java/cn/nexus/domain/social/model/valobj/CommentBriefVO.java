package cn.nexus.domain.social.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 评论最小信息：用于 rootId 计算与幂等删除判定（避免读整行）。
 *
 * <p>上线版额外用途：删除/置顶权限校验需要用到 userId（评论作者）。</p>
 *
 * @author codex
 * @since 2026-01-14
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CommentBriefVO {
    private Long commentId;
    private Long postId;
    private Long userId;
    private Long rootId;
    private Integer status;
}
