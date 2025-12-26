package cn.nexus.api.social.interaction.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 投票请求。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PollVoteRequestDTO {
    private Long pollId;
    private List<Long> optionIds;
}
