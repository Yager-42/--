package cn.nexus.domain.social.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 评论展示 VO（读侧用）。
 *
 * <p>注意：nickname/avatarUrl 不在评论表（interaction_comment）里，读侧由 {@code IUserBaseRepository}
 * 批量补全后写入本 VO。</p>
 *
 * @author codex
 * @since 2026-01-20
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CommentViewVO {
    private Long commentId;
    private Long postId;
    private Long userId;
    /** 读侧必须返回：由 IUserBaseRepository 批量补全 */
    private String nickname;
    /** 读侧必须返回：由 IUserBaseRepository 批量补全 */
    private String avatarUrl;
    private Long rootId;
    private Long parentId;
    private Long replyToId;
    private String content;
    private Integer status;
    /** 毫秒时间戳（与 CommentResponseDTO.createTime 一致） */
    private Long createTime;
}
