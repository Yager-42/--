package cn.nexus.api.social.interaction.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CommentReplyListRequestDTO {
    private Long rootId;
    /** 游标："{timeMs}:{commentId}"；为空表示从最早开始 */
    private String cursor;
    private Integer limit;
}

