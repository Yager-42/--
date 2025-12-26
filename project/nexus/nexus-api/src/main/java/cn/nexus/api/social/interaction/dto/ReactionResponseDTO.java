package cn.nexus.api.social.interaction.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 点赞/态势结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReactionResponseDTO {
    private Long currentCount;
    private boolean success;
}
