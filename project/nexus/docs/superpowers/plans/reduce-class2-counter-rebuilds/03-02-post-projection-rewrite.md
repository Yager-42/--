# Task 3.2 Post Projection Rewrite Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rewrite post projection to compare durable state, reject stale events, and enqueue only real `post` counter deltas.

**Architecture:** Processor normalizes post status, delegates ordering/state transition to `IPostCounterProjectionRepository`, then enqueues `UserCounterType.POST` delta only if the result is fresh and nonzero. The post author is a business invariant and not migrated by this processor.

**Tech Stack:** Java, Spring service, Mockito tests, Maven.

---

## File Structure

- Modify: `nexus-domain/src/main/java/cn/nexus/domain/social/service/RelationCounterProjectionProcessor.java`
- Modify: `nexus-domain/src/test/java/cn/nexus/domain/social/service/RelationCounterProjectionProcessorTest.java`
- Reference: `nexus-domain/src/main/java/cn/nexus/domain/social/adapter/repository/IPostCounterProjectionRepository.java`

## Exact Behavior

- `PUBLISHED` -> target published `true`.
- `UNPUBLISHED` and `DELETED` -> target published `false`.
- Unknown status -> return.
- Missing `sourceId`, `targetId`, or `relationEventId` -> return.
- Repository returns `stale=true` -> no delta enqueue.
- Repository returns `delta=0` -> no delta enqueue.
- Repository returns `delta != 0` -> enqueue one `POST` delta row.

### Task 1: Inject Repository

- [ ] **Step 1: Add field**

```java
private final IPostCounterProjectionRepository postCounterProjectionRepository;
```

- [ ] **Step 2: Update tests and constructor sites**

All `new RelationCounterProjectionProcessor(...)` calls must pass mock `postCounterProjectionRepository`.

### Task 2: Implement Post Projection

- [ ] **Step 1: Add status normalization helper**

```java
private Boolean targetPublished(String status) {
    return switch (normalize(status)) {
        case "PUBLISHED" -> Boolean.TRUE;
        case "UNPUBLISHED", "DELETED" -> Boolean.FALSE;
        default -> null;
    };
}
```

- [ ] **Step 2: Use target post id**

`postId = event.getTargetId()`. `authorId = event.getSourceId()`.

- [ ] **Step 3: Enqueue on fresh nonzero delta**

```java
PostCounterProjectionResultVO result =
        postCounterProjectionRepository.projectPublishedState(postId, authorId, targetPublished, event.getRelationEventId());
if (result != null && !result.isStale() && result.getDelta() != 0L) {
    userCounterDeltaOutboxRepository.enqueue(event.getRelationEventId(), result.getAuthorId(), UserCounterType.POST, result.getDelta());
}
```

### Task 3: Verify

- [ ] **Step 1: Run post projection tests**

```powershell
& 'C:\Users\Administrator\Desktop\文档\.codex\tools\apache-maven-3.9.6\bin\mvn.cmd' -pl nexus-domain -Dtest=RelationCounterProjectionProcessorTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: post edge tests pass.

- [ ] **Step 2: Search forbidden calls**

```powershell
rg -n "rebuildAllCounters|incrementPosts" nexus-domain/src/main/java/cn/nexus/domain/social/service/RelationCounterProjectionProcessor.java
```

Expected: no matches.

- [ ] **Step 3: Commit**

```powershell
git add nexus-domain/src/main/java/cn/nexus/domain/social/service/RelationCounterProjectionProcessor.java nexus-domain/src/test/java/cn/nexus/domain/social/service/RelationCounterProjectionProcessorTest.java
git commit -m "feat: enqueue post counter deltas from durable state edges"
```

## Ambiguity Review

No architecture choice remains. Stale handling and author invariant are already fixed by OpenSpec.
