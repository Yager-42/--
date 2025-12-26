package cn.nexus.api.social.interaction.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 创建投票结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PollCreateResponseDTO {
    private Long pollId;
}
