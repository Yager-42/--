package cn.nexus.domain.counter.model.event;

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

    private String targetType;
    private Long targetId;
    private String metric;
    private Integer slot;
    private Long actorUserId;
    private Long delta;
    private Long tsMs;
}
