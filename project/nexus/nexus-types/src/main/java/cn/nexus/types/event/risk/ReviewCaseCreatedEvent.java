package cn.nexus.types.event.risk;

import cn.nexus.types.event.BaseEvent;
import lombok.Data;

/**
 * 人审工单创建事件：用于异步入队/通知。
 */
@Data
public class ReviewCaseCreatedEvent extends BaseEvent {
    private Long caseId;
    private Long decisionId;
    private String queue;
    private String summary;
}

