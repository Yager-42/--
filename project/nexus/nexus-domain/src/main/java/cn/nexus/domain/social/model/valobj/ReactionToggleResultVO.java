package cn.nexus.domain.social.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 点赞写链路（Redis Lua）返回结果。
 *
 * <p>这个对象只描述“真实发生了什么”，不包含业务成功/失败的语义（由上层决定）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReactionToggleResultVO {

    /**
     * 本次计数变化量：-1/0/+1（幂等请求返回 0）。
     */
    private Long delta;

    /**
     * Redis 更新后的当前点赞数（绝对值）。
     */
    private Long currentCount;

    /**
     * 是否需要投递一次延迟 flush（仅当 winKey miss 且 delta != 0 时为 true）。
     */
    private boolean needSchedule;
}

