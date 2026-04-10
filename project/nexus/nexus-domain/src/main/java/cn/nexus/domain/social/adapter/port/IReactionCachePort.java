package cn.nexus.domain.social.adapter.port;

import cn.nexus.domain.social.model.valobj.ReactionApplyResultVO;
import cn.nexus.domain.social.model.valobj.ReactionTargetVO;
import java.util.List;
import java.util.Map;

/**
 * 点赞缓存端口（Redis）。
 *
 * 只暴露在线真相源读写和恢复辅助能力，不暴露旧的 DB 同步快照语义。
 */
public interface IReactionCachePort {

    ReactionApplyResultVO applyAtomic(Long userId,
                                      ReactionTargetVO target,
                                      int desiredState);

    long getCount(ReactionTargetVO target);

    Map<String, Long> batchGetCount(List<ReactionTargetVO> targets);

    long getCountFromRedis(ReactionTargetVO target);

    boolean getState(Long userId, ReactionTargetVO target);

    boolean bitmapShardExists(Long userId, ReactionTargetVO target);

    void setState(Long userId, ReactionTargetVO target, boolean state);

    void setCount(ReactionTargetVO target, long count);

    boolean applyRecoveryEvent(Long userId,
                               ReactionTargetVO target,
                               int desiredState);

    Long getRecoveryCheckpoint(String targetType, String reactionType);

    void setRecoveryCheckpoint(String targetType, String reactionType, Long seq);

    long getWindowMs(ReactionTargetVO target, long defaultMs);
}
