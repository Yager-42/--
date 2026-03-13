package cn.nexus.domain.social.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Redis 原子写入结果值对象。
 *
 * @author codex
 * @since 2026-01-20
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReactionApplyResultVO {
    /**
     * 当前近实时计数（来自 Redis）。
     */
    private Long currentCount;

    /**
     * 本次对集合的真实变更：+1 / -1 / 0。
     */
    private Integer delta;

    /**
     * 是否首次置 pending（用于决定是否投递延迟消息）。
     */
    private boolean firstPending;
}

