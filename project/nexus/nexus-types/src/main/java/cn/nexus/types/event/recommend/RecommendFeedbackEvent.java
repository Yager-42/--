package cn.nexus.types.event.recommend;

import cn.nexus.types.event.BaseEvent;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 推荐反馈事件（C 通道）：用于表达撤销/反向语义（如 unlike/unstar），避免污染通知语义。
 *
 * <p>字段刻意最小化：足够喂给推荐系统即可。</p>
 *
 * @author codex
 * @since 2026-01-26
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class RecommendFeedbackEvent extends BaseEvent {

    /** 触发者用户 ID。 */
    private Long fromUserId;

    /** 内容 ID。 */
    private Long postId;

    /** 行为类型（如 unlike / unstar / share 等）。 */
    private String feedbackType;

    /** 事件时间戳（毫秒）。 */
    private Long tsMs;
}

