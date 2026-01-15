package cn.nexus.domain.social.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 互动结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReactionResultVO {
    private Long currentCount;
    private boolean success;

    /**
     * 本次计数变化量：-1/0/+1（幂等请求返回 0）。
     *
     * <p>用于 controller 判定是否需要投递异步链路（例如点赞通知、延迟落库）。</p>
     */
    private Long delta;

    /**
     * 是否需要调度一次延迟 flush（只在窗口首次创建时为 true）。
     */
    private boolean needSchedule;
}
