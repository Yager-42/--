package cn.nexus.api.social.interaction.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CommentListRequestDTO {
    private Long postId;
    /** 游标："{timeMs}:{commentId}"；为空表示从最新开始 */
    private String cursor;
    /** 单页数量 */
    private Integer limit;
    /** 预加载每条一级评论的前 N 条回复 */
    private Integer preloadReplyLimit;
}

