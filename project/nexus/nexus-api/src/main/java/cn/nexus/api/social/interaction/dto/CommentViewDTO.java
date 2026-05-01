package cn.nexus.api.social.interaction.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 评论展示 DTO（读接口用）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CommentViewDTO {
    private Long commentId;
    private Long postId;
    private Long userId;
    private String nickname;
    private String avatarUrl;
    private Long rootId;
    private Long parentId;
    private Long replyToId;
    private String content;
    /** 1=正常；0=待审核；2=删除 */
    private Integer status;
    private Long createTime;
}
