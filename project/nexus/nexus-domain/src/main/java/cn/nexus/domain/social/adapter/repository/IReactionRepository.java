package cn.nexus.domain.social.adapter.repository;

import cn.nexus.domain.social.model.valobj.ReactionTargetVO;
import cn.nexus.domain.social.model.valobj.ReactionUserEdgeVO;
import java.util.List;

public interface IReactionRepository {

    void batchUpsert(ReactionTargetVO target, List<Long> userIds);

    void batchDelete(ReactionTargetVO target, List<Long> userIds);

    void upsertCount(ReactionTargetVO target, long count);

    void incrCount(ReactionTargetVO target, long delta);

    boolean applyCountDeltaOnce(ReactionTargetVO target, String eventId, long delta);

    boolean exists(ReactionTargetVO target, Long userId);

    int insertIgnore(ReactionTargetVO target, Long userId);

    int deleteOne(ReactionTargetVO target, Long userId);

    long getCount(ReactionTargetVO target);

    List<ReactionUserEdgeVO> pageUserEdgesByTarget(ReactionTargetVO target, String cursor, int limit);

    java.util.Set<Long> batchExists(ReactionTargetVO targetTemplate, Long userId, List<Long> targetIds);
}
