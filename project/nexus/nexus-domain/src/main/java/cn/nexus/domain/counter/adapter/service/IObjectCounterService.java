package cn.nexus.domain.counter.adapter.service;

import cn.nexus.domain.counter.model.valobj.ObjectCounterType;
import cn.nexus.domain.social.model.valobj.ReactionTargetTypeEnumVO;
import java.util.List;
import java.util.Map;

/**
 * Object counter service contract aligned with zhiguang-style semantics.
 */
public interface IObjectCounterService {

    boolean like(ReactionTargetTypeEnumVO targetType, Long targetId, Long userId);

    boolean unlike(ReactionTargetTypeEnumVO targetType, Long targetId, Long userId);

    boolean isLiked(ReactionTargetTypeEnumVO targetType, Long targetId, Long userId);

    Map<String, Long> getCounts(ReactionTargetTypeEnumVO targetType, Long targetId, List<ObjectCounterType> metrics);

    Map<Long, Map<String, Long>> getCountsBatch(ReactionTargetTypeEnumVO targetType, List<Long> targetIds, List<ObjectCounterType> metrics);
}

