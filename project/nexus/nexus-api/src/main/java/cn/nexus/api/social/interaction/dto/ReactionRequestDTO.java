package cn.nexus.api.social.interaction.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 点赞/态势请求。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReactionRequestDTO {
    /**
     * 请求标识（可选）：用于日志串联与排障，不参与幂等。
     */
    private String requestId;
    private Long targetId;
    private String targetType;
    private String type;
    private String action;
}
