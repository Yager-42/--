package cn.nexus.api.social.interaction.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 点赞状态查询结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReactionStateResponseDTO {
    private boolean state;
    private Long currentCount;
}

