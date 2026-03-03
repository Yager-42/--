package cn.nexus.domain.social.adapter.port;

import cn.nexus.domain.social.model.valobj.like.PostLikeApplyResultVO;
import cn.nexus.domain.social.model.valobj.like.PostLikeCacheStateVO;

/**
 * Post-like cache port (Bloom + ZSet).
 */
public interface IPostLikeCachePort {

    PostLikeApplyResultVO tryLike(Long userId, Long postId, long nowMs);

    PostLikeApplyResultVO forceLike(Long userId, Long postId, long nowMs);

    PostLikeApplyResultVO tryUnlike(Long userId, Long postId, long nowMs);

    PostLikeApplyResultVO forceUnlike(Long userId, Long postId, long nowMs);

    PostLikeCacheStateVO cacheState(Long userId, Long postId);

    /**
     * 更新“作者收到的点赞数”计数（最终一致，允许短暂偏差）。
     *
     * <p>注意：这个计数是按 creatorId 聚合的派生值，不参与点赞事实表（interaction_reaction）。</p>
     *
     * @param creatorId 作者用户 ID
     * @param delta     +1=收到点赞，-1=取消点赞
     * @return 当前计数（已做非负保护）
     */
    long applyCreatorLikeDelta(Long creatorId, int delta);
}
