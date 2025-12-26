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
}
