package cn.nexus.types.event.risk;

import cn.nexus.types.event.BaseEvent;
import lombok.Data;

import java.util.List;

/**
 * LLM 扫描请求事件：由在线决策链路投递，异步 worker 消费并回写结果。
 */
@Data
public class LlmScanRequestedEvent extends BaseEvent {
    /** 任务ID（业务语义），用于追踪与去重 */
    private String taskId;
    /** 关联决策ID */
    private Long decisionId;
    /** 原始 eventId（幂等键） */
    private String eventId;

    private Long userId;
    private String actionType;
    private String scenario;

    /** 文本内容（可空） */
    private String contentText;
    /** 图片 URL（可空） */
    private List<String> mediaUrls;

    private String targetId;
    /** 扩展字段 JSON（可空） */
    private String extJson;

    /** TEXT/IMAGE */
    private String contentType;
    /** 去重 hash（textHash/pHash 等） */
    private String contentHash;
}

