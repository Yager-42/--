package cn.nexus.domain.social.adapter.repository;

/**
 * Post counter projection state - published-state edge detection for post counter increments.
 */
public interface IPostCounterProjectionRepository {

    enum EdgeResult {
        /** State changed (false -> true or true -> false) and eventId > lastEventId. Caller applies INCRBY. */
        EDGE_TRANSITION,
        /** Target state equals current projected state. Caller is no-op. */
        SAME_STATE,
        /** eventId <= lastEventId. Caller discards the stale event. */
        STALE_EVENT
    }

    /**
     * Compare incoming event state with persisted projection, write if newer.
     *
     * @param postId          post identifier
     * @param authorId        post author (business invariant, enforced on first write)
     * @param targetPublished true if the event says the post is published
     * @param relationEventId monotonic event id for stale rejection
     * @return edge classification the caller uses to decide INCRBY
     */
    EdgeResult compareAndWrite(Long postId, Long authorId, boolean targetPublished, Long relationEventId);
}
