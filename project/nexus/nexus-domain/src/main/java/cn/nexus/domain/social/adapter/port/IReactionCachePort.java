package cn.nexus.domain.social.adapter.port;

import cn.nexus.domain.social.model.valobj.ReactionApplyResultVO;
import cn.nexus.domain.social.model.valobj.ReactionTargetVO;
import java.util.Map;

/**
 * 点赞缓存端口（Redis）。
 */
public interface IReactionCachePort {

    ReactionApplyResultVO applyAtomic(Long userId, ReactionTargetVO target, int desiredState, int syncTtlSec);

    boolean snapshotOps(ReactionTargetVO target);

    Map<Long, Integer> readOpsSnapshot(ReactionTargetVO target);

    void clearOpsSnapshot(ReactionTargetVO target);

    long getCount(ReactionTargetVO target);

    long getCountFromRedis(ReactionTargetVO target);

    boolean getState(Long userId, ReactionTargetVO target);

    boolean bitmapShardExists(Long userId, ReactionTargetVO target);

    void setState(Long userId, ReactionTargetVO target, boolean state);

    void setCount(ReactionTargetVO target, long count);

    long getWindowMs(ReactionTargetVO target, long defaultMs);

    void setSyncPending(ReactionTargetVO target, int ttlSec);

    void clearSyncFlag(ReactionTargetVO target);

    void setLastSyncTime(ReactionTargetVO target, long epochMillis);

    boolean existsOps(ReactionTargetVO target);
}
