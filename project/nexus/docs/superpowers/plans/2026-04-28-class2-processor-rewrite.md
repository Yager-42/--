# Class 2 Processor Rewrite — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace all `rebuildAllCounters` calls in `RelationCounterProjectionProcessor` with O(1) Redis SDS atomic increments based on edge detection.

**Architecture:** Single-file rewrite of the core projection processor. Constructor gains `IPostCounterProjectionRepository`. Follow projection uses existing edge detectors. Post projection uses new `compareAndWrite`. All `rebuildAllCounters` and `rebuildRelationCounters` calls removed.

**Tech Stack:** Java 21+, Spring @Transactional, Lombok

---

### Task R6: Add constructor parameter and rewrite follow projection

**Files:**
- MODIFY: `nexus-domain/src/main/java/cn/nexus/domain/social/service/RelationCounterProjectionProcessor.java`

- [ ] **Step 1: Read current file**

```bash
cat nexus-domain/src/main/java/cn/nexus/domain/social/service/RelationCounterProjectionProcessor.java
```

- [ ] **Step 2: Replace `applyFollowProjection` method**

Replace the entire `applyFollowProjection` method (lines 47-80) with:

```java
private void applyFollowProjection(RelationCounterProjectEvent event) {
    String status = normalize(event.getStatus());
    Long sourceId = event.getSourceId();
    Long targetId = event.getTargetId();
    if ("ACTIVE".equals(status)) {
        if (!isActiveFollow(sourceId, targetId)) {
            return;
        }
        Long followerRowId = event.getRelationEventId() == null
                ? relationSeed(sourceId, targetId)
                : event.getRelationEventId();
        boolean changed = relationRepository.saveFollowerIfAbsent(
                followerRowId, targetId, sourceId, new java.util.Date());
        relationAdjacencyCachePort.addFollowWithTtl(
                sourceId, targetId, System.currentTimeMillis(), ADJACENCY_CACHE_TTL_SECONDS);
        if (changed) {
            userCounterService.incrementFollowings(sourceId, 1L);
            userCounterService.incrementFollowers(targetId, 1L);
        }
        return;
    }
    if ("UNFOLLOW".equals(status)) {
        if (isActiveFollow(sourceId, targetId)) {
            return;
        }
        boolean changed = relationRepository.deleteFollowerIfPresent(targetId, sourceId);
        relationAdjacencyCachePort.removeFollowWithTtl(
                sourceId, targetId, ADJACENCY_CACHE_TTL_SECONDS);
        if (changed) {
            userCounterService.incrementFollowings(sourceId, -1L);
            userCounterService.incrementFollowers(targetId, -1L);
        }
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add nexus-domain/src/main/java/cn/nexus/domain/social/service/RelationCounterProjectionProcessor.java
git commit -m "refactor: use INCRBY for follow/unfollow projection instead of rebuildAllCounters"
```

---

### Task R7: Replace post projection with edge-detected INCRBY

**Files:**
- MODIFY: `nexus-domain/src/main/java/cn/nexus/domain/social/service/RelationCounterProjectionProcessor.java`

- [ ] **Step 1: Add constructor parameter and field**

Replace the field declarations and constructor:

```java
@Service
@RequiredArgsConstructor
public class RelationCounterProjectionProcessor {

    private static final int RELATION_FOLLOW = 1;
    private static final int STATUS_ACTIVE = 1;
    private static final long ADJACENCY_CACHE_TTL_SECONDS = 2L * 60L * 60L;
    private final IRelationRepository relationRepository;
    private final IRelationAdjacencyCachePort relationAdjacencyCachePort;
    private final IUserCounterService userCounterService;
    private final IPostCounterProjectionRepository postCounterProjectionRepository;
```

And add this import:
```java
import cn.nexus.domain.social.adapter.repository.IPostCounterProjectionRepository;
import cn.nexus.domain.social.adapter.repository.IPostCounterProjectionRepository.EdgeResult;
```

- [ ] **Step 2: Replace `applyPostProjection` method**

Replace the current method (lines 95-108) with:

```java
private void applyPostProjection(RelationCounterProjectEvent event) {
    Long authorId = event.getSourceId();
    Long postId = event.getTargetId();
    // Behavioral change from legacy: null postId returns early instead of triggering
    // a full rebuild. Null postId events are not expected in normal operation.
    if (authorId == null || postId == null) {
        return;
    }
    String status = normalize(event.getStatus());
    boolean targetPublished = "PUBLISHED".equals(status);
    // UNPUBLISHED and DELETED both mean not-published
    Long relationEventId = event.getRelationEventId();
    if (relationEventId == null) {
        relationEventId = 0L;
    }

    EdgeResult result = postCounterProjectionRepository.compareAndWrite(
            postId, authorId, targetPublished, relationEventId);

    if (result == EdgeResult.EDGE_TRANSITION) {
        userCounterService.incrementPosts(authorId, targetPublished ? 1L : -1L);
    }
}
```

- [ ] **Step 3: Delete `rebuildRelationCounters` method**

Remove the entire method (lines 110-114):
```java
private void rebuildRelationCounters(Long sourceId, Long targetId) {
    userCounterService.rebuildAllCounters(sourceId);
    if (targetId != null && !targetId.equals(sourceId)) {
        userCounterService.rebuildAllCounters(targetId);
    }
}
```

- [ ] **Step 4: Commit**

```bash
git add nexus-domain/src/main/java/cn/nexus/domain/social/service/RelationCounterProjectionProcessor.java
git commit -m "refactor: use INCRBY for post projection with edge detection, remove all rebuildAllCounters calls"
```

---

### Task R8: Verify block projection unchanged

**Files:**
- MODIFY: `nexus-domain/src/main/java/cn/nexus/domain/social/service/RelationCounterProjectionProcessor.java`

- [ ] **Step 1: Verify `applyBlockProjection` is untouched**

The block method should remain:
```java
private void applyBlockProjection(RelationCounterProjectEvent event) {
    Long sourceId = event.getSourceId();
    Long targetId = event.getTargetId();
    boolean forwardChanged = relationRepository.deleteFollowerIfPresent(targetId, sourceId);
    if (forwardChanged) {
        relationAdjacencyCachePort.removeFollowWithTtl(sourceId, targetId, ADJACENCY_CACHE_TTL_SECONDS);
    }
    boolean reverseChanged = relationRepository.deleteFollowerIfPresent(sourceId, targetId);
    if (reverseChanged) {
        relationAdjacencyCachePort.removeFollowWithTtl(targetId, sourceId, ADJACENCY_CACHE_TTL_SECONDS);
    }
}
```

No changes needed. Block events don't trigger counter changes.

- [ ] **Step 2: Verify final file compiles**

```bash
cd /Users/rr/Desktop/revive/--/project/nexus && mvn compile -pl nexus-domain -am -q -DskipTests
```
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit (if any changes)**

No changes needed — block method is already correct.
