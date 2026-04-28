# Class 2 Processor Rewrite — Design Spec

**Topic:** Rewrite RelationCounterProjectionProcessor to use O(1) INCRBY instead of O(N) rebuilds
**Change:** reduce-class2-counter-rebuilds
**Group:** 3 of 5
**Date:** 2026-04-28

## Context

The `RelationCounterProjectionProcessor` currently calls `rebuildAllCounters` on every follow/unfollow/post event. This executes 3 MySQL COUNT queries plus enumerates all author posts for `like_received` per event. The replacement uses edge detectors already present (`saveFollowerIfAbsent`/`deleteFollowerIfPresent` for follow, new `post_counter_projection` for posts) and applies atomic Redis SDS increments.

## Scope

Single file rewrite: `RelationCounterProjectionProcessor.java`. Remove `rebuildRelationCounters()` method entirely. Replace per-event rebuild calls with targeted `incrementXxx()` calls based on edge detection results.

## Design

### Constructor Change

```java
// Old: 3 params
processor = new RelationCounterProjectionProcessor(
    relationRepository, relationAdjacencyCachePort, userCounterService);

// New: 4 params
processor = new RelationCounterProjectionProcessor(
    relationRepository, relationAdjacencyCachePort, userCounterService,
    postCounterProjectionRepository);
```

### Follow Projection (3.1 + 3.2)

```java
private void applyFollowProjection(RelationCounterProjectEvent event) {
    // ... isActiveFollow check unchanged ...

    if ("ACTIVE".equals(status)) {
        boolean changed = relationRepository.saveFollowerIfAbsent(followerRowId, targetId, sourceId, new Date());
        relationAdjacencyCachePort.addFollowWithTtl(sourceId, targetId, ..., ADJACENCY_CACHE_TTL_SECONDS);
        if (changed) {
            userCounterService.incrementFollowings(sourceId, 1L);
            userCounterService.incrementFollowers(targetId, 1L);
        }
        return;
    }

    if ("UNFOLLOW".equals(status)) {
        boolean changed = relationRepository.deleteFollowerIfPresent(targetId, sourceId);
        relationAdjacencyCachePort.removeFollowWithTtl(sourceId, targetId, ADJACENCY_CACHE_TTL_SECONDS);
        if (changed) {
            userCounterService.incrementFollowings(sourceId, -1L);
            userCounterService.incrementFollowers(targetId, -1L);
        }
    }
}
```

Key: cache update always happens (duplicate events still refresh TTL). Counter delta only happens on real edges (changed=true).

### Post Projection (3.3)

```java
private void applyPostProjection(RelationCounterProjectEvent event) {
    Long authorId = event.getSourceId();
    Long postId = event.getTargetId();
    if (authorId == null || postId == null) return;

    boolean targetPublished = "PUBLISHED".equals(normalize(event.getStatus()));
    Long relationEventId = event.getRelationEventId();
    if (relationEventId == null) relationEventId = 0L;

    EdgeResult result = postCounterProjectionRepository.compareAndWrite(
        postId, authorId, targetPublished, relationEventId);

    switch (result) {
        case EDGE_TRANSITION:
            userCounterService.incrementPosts(authorId, targetPublished ? 1L : -1L);
            break;
        case SAME_STATE:
        case STALE_EVENT:
        default:
            break;
    }
}
```

### Removed Code (3.4)

- Delete `rebuildRelationCounters(Long sourceId, Long targetId)` method
- Delete all calls to `rebuildAllCounters()` (6 call sites in follow/unfollow/post methods)
- `rebuildAllCounters` remains available in `IUserCounterService` for cold-path repair — just not called from this processor

### Cache Updates (3.5)

Unchanged. Adjacency cache write/delete happens regardless of edge detection result — duplicates refresh TTL.
