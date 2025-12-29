package cn.nexus.domain.social.model.valobj;

import lombok.Builder;
import lombok.Value;

import java.util.Date;

/**
 * 关系事件收件箱领域视图。
 */
@Value
@Builder
public class RelationEventInboxVO {
    String eventType;
    String fingerprint;
    String payload;
    String status;
    Date createTime;
    Date updateTime;
}
