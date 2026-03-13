package cn.nexus.types.event;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 内容摘要生成事件：用于触发异步生成并写回 content_post.summary。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class PostSummaryGenerateEvent extends BaseEvent {

    /** 内容 ID。 */
    private Long postId;

    /** 操作者用户 ID（通常为作者本人，可为空）。 */
    private Long operatorId;

    /** 事件时间戳（毫秒）。 */
    private Long tsMs;
}

