package cn.nexus.types.event.risk;

import cn.nexus.types.event.BaseEvent;
import lombok.Data;

/**
 * 图片扫描请求事件：异步扫描队列。
 *
 * <p>说明：图片扫描通常也可以复用 {@link LlmScanRequestedEvent} 的多模态能力；保留该事件用于明确语义分流。</p>
 */
@Data
public class ImageScanRequestedEvent extends BaseEvent {
    private String taskId;
    private Long decisionId;
    private String eventId;
    private Long userId;
    private String imageUrl;
}

