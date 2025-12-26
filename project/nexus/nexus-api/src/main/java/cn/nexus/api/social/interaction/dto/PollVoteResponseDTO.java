package cn.nexus.api.social.interaction.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 投票结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PollVoteResponseDTO {
    private String updatedStats;
}
