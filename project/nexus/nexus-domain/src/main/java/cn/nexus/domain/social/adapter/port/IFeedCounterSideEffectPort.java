package cn.nexus.domain.social.adapter.port;

/**
 * Feed local cache side effects for counter changes.
 */
public interface IFeedCounterSideEffectPort {

    /**
     * Apply post-like cache side effects by reverse index and keep page TTL unchanged.
     *
     * @param postId target post id
     * @param delta like delta, non-zero means effective toggle
     */
    void applyPostLikeDelta(Long postId, long delta);
}

