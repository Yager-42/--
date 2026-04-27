# Task 3.4 Cache Compatibility Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Preserve relation adjacency cache and follower projection table behavior while changing counter writes to delta outbox.

**Architecture:** Counter projection changes must not alter relation-query behavior. Cache add/remove and follower table maintenance still follow current relation truth; only counter mutation is replaced by durable delta enqueue.

**Tech Stack:** Java, Mockito, JUnit 5, Maven.

---

## File Structure

- Modify: `nexus-domain/src/test/java/cn/nexus/domain/social/service/RelationCounterProjectionProcessorTest.java`
- Modify: `nexus-domain/src/main/java/cn/nexus/domain/social/service/RelationCounterProjectionProcessor.java`

## Exact Behavior

- Active follow with active business truth calls `addFollowWithTtl`, even if follower row already exists.
- Unfollow with inactive business truth calls `removeFollowWithTtl`, even if follower row is already missing.
- Block removes forward and reverse cache entries only when their follower rows were present and removed.
- Cache behavior must not depend on delta outbox success outside the transaction.

### Task 1: Add Cache Compatibility Tests

- [ ] **Step 1: Active duplicate cache refresh test**

```java
@Test
void process_duplicateActiveFollow_shouldRefreshAdjacencyCacheWithoutDelta() {
    RelationCounterProjectEvent event = followEvent(3001L, 11L, 22L, "ACTIVE");
    when(relationRepository.findRelation(11L, 22L, 1))
            .thenReturn(RelationEntity.builder().status(1).build());
    when(relationRepository.saveFollowerIfAbsent(eq(3001L), eq(22L), eq(11L), any()))
            .thenReturn(false);

    processor.process(event);

    verify(relationAdjacencyCachePort).addFollowWithTtl(eq(11L), eq(22L), anyLong(), eq(7200L));
    verifyNoInteractions(userCounterDeltaOutboxRepository);
}
```

- [ ] **Step 2: Missing unfollow cache remove test**

```java
@Test
void process_duplicateUnfollow_shouldRemoveAdjacencyCacheWithoutDelta() {
    RelationCounterProjectEvent event = followEvent(3002L, 11L, 22L, "UNFOLLOW");
    when(relationRepository.findRelation(11L, 22L, 1)).thenReturn(null);
    when(relationRepository.deleteFollowerIfPresent(22L, 11L)).thenReturn(false);

    processor.process(event);

    verify(relationAdjacencyCachePort).removeFollowWithTtl(11L, 22L, 7200L);
    verifyNoInteractions(userCounterDeltaOutboxRepository);
}
```

- [ ] **Step 3: Block direction tests**

When `deleteFollowerIfPresent(target, source)` is true, remove source->target cache and enqueue source/target decrement. When false, do neither for that direction. Repeat for reverse direction.

### Task 2: Verify

- [ ] **Step 1: Run processor tests**

```powershell
& 'C:\Users\Administrator\Desktop\文档\.codex\tools\apache-maven-3.9.6\bin\mvn.cmd' -pl nexus-domain -Dtest=RelationCounterProjectionProcessorTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 2: Commit**

```powershell
git add nexus-domain/src/main/java/cn/nexus/domain/social/service/RelationCounterProjectionProcessor.java nexus-domain/src/test/java/cn/nexus/domain/social/service/RelationCounterProjectionProcessorTest.java
git commit -m "test: preserve relation cache behavior during counter projection"
```

## Ambiguity Review

No architecture choice remains. Cache compatibility is separate from counter delta semantics and must remain behaviorally stable.
