package cn.nexus.types.event.interaction;

import cn.nexus.types.event.BaseEvent;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Like/Unlike event for post.
 *
 * <p>type: 1=like, 0=unlike.</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class LikeUnlikePostEvent extends BaseEvent {
    private Long userId;
    private Long postId;
    private Long postCreatorId;
    private Integer type;
    private Long createTime;
}
