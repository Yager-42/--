package cn.nexus.domain.social.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 点赞状态查询结果值对象。
 *
 * @author codex
 * @since 2026-01-20
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReactionStateVO {
    private boolean state;
    private Long currentCount;
}

