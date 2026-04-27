# Task 1.2 Duplicate Follow No-Delta Tests Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add domain regression tests proving duplicate follow/unfollow projections do not enqueue counter delta rows.

**Architecture:** Duplicate events are handled by follower projection edge detectors before counter delta enqueue. `saveFollowerIfAbsent(...) == false` and `deleteFollowerIfPresent(...) == false` are no-op counter edges, while cache maintenance behavior remains compatible with relation queries.

**Tech Stack:** Java, JUnit 5, Mockito, Maven.

---

## File Structure

- Modify: `nexus-domain/src/test/java/cn/nexus/domain/social/service/RelationCounterProjectionProcessorTest.java`
- Reference: `nexus-domain/src/main/java/cn/nexus/domain/social/service/RelationCounterProjectionProcessor.java`
- Reference: `nexus-domain/src/main/java/cn/nexus/domain/counter/adapter/repository/IUserCounterDeltaOutboxRepository.java`

## Exact Behavior To Lock

- Duplicate `FOLLOW ACTIVE` still refreshes adjacency cache but does not enqueue deltas.
- Duplicate `UNFOLLOW` still removes adjacency cache but does not enqueue deltas.
- No direct Redis increment or rebuild is allowed.

### Task 1: Add Duplicate Follow Tests

- [ ] **Step 1: Add failing duplicate active follow test**

```java
@Test
void process_followActiveExistingEdge_shouldNotEnqueueCounterDeltas() {
    RelationCounterProjectEvent event = followEvent(1101L, 11L, 22L, "ACTIVE");
    when(relationRepository.findRelation(11L, 22L, 1))
            .thenReturn(RelationEntity.builder().status(1).build());
    when(relationRepository.saveFollowerIfAbsent(eq(1101L), eq(22L), eq(11L), any()))
            .thenReturn(false);

    processor.process(event);

    verify(relationAdjacencyCachePort).addFollowWithTtl(eq(11L), eq(22L), anyLong(), eq(7200L));
    verifyNoInteractions(userCounterDeltaOutboxRepository);
    verify(userCounterService, never()).rebuildAllCounters(any());
    verify(userCounterService, never()).incrementFollowings(any(), anyLong());
    verify(userCounterService, never()).incrementFollowers(any(), anyLong());
}
```

- [ ] **Step 2: Add failing duplicate unfollow test**

```java
@Test
void process_followUnfollowMissingEdge_shouldNotEnqueueCounterDeltas() {
    RelationCounterProjectEvent event = followEvent(1102L, 11L, 22L, "UNFOLLOW");
    when(relationRepository.findRelation(11L, 22L, 1)).thenReturn(null);
    when(relationRepository.deleteFollowerIfPresent(22L, 11L)).thenReturn(false);

    processor.process(event);

    verify(relationAdjacencyCachePort).removeFollowWithTtl(11L, 22L, 7200L);
    verifyNoInteractions(userCounterDeltaOutboxRepository);
    verify(userCounterService, never()).rebuildAllCounters(any());
    verify(userCounterService, never()).incrementFollowings(any(), anyLong());
    verify(userCounterService, never()).incrementFollowers(any(), anyLong());
}
```

- [ ] **Step 3: Add relation-truth guard tests**

```java
@Test
void process_followActiveButBusinessTruthInactive_shouldNotTouchProjectionOrCounters() {
    RelationCounterProjectEvent event = followEvent(1103L, 11L, 22L, "ACTIVE");
    when(relationRepository.findRelation(11L, 22L, 1)).thenReturn(null);

    processor.process(event);

    verify(relationRepository, never()).saveFollowerIfAbsent(any(), any(), any(), any());
    verifyNoInteractions(userCounterDeltaOutboxRepository);
}
```

- [ ] **Step 4: Run focused tests**

Run:

```powershell
& 'C:\Users\Administrator\Desktop\文档\.codex\tools\apache-maven-3.9.6\bin\mvn.cmd' -pl nexus-domain -Dtest=RelationCounterProjectionProcessorTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected before implementation: assertions fail because old processor calls `rebuildAllCounters` on duplicate edges.

- [ ] **Step 5: Commit after implementation makes tests pass**

```powershell
git add nexus-domain/src/test/java/cn/nexus/domain/social/service/RelationCounterProjectionProcessorTest.java
git commit -m "test: cover duplicate follow counter projection no-ops"
```

## Ambiguity Review

No architecture choice remains. Duplicate edge means no delta row; consumer idempotency is a separate guard and must not be the only duplicate protection.
