# Task 1.1 Follow Delta Outbox Tests Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add domain regression tests proving effective follow/unfollow edges enqueue durable counter deltas and never call `rebuildAllCounters`.

**Architecture:** `RelationCounterProjectionProcessor` remains the edge detector, but normal Class 2 writes go to `IUserCounterDeltaOutboxRepository`, not Redis. A new follower row enqueues `source.following +1` and `target.follower +1`; a removed follower row enqueues `source.following -1` and `target.follower -1`. Redis mutation is intentionally absent from processor tests.

**Tech Stack:** Java, JUnit 5, Mockito, Spring domain service tests, Maven.

---

## File Structure

- Modify: `nexus-domain/src/test/java/cn/nexus/domain/social/service/RelationCounterProjectionProcessorTest.java`
- Reference: `nexus-domain/src/main/java/cn/nexus/domain/social/service/RelationCounterProjectionProcessor.java`
- Reference: `nexus-domain/src/main/java/cn/nexus/domain/counter/adapter/repository/IUserCounterDeltaOutboxRepository.java`
- Reference: `openspec/changes/reduce-class2-counter-rebuilds/specs/count-user-state-and-snapshot/spec.md`

## Exact Behavior To Lock

- `FOLLOW ACTIVE` with `saveFollowerIfAbsent(...) == true` enqueues two delta rows.
- `UNFOLLOW` with `deleteFollowerIfPresent(...) == true` enqueues two delta rows.
- The processor never calls `userCounterService.rebuildAllCounters(...)`.
- The processor never calls `incrementFollowings(...)` or `incrementFollowers(...)` directly in normal projection.
- `source_event_id` is `event.getRelationEventId()` and must be passed to the outbox repository.

### Task 1: Add Follow Active Test

- [ ] **Step 1: Add `IUserCounterDeltaOutboxRepository` mock to the test fixture**

```java
private IUserCounterDeltaOutboxRepository userCounterDeltaOutboxRepository;

@BeforeEach
void setUp() {
    userCounterDeltaOutboxRepository = Mockito.mock(IUserCounterDeltaOutboxRepository.class);
    processor = new RelationCounterProjectionProcessor(
            relationRepository,
            relationAdjacencyCachePort,
            userCounterService,
            postCounterProjectionRepository,
            userCounterDeltaOutboxRepository
    );
}
```

- [ ] **Step 2: Add failing test for active follow**

```java
@Test
void process_followActiveCreatedEdge_shouldEnqueueFollowingAndFollowerDeltas() {
    RelationCounterProjectEvent event = followEvent(1001L, 11L, 22L, "ACTIVE");
    when(relationRepository.findRelation(11L, 22L, 1))
            .thenReturn(RelationEntity.builder().status(1).build());
    when(relationRepository.saveFollowerIfAbsent(eq(1001L), eq(22L), eq(11L), any()))
            .thenReturn(true);

    processor.process(event);

    verify(userCounterDeltaOutboxRepository).enqueue(1001L, 11L, UserCounterType.FOLLOWING, 1L);
    verify(userCounterDeltaOutboxRepository).enqueue(1001L, 22L, UserCounterType.FOLLOWER, 1L);
    verify(userCounterService, never()).rebuildAllCounters(any());
    verify(userCounterService, never()).incrementFollowings(any(), anyLong());
    verify(userCounterService, never()).incrementFollowers(any(), anyLong());
}
```

- [ ] **Step 3: Add failing test for unfollow removed edge**

```java
@Test
void process_followUnfollowRemovedEdge_shouldEnqueueFollowingAndFollowerDeltas() {
    RelationCounterProjectEvent event = followEvent(1002L, 11L, 22L, "UNFOLLOW");
    when(relationRepository.findRelation(11L, 22L, 1)).thenReturn(null);
    when(relationRepository.deleteFollowerIfPresent(22L, 11L)).thenReturn(true);

    processor.process(event);

    verify(userCounterDeltaOutboxRepository).enqueue(1002L, 11L, UserCounterType.FOLLOWING, -1L);
    verify(userCounterDeltaOutboxRepository).enqueue(1002L, 22L, UserCounterType.FOLLOWER, -1L);
    verify(userCounterService, never()).rebuildAllCounters(any());
    verify(userCounterService, never()).incrementFollowings(any(), anyLong());
    verify(userCounterService, never()).incrementFollowers(any(), anyLong());
}
```

- [ ] **Step 4: Run tests and confirm failure before implementation**

Run:

```powershell
& 'C:\Users\Administrator\Desktop\文档\.codex\tools\apache-maven-3.9.6\bin\mvn.cmd' -pl nexus-domain -Dtest=RelationCounterProjectionProcessorTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected before implementation: compilation fails until the new outbox interface is added, or assertions fail because the processor still calls `rebuildAllCounters`.

- [ ] **Step 5: Commit only this test change after it passes during implementation**

```powershell
git add nexus-domain/src/test/java/cn/nexus/domain/social/service/RelationCounterProjectionProcessorTest.java
git commit -m "test: cover follow counter delta outbox projection"
```

## Ambiguity Review

No architecture choice is left to the implementer. Processor normal path writes durable delta outbox rows only; Redis increments are worker-owned.
