package cn.nexus.domain.social.adapter.port;

public interface IRelationEventPort {

    boolean publishCounterProjection(Long eventId,
                                     String eventType,
                                     Long sourceId,
                                     Long targetId,
                                     String status,
                                     String projectionKey,
                                     Long projectionVersion);

    default boolean onFollow(Long eventId,
                             Long sourceId,
                             Long targetId,
                             String status,
                             String projectionKey,
                             Long projectionVersion) {
        return publishCounterProjection(eventId, "FOLLOW", sourceId, targetId, status, projectionKey, projectionVersion);
    }

    default boolean onBlock(Long eventId, Long sourceId, Long targetId) {
        return publishCounterProjection(eventId, "BLOCK", sourceId, targetId, null, null, null);
    }

    default boolean onPost(Long eventId,
                           Long authorId,
                           Long postId,
                           String status,
                           String projectionKey,
                           Long projectionVersion) {
        return publishCounterProjection(eventId, "POST", authorId, postId, status, projectionKey, projectionVersion);
    }
}
