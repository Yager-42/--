# Class 2 Regression Tests — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace existing `RelationCounterProjectionProcessorTest` with TDD tests that lock in INCRBY-based projection behavior for follow/unfollow/post events.

**Architecture:** Pure Mockito unit tests. 4 mock dependencies (IRelationRepository, IRelationAdjacencyCachePort, IUserCounterService, plus new IPostCounterProjectionRepository). Tests verify incremental counter calls and absence of `rebuildAllCounters` on normal events.

**Tech Stack:** JUnit Jupiter 5, Mockito, Java 21+

---

## File Structure

| File | Action | Purpose |
|------|--------|---------|
| `nexus-domain/src/main/java/cn/nexus/domain/social/adapter/repository/IPostCounterProjectionRepository.java` | CREATE | New domain contract — post state edge detection |
| `nexus-domain/src/test/java/cn/nexus/domain/social/service/RelationCounterProjectionProcessorTest.java` | MODIFY | Rewrite tests for new INCRBY behavior |

---

### Task R1: Create post counter projection domain contract

**Files:**
- CREATE: `nexus-domain/src/main/java/cn/nexus/domain/social/adapter/repository/IPostCounterProjectionRepository.java`

- [ ] **Step 1: Write the interface**

```java
package cn.nexus.domain.social.adapter.repository;

/**
 * Post counter projection state — published-state edge detection for post counter increments.
 */
public interface IPostCounterProjectionRepository {

    enum EdgeResult {
        /** State changed (false→true or true→false) and eventId > lastEventId. Caller applies INCRBY. */
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
```

- [ ] **Step 2: Verify compilation**

```bash
cd /Users/rr/Desktop/revive/--/project/nexus && mvn compile -pl nexus-domain -am -q -DskipTests
```
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add nexus-domain/src/main/java/cn/nexus/domain/social/adapter/repository/IPostCounterProjectionRepository.java
git commit -m "feat: add post counter projection repository contract for class2 edge detection"
```

---

### Task R2: Rewrite test class — follow projection INCRBY tests (1.1 + 1.2)

**Files:**
- MODIFY: `nexus-domain/src/test/java/cn/nexus/domain/social/service/RelationCounterProjectionProcessorTest.java`

- [ ] **Step 1: Replace entire test file**

```java
package cn.nexus.domain.social.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.domain.counter.adapter.service.IUserCounterService;
import cn.nexus.domain.social.adapter.port.IRelationAdjacencyCachePort;
import cn.nexus.domain.social.adapter.repository.IPostCounterProjectionRepository;
import cn.nexus.domain.social.adapter.repository.IRelationRepository;
import cn.nexus.domain.social.model.entity.RelationEntity;
import cn.nexus.types.event.relation.RelationCounterProjectEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class RelationCounterProjectionProcessorTest {

    private IRelationRepository relationRepository;
    private IRelationAdjacencyCachePort relationAdjacencyCachePort;
    private IUserCounterService userCounterService;
    private IPostCounterProjectionRepository postCounterProjectionRepository;
    private RelationCounterProjectionProcessor processor;

    @BeforeEach
    void setUp() {
        relationRepository = Mockito.mock(IRelationRepository.class);
        relationAdjacencyCachePort = Mockito.mock(IRelationAdjacencyCachePort.class);
        userCounterService = Mockito.mock(IUserCounterService.class);
        postCounterProjectionRepository = Mockito.mock(IPostCounterProjectionRepository.class);
        processor = new RelationCounterProjectionProcessor(
                relationRepository,
                relationAdjacencyCachePort,
                userCounterService,
                postCounterProjectionRepository
        );
    }

    // ── 1.1 Follow edge detected → INCRBY ──

    @Test
    void followActive_newEdge_shouldIncrementCounters() {
        RelationCounterProjectEvent event = followEvent(100L, 1L, 2L, "ACTIVE");
        when(relationRepository.findRelation(1L, 2L, 1))
                .thenReturn(RelationEntity.builder().status(1).build());
        when(relationRepository.saveFollowerIfAbsent(eq(100L), eq(2L), eq(1L), any()))
                .thenReturn(true);

        processor.process(event);

        verify(userCounterService).incrementFollowings(1L, 1L);
        verify(userCounterService).incrementFollowers(2L, 1L);
        verify(relationAdjacencyCachePort).addFollowWithTtl(eq(1L), eq(2L), any(Long.class), eq(7200L));
        verify(userCounterService, never()).rebuildAllCounters(any());
    }

    @Test
    void unfollowActive_removedEdge_shouldDecrementCounters() {
        RelationCounterProjectEvent event = followEvent(101L, 1L, 2L, "UNFOLLOW");
        when(relationRepository.findRelation(1L, 2L, 1)).thenReturn(null);
        when(relationRepository.deleteFollowerIfPresent(2L, 1L)).thenReturn(true);

        processor.process(event);

        verify(userCounterService).incrementFollowings(1L, -1L);
        verify(userCounterService).incrementFollowers(2L, -1L);
        verify(relationAdjacencyCachePort).removeFollowWithTtl(1L, 2L, 7200L);
        verify(userCounterService, never()).rebuildAllCounters(any());
    }

    // ── 1.2 Duplicate edge → no counter calls ──

    @Test
    void followActive_duplicate_shouldNotIncrementCounters() {
        RelationCounterProjectEvent event = followEvent(102L, 1L, 2L, "ACTIVE");
        when(relationRepository.findRelation(1L, 2L, 1))
                .thenReturn(RelationEntity.builder().status(1).build());
        when(relationRepository.saveFollowerIfAbsent(eq(102L), eq(2L), eq(1L), any()))
                .thenReturn(false);

        processor.process(event);

        verify(relationAdjacencyCachePort).addFollowWithTtl(eq(1L), eq(2L), any(Long.class), eq(7200L));
        verify(userCounterService, never()).incrementFollowings(any(), any(Long.class));
        verify(userCounterService, never()).incrementFollowers(any(), any(Long.class));
        verify(userCounterService, never()).rebuildAllCounters(any());
    }

    @Test
    void unfollow_duplicate_shouldNotDecrementCounters() {
        RelationCounterProjectEvent event = followEvent(103L, 1L, 2L, "UNFOLLOW");
        when(relationRepository.findRelation(1L, 2L, 1)).thenReturn(null);
        when(relationRepository.deleteFollowerIfPresent(2L, 1L)).thenReturn(false);

        processor.process(event);

        verify(relationAdjacencyCachePort).removeFollowWithTtl(1L, 2L, 7200L);
        verify(userCounterService, never()).incrementFollowings(any(), any(Long.class));
        verify(userCounterService, never()).incrementFollowers(any(), any(Long.class));
        verify(userCounterService, never()).rebuildAllCounters(any());
    }

    // ── 1.3 Post state edge → INCRBY ──

    @Test
    void post_unpublishedToPublished_shouldIncrementPostCounter() {
        RelationCounterProjectEvent event = postEvent(200L, 1L, 100L, "PUBLISHED");
        when(postCounterProjectionRepository.compareAndWrite(100L, 1L, true, 200L))
                .thenReturn(IPostCounterProjectionRepository.EdgeResult.EDGE_TRANSITION);

        processor.process(event);

        verify(userCounterService).incrementPosts(1L, 1L);
        verify(userCounterService, never()).rebuildAllCounters(any());
    }

    @Test
    void post_publishedToUnpublished_shouldDecrementPostCounter() {
        RelationCounterProjectEvent event = postEvent(201L, 1L, 100L, "UNPUBLISHED");
        when(postCounterProjectionRepository.compareAndWrite(100L, 1L, false, 201L))
                .thenReturn(IPostCounterProjectionRepository.EdgeResult.EDGE_TRANSITION);

        processor.process(event);

        verify(userCounterService).incrementPosts(1L, -1L);
        verify(userCounterService, never()).rebuildAllCounters(any());
    }

    @Test
    void post_publishedToDeleted_shouldDecrementPostCounter() {
        RelationCounterProjectEvent event = postEvent(204L, 1L, 100L, "DELETED");
        when(postCounterProjectionRepository.compareAndWrite(100L, 1L, false, 204L))
                .thenReturn(IPostCounterProjectionRepository.EdgeResult.EDGE_TRANSITION);

        processor.process(event);

        verify(userCounterService).incrementPosts(1L, -1L);
        verify(userCounterService, never()).rebuildAllCounters(any());
    }

    @Test
    void post_sameState_shouldNotModifyPostCounter() {
        RelationCounterProjectEvent event = postEvent(202L, 1L, 100L, "PUBLISHED");
        when(postCounterProjectionRepository.compareAndWrite(100L, 1L, true, 202L))
                .thenReturn(IPostCounterProjectionRepository.EdgeResult.SAME_STATE);

        processor.process(event);

        verify(userCounterService, never()).incrementPosts(any(), any(Long.class));
        verify(userCounterService, never()).rebuildAllCounters(any());
    }

    @Test
    void post_staleEvent_shouldRejectAndNotModifyCounter() {
        RelationCounterProjectEvent event = postEvent(199L, 1L, 100L, "PUBLISHED");
        when(postCounterProjectionRepository.compareAndWrite(100L, 1L, true, 199L))
                .thenReturn(IPostCounterProjectionRepository.EdgeResult.STALE_EVENT);

        processor.process(event);

        verify(userCounterService, never()).incrementPosts(any(), any(Long.class));
        verify(userCounterService, never()).rebuildAllCounters(any());
    }

    @Test
    void post_unpublishedToPublished_authorIdNull_shouldNotIncrement() {
        RelationCounterProjectEvent event = postEvent(203L, null, 100L, "PUBLISHED");
        when(postCounterProjectionRepository.compareAndWrite(100L, null, true, 203L))
                .thenReturn(IPostCounterProjectionRepository.EdgeResult.EDGE_TRANSITION);

        processor.process(event);

        verify(userCounterService, never()).incrementPosts(any(), any(Long.class));
    }

    // ── 1.4 No rebuildAllCounters — proven by never() in all tests above ──

    // ── Block projection (kept from existing) ──

    @Test
    void process_block_shouldOnlyRemoveAdjacencyWhenFollowerProjectionTransitions() {
        RelationCounterProjectEvent event = blockEvent(300L, 11L, 22L);
        when(relationRepository.deleteFollowerIfPresent(22L, 11L)).thenReturn(false);
        when(relationRepository.deleteFollowerIfPresent(11L, 22L)).thenReturn(true);

        processor.process(event);

        verify(relationAdjacencyCachePort, never()).removeFollowWithTtl(11L, 22L, 7200L);
        verify(relationAdjacencyCachePort).removeFollowWithTtl(22L, 11L, 7200L);
        verify(userCounterService, never()).rebuildAllCounters(any());
    }

    // ── Helpers ──

    private RelationCounterProjectEvent followEvent(Long relationEventId, Long sourceId, Long targetId, String status) {
        RelationCounterProjectEvent event = new RelationCounterProjectEvent();
        event.setEventId("relation-counter:" + relationEventId);
        event.setRelationEventId(relationEventId);
        event.setEventType("FOLLOW");
        event.setSourceId(sourceId);
        event.setTargetId(targetId);
        event.setStatus(status);
        return event;
    }

    private RelationCounterProjectEvent blockEvent(Long relationEventId, Long sourceId, Long targetId) {
        RelationCounterProjectEvent event = new RelationCounterProjectEvent();
        event.setEventId("relation-counter:" + relationEventId);
        event.setRelationEventId(relationEventId);
        event.setEventType("BLOCK");
        event.setSourceId(sourceId);
        event.setTargetId(targetId);
        event.setStatus("BLOCKED");
        return event;
    }

    private RelationCounterProjectEvent postEvent(Long relationEventId, Long authorId, Long postId, String status) {
        RelationCounterProjectEvent event = new RelationCounterProjectEvent();
        event.setEventId("relation-counter:" + relationEventId);
        event.setRelationEventId(relationEventId);
        event.setEventType("POST");
        event.setSourceId(authorId);
        event.setTargetId(postId);
        event.setStatus(status);
        return event;
    }
}
```

- [ ] **Step 2: Verify compilation and run tests (expected: compilation error)**

```bash
cd /Users/rr/Desktop/revive/--/project/nexus && mvn test -pl nexus-domain -am -Dtest=RelationCounterProjectionProcessorTest -DfailIfNoTests=false
```
Expected: **COMPILE ERROR** — `RelationCounterProjectionProcessor` constructor doesn't yet accept `IPostCounterProjectionRepository`.

This is correct TDD state. The test file references the new constructor parameter that will be added in Group 3 (Processor Rewrite). Compilation will be fixed when that group is implemented.

- [ ] **Step 3: Commit**

```bash
git add nexus-domain/src/test/java/cn/nexus/domain/social/service/RelationCounterProjectionProcessorTest.java
git commit -m "test: rewrite class2 projection tests for INCRBY behavior (TDD red)"
```
