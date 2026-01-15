package cn.nexus.domain.social.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 点赞状态条目（批量）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReactionStateItemVO {

    private Long targetId;
    private String targetType;

    private Long likeCount;
    private boolean likedByMe;
}

