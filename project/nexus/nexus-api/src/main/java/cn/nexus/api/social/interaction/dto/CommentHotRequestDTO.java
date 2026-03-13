package cn.nexus.api.social.interaction.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CommentHotRequestDTO {
    private Long postId;
    private Integer limit;
    /** 可选：热榜也预加载回复预览（默认 3） */
    private Integer preloadReplyLimit;
}

