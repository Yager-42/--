# Task 3.1 Follow Projection Rewrite Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rewrite follow projection to enqueue durable counter deltas based on follower edge detector results.

**Architecture:** `RelationCounterProjectionProcessor` runs inside the existing transaction boundary. It updates follower projection/cache as before, but only writes `user_counter_delta_outbox` rows for effective state edges. Redis counter application is deferred to the delta worker.

**Tech Stack:** Java, Spring service, Spring transaction, Mockito tests, Maven.

---

## File Structure

- Modify: `nexus-domain/src/main/java/cn/nexus/domain/social/service/RelationCounterProjectionProcessor.java`
- Modify: `nexus-domain/src/test/java/cn/nexus/domain/social/service/RelationCounterProjectionProcessorTest.java`
- Reference: `nexus-domain/src/main/java/cn/nexus/domain/counter/adapter/repository/IUserCounterDeltaOutboxRepository.java`

## Exact Behavior

- Active follow:
  - If business relation is not active, return.
  - Call `saveFollowerIfAbsent`.
  - Always refresh adjacency cache after active truth.
  - If changed, enqueue `FOLLOWING +1` for source and `FOLLOWER +1` for target.
- Unfollow:
  - If business relation is still active, return.
  - Call `deleteFollowerIfPresent`.
  - Always remove adjacency cache after inactive truth.
  - If changed, enqueue `FOLLOWING -1` for source and `FOLLOWER -1` for target.

### Task 1: Implement Constructor Dependency

- [ ] **Step 1: Add field**

```java
private final IUserCounterDeltaOutboxRepository userCounterDeltaOutboxRepository;
```

- [ ] **Step 2: Import `UserCounterType`**

Use enum values `FOLLOWING` and `FOLLOWER`.

### Task 2: Replace Rebuild With Delta Enqueue

- [ ] **Step 1: Add helper**

```java
private void enqueueRelationDeltas(Long eventId, Long sourceId, Long targetId, long delta) {
    if (eventId == null || sourceId == null || targetId == null) {
        return;
    }
    userCounterDeltaOutboxRepository.enqueue(eventId, sourceId, UserCounterType.FOLLOWING, delta);
    if (!targetId.equals(sourceId)) {
        userCounterDeltaOutboxRepository.enqueue(eventId, targetId, UserCounterType.FOLLOWER, delta);
    }
}
```

- [ ] **Step 2: Use helper only when edge changed**

Call with `+1L` after `saveFollowerIfAbsent == true`; call with `-1L` after `deleteFollowerIfPresent == true`.

- [ ] **Step 3: Remove `rebuildRelationCounters`**

No normal follow path may call `rebuildAllCounters`.

### Task 3: Verify

- [ ] **Step 1: Run domain tests**

```powershell
& 'C:\Users\Administrator\Desktop\文档\.codex\tools\apache-maven-3.9.6\bin\mvn.cmd' -pl nexus-domain -Dtest=RelationCounterProjectionProcessorTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: follow tests pass.

- [ ] **Step 2: Search for forbidden calls**

```powershell
rg -n "rebuildAllCounters|incrementFollowings|incrementFollowers" nexus-domain/src/main/java/cn/nexus/domain/social/service/RelationCounterProjectionProcessor.java
```

Expected: no matches for `rebuildAllCounters`, `incrementFollowings`, or `incrementFollowers`.

- [ ] **Step 3: Commit**

```powershell
git add nexus-domain/src/main/java/cn/nexus/domain/social/service/RelationCounterProjectionProcessor.java nexus-domain/src/test/java/cn/nexus/domain/social/service/RelationCounterProjectionProcessorTest.java
git commit -m "feat: enqueue follow counter deltas from projection edges"
```

## Ambiguity Review

No architecture choice remains. The processor owns durable enqueue, not Redis mutation.
