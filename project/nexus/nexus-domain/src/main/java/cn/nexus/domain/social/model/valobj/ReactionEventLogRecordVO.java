package cn.nexus.domain.social.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReactionEventLogRecordVO {
    private String eventId;
    private String targetType;
    private Long targetId;
    private String reactionType;
    private Long userId;
    private Integer desiredState;
    private Integer delta;
    private Long eventTime;
}
