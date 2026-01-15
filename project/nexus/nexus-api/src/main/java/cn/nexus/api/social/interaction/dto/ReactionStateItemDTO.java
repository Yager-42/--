package cn.nexus.api.social.interaction.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 批量点赞状态条目。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReactionStateItemDTO {

    /**
     * 目标 ID。
     */
    private Long targetId;

    /**
     * 目标类型：POST/COMMENT。
     */
    private String targetType;

    /**
     * 当前点赞数。
     */
    private Long likeCount;

    /**
     * 我是否已点赞。
     */
    private boolean likedByMe;
}

