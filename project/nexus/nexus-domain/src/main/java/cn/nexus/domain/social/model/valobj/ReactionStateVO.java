package cn.nexus.domain.social.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 点赞状态（单条）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReactionStateVO {

    /**
     * 当前点赞数。
     */
    private Long likeCount;

    /**
     * 我是否已点赞。
     */
    private boolean likedByMe;
}

