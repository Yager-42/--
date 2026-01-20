package cn.nexus.domain.social.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 点赞目标值对象：targetType + targetId + reactionType。
 *
 * <p>它是本业务里“同一份数据归属”的唯一键。所有 Redis key 都必须带同一个 hash-tag，
 * 才能在 Redis Cluster 下安全执行 Lua/RENAME。</p>
 *
 * @author codex
 * @since 2026-01-20
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReactionTargetVO {
    private ReactionTargetTypeEnumVO targetType;
    private Long targetId;
    private ReactionTypeEnumVO reactionType;

    /**
     * Redis Cluster hash-tag：把同一 target 的 key 强制落在同一个 slot。
     *
     * @return {@link String} 例如 {POST:90001:LIKE}
     */
    public String hashTag() {
        String t = targetType == null ? "" : targetType.getCode();
        String r = reactionType == null ? "" : reactionType.getCode();
        return "{" + t + ":" + (targetId == null ? "" : targetId) + ":" + r + "}";
    }
}

