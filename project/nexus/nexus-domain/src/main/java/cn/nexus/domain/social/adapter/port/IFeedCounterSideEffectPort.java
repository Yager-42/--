package cn.nexus.domain.social.adapter.port;

import cn.nexus.domain.counter.model.valobj.ObjectCounterType;

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
    void applyPostCounterDelta(Long postId, ObjectCounterType metric, long delta);
}
