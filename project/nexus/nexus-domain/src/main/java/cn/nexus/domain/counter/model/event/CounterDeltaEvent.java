package cn.nexus.domain.counter.model.event;

import cn.nexus.domain.counter.model.valobj.ObjectCounterType;
import cn.nexus.domain.social.model.valobj.ReactionTargetTypeEnumVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Kafka payload for effective object-counter state changes.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CounterDeltaEvent {

    private ReactionTargetTypeEnumVO entityType;
    private Long entityId;
    private ObjectCounterType metric;
    private Integer idx;
    private Long userId;
    private Long delta;
}
