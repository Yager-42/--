package cn.nexus.api.social.interaction.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 置顶评论请求。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PinCommentRequestDTO {
    private Long commentId;
    private Long postId;
}
