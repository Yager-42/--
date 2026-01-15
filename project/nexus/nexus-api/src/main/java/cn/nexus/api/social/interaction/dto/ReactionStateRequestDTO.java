package cn.nexus.api.social.interaction.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 获取点赞状态请求（单条）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReactionStateRequestDTO {

    /**
     * 目标 ID（帖子/评论等）。
     */
    private Long targetId;

    /**
     * 目标类型：POST/COMMENT。
     */
    private String targetType;
}

