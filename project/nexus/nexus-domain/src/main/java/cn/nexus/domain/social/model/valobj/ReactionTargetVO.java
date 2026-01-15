package cn.nexus.domain.social.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 点赞状态查询目标（domain 侧）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReactionTargetVO {

    /**
     * 目标 ID。
     */
    private Long targetId;

    /**
     * 目标类型：POST/COMMENT。
     */
    private String targetType;
}

