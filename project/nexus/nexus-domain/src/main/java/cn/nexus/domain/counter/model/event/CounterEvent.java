package cn.nexus.domain.counter.model.event;

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
    private String targetType;
    private Long targetId;
    private String metric;
    private Integer slot;
    private Long actorUserId;
    private long delta;
    private long tsMs;
}
