# Task 1.3 Post Delta Edge Tests Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add domain regression tests proving post projection enqueues `post` deltas only on fresh durable published-state edges.

**Architecture:** `IPostCounterProjectionRepository` owns durable `post_id` state and stale event rejection. The processor asks it for a projection result; only nonzero fresh deltas are enqueued into `IUserCounterDeltaOutboxRepository`. Post authors are a business invariant, so tests do not model author migration.

**Tech Stack:** Java, JUnit 5, Mockito, Maven.

---

## File Structure

- Modify: `nexus-domain/src/test/java/cn/nexus/domain/social/service/RelationCounterProjectionProcessorTest.java`
- Reference: `nexus-domain/src/main/java/cn/nexus/domain/social/adapter/repository/IPostCounterProjectionRepository.java`
- Reference: `nexus-domain/src/main/java/cn/nexus/domain/social/model/valobj/PostCounterProjectionResultVO.java`
- Reference: `nexus-domain/src/main/java/cn/nexus/domain/counter/adapter/repository/IUserCounterDeltaOutboxRepository.java`

## Exact Behavior To Lock

- `PUBLISHED` maps to target published `true`.
- `UNPUBLISHED` and `DELETED` map to target published `false`.
- Repository result `delta=1` enqueues `POST +1`; `delta=-1` enqueues `POST -1`; `delta=0` enqueues nothing.
- Stale result enqueues nothing.
- Processor never directly calls `incrementPosts` or `rebuildAllCounters`.

### Task 1: Add Post Edge Tests

- [ ] **Step 1: Add post published edge test**

```java
@Test
void process_postPublishedFreshEdge_shouldEnqueuePostIncrement() {
    RelationCounterProjectEvent event = postEvent(2001L, 77L, 9001L, "PUBLISHED");
    when(postCounterProjectionRepository.projectPublishedState(9001L, 77L, true, 2001L))
            .thenReturn(PostCounterProjectionResultVO.builder()
                    .postId(9001L)
                    .authorId(77L)
                    .previousPublished(false)
                    .currentPublished(true)
                    .delta(1L)
                    .stale(false)
                    .build());

    processor.process(event);

    verify(userCounterDeltaOutboxRepository).enqueue(2001L, 77L, UserCounterType.POST, 1L);
    verify(userCounterService, never()).incrementPosts(any(), anyLong());
    verify(userCounterService, never()).rebuildAllCounters(any());
}
```

- [ ] **Step 2: Add post unpublished edge test**

```java
@Test
void process_postUnpublishedFreshEdge_shouldEnqueuePostDecrement() {
    RelationCounterProjectEvent event = postEvent(2002L, 77L, 9001L, "UNPUBLISHED");
    when(postCounterProjectionRepository.projectPublishedState(9001L, 77L, false, 2002L))
            .thenReturn(PostCounterProjectionResultVO.builder()
                    .postId(9001L)
                    .authorId(77L)
                    .previousPublished(true)
                    .currentPublished(false)
                    .delta(-1L)
                    .stale(false)
                    .build());

    processor.process(event);

    verify(userCounterDeltaOutboxRepository).enqueue(2002L, 77L, UserCounterType.POST, -1L);
}
```

- [ ] **Step 3: Add duplicate and stale tests**

```java
@Test
void process_postPublishedSameState_shouldNotEnqueueDelta() {
    RelationCounterProjectEvent event = postEvent(2003L, 77L, 9001L, "PUBLISHED");
    when(postCounterProjectionRepository.projectPublishedState(9001L, 77L, true, 2003L))
            .thenReturn(PostCounterProjectionResultVO.builder()
                    .postId(9001L).authorId(77L)
                    .previousPublished(true).currentPublished(true)
                    .delta(0L).stale(false).build());

    processor.process(event);

    verifyNoInteractions(userCounterDeltaOutboxRepository);
}
```

```java
@Test
void process_postStaleRelationEventId_shouldNotEnqueueDelta() {
    RelationCounterProjectEvent event = postEvent(1999L, 77L, 9001L, "DELETED");
    when(postCounterProjectionRepository.projectPublishedState(9001L, 77L, false, 1999L))
            .thenReturn(PostCounterProjectionResultVO.builder()
                    .postId(9001L).authorId(77L)
                    .previousPublished(true).currentPublished(true)
                    .delta(0L).stale(true).build());

    processor.process(event);

    verifyNoInteractions(userCounterDeltaOutboxRepository);
}
```

- [ ] **Step 4: Run focused tests**

Run:

```powershell
& 'C:\Users\Administrator\Desktop\文档\.codex\tools\apache-maven-3.9.6\bin\mvn.cmd' -pl nexus-domain -Dtest=RelationCounterProjectionProcessorTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected before implementation: compilation fails until new repository/result APIs exist, or assertions fail because current processor rebuilds.

- [ ] **Step 5: Commit after implementation makes tests pass**

```powershell
git add nexus-domain/src/test/java/cn/nexus/domain/social/service/RelationCounterProjectionProcessorTest.java
git commit -m "test: cover post counter delta edge projection"
```

## Ambiguity Review

No architecture choice remains. Stale post event handling is fixed as `relationEventId <= last_event_id` no-op, and post author changes are out of scope by business invariant.
