package cn.nexus.infrastructure.dao.social.po;

import java.util.Date;
import lombok.Data;

@Data
public class InteractionReactionEventLogPO {
    private Long seq;
    private String eventId;
    private String targetType;
    private Long targetId;
    private String reactionType;
    private Long userId;
    private Integer desiredState;
    private Integer delta;
    private Long eventTime;
    private Date createTime;
}
