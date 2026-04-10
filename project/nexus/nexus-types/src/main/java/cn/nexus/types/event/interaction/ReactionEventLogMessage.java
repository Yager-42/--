package cn.nexus.types.event.interaction;

import cn.nexus.types.event.BaseEvent;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class ReactionEventLogMessage extends BaseEvent {
    private String targetType;
    private Long targetId;
    private String reactionType;
    private Long userId;
    private Integer desiredState;
    private Integer delta;
    private Long eventTime;
}
