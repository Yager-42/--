package cn.nexus.domain.counter.model.event;

import cn.nexus.domain.counter.model.valobj.ObjectCounterType;
import cn.nexus.domain.social.model.valobj.ReactionTargetTypeEnumVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Local counter side-effect event published after successful counter state transition.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CounterEvent {

    private String requestId;
    private ReactionTargetTypeEnumVO targetType;
    private Long targetId;
    private ObjectCounterType counterType;
    private Long actorUserId;
    private long delta;
    private long tsMs;
}

