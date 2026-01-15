package cn.nexus.api.social.interaction.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 批量点赞状态查询目标。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReactionTargetDTO {

    /**
     * 目标 ID。
     */
    private Long targetId;

    /**
     * 目标类型：POST/COMMENT。
     */
    private String targetType;
}

