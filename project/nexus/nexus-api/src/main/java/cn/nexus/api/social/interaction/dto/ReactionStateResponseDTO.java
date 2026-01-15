package cn.nexus.api.social.interaction.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 获取点赞状态响应（单条）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReactionStateResponseDTO {

    /**
     * 当前点赞数（来自 Redis，缺失时回源 DB 并回填）。
     */
    private Long likeCount;

    /**
     * 我是否已点赞（userId 来自 Header 上下文注入）。
     */
    private boolean likedByMe;
}

