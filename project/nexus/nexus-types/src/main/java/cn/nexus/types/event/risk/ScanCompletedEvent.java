package cn.nexus.types.event.risk;

import cn.nexus.types.event.BaseEvent;
import lombok.Data;

import java.util.List;

/**
 * 扫描完成事件：由 risk-worker 在异步扫描后发布，用于旁路消费（审计/报表/告警等）。
 */
@Data
public class ScanCompletedEvent extends BaseEvent {
    private String taskId;
    private Long decisionId;
    /** TEXT/IMAGE */
    private String contentType;
    /** PASS/REVIEW/BLOCK */
    private String result;
    private String reasonCode;
    private Double confidence;
    private List<String> riskTags;
    /** 追溯字段：本次扫描使用的 promptVersion（可空） */
    private Long promptVersion;
    /** 追溯字段：本次扫描使用的模型名（可空） */
    private String model;
}

