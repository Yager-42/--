# Task 4.5 Force Rebuild Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Keep explicit force rebuild behavior available for tests and operator repair while removing it from normal processor paths.

**Architecture:** `forceRebuildClass2Counters` is the explicit immediate repair API. Legacy `rebuildAllCounters` remains as a compatibility alias, but because this is a destructive change it delegates to Class 2 repair and no longer recomputes `like_received` by scanning all posts.

**Tech Stack:** Java, Redis snapshot helpers, Mockito, Maven.

---

## File Structure

- Modify: `nexus-domain/src/main/java/cn/nexus/domain/counter/adapter/service/IUserCounterService.java`
- Modify: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/counter/service/UserCounterService.java`
- Modify: `nexus-infrastructure/src/test/java/cn/nexus/infrastructure/adapter/counter/service/UserCounterServiceTest.java`

## Exact Behavior

- `forceRebuildClass2Counters(userId)` immediately repairs Redis snapshot.
- It must use the existing user rebuild lock from `CountRedisKeys.userRebuildLock(userId)` and `CountRedisOperations.tryAcquireRebuildLock(...)` to avoid concurrent writers.
- It bypasses durable request coalescing.
- `rebuildAllCounters(userId)` delegates to `forceRebuildClass2Counters(userId)`.
- Normal processor code must not call either force method.

### Task 1: Tests

- [ ] **Step 1: Add force rebuild direct repair test**

Call `forceRebuildClass2Counters(31L)` and verify relation/post count repositories are read and Redis snapshot is written.

- [ ] **Step 2: Add legacy alias test**

Call `rebuildAllCounters(31L)` and assert same final Redis snapshot as force method.

- [ ] **Step 3: Add no post enumeration assertion**

Verify `contentRepository.listPublishedPostIdsByUser(any())` is never called.

### Task 2: Implementation

- [ ] **Step 1: Implement force method**

Reuse Class 2 repair method from task 2.4. Acquire the existing Redis rebuild lock before writing the repaired snapshot and release it in `finally`.

- [ ] **Step 2: Delegate legacy method**

```java
@Override
public void rebuildAllCounters(Long userId) {
    forceRebuildClass2Counters(userId);
}
```

### Task 3: Verify

- [ ] **Step 1: Run tests**

```powershell
& 'C:\Users\Administrator\Desktop\文档\.codex\tools\apache-maven-3.9.6\bin\mvn.cmd' -pl nexus-infrastructure -Dtest=UserCounterServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 2: Search normal processor**

```powershell
rg -n "forceRebuildClass2Counters|rebuildAllCounters" nexus-domain/src/main/java/cn/nexus/domain/social/service/RelationCounterProjectionProcessor.java
```

Expected: no output.

- [ ] **Step 3: Commit**

```powershell
git add nexus-domain/src/main/java/cn/nexus/domain/counter/adapter/service/IUserCounterService.java nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/counter/service/UserCounterService.java nexus-infrastructure/src/test/java/cn/nexus/infrastructure/adapter/counter/service/UserCounterServiceTest.java
git commit -m "feat: keep explicit class2 force repair"
```

## Ambiguity Review

No architecture choice remains. Force rebuild is operator/test repair only; normal projection never calls it.
