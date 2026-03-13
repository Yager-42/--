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
    /**
     * 请求标识：服务端每次都会回传，用于链路追踪与对账。
     */
    private String requestId;
    private Long currentCount;
    private boolean success;
}
