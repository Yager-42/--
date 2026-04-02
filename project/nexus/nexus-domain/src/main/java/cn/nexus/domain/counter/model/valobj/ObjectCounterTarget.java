package cn.nexus.domain.counter.model.valobj;

import cn.nexus.domain.social.model.valobj.ReactionTargetTypeEnumVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 对象计数目标。
 *
 * @author codex
 * @since 2026-04-02
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ObjectCounterTarget {

    private ReactionTargetTypeEnumVO targetType;
    private Long targetId;
    private ObjectCounterType counterType;

    public String hashTag() {
        String type = targetType == null ? "" : targetType.getCode();
        String counter = counterType == null ? "" : counterType.getCode();
        String id = targetId == null ? "" : String.valueOf(targetId);
        return "{" + type + ":" + id + ":" + counter + "}";
    }
}
