# Task 2.1 Post Projection Contract Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add domain contracts for MySQL-backed post counter projection state keyed by `postId`.

**Architecture:** Domain code depends on `IPostCounterProjectionRepository`, not MyBatis classes. The repository returns a result that includes previous state, current state, delta, stale flag, and author id. Stale events are determined by infrastructure using `relationEventId <= last_event_id`.

**Tech Stack:** Java, Lombok, JUnit 5, AssertJ, Maven.

---

## File Structure

- Create: `nexus-domain/src/main/java/cn/nexus/domain/social/model/valobj/PostCounterProjectionResultVO.java`
- Create: `nexus-domain/src/main/java/cn/nexus/domain/social/adapter/repository/IPostCounterProjectionRepository.java`
- Create: `nexus-domain/src/test/java/cn/nexus/domain/social/model/valobj/PostCounterProjectionResultVOTest.java`
- Reference: `openspec/changes/reduce-class2-counter-rebuilds/specs/count-user-state-and-snapshot/spec.md`

## Exact Interface Contract

```java
public interface IPostCounterProjectionRepository {
    PostCounterProjectionResultVO projectPublishedState(
            Long postId,
            Long authorId,
            boolean targetPublished,
            Long relationEventId
    );
}
```

```java
@Getter
@Builder
public class PostCounterProjectionResultVO {
    private final Long postId;
    private final Long authorId;
    private final boolean previousPublished;
    private final boolean currentPublished;
    private final long delta;
    private final boolean stale;
}
```

### Task 1: Write Failing Contract Test

- [ ] **Step 1: Add builder/getter contract test**

```java
@Test
void builder_shouldExposeProjectionEdgeFields() {
    PostCounterProjectionResultVO result = PostCounterProjectionResultVO.builder()
            .postId(9001L)
            .authorId(77L)
            .previousPublished(false)
            .currentPublished(true)
            .delta(1L)
            .stale(false)
            .build();

    assertThat(result.getPostId()).isEqualTo(9001L);
    assertThat(result.getAuthorId()).isEqualTo(77L);
    assertThat(result.isPreviousPublished()).isFalse();
    assertThat(result.isCurrentPublished()).isTrue();
    assertThat(result.getDelta()).isEqualTo(1L);
    assertThat(result.isStale()).isFalse();
}
```

- [ ] **Step 2: Run test and confirm failure**

```powershell
& 'C:\Users\Administrator\Desktop\文档\.codex\tools\apache-maven-3.9.6\bin\mvn.cmd' -pl nexus-domain -Dtest=PostCounterProjectionResultVOTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected before implementation: compilation fails because `PostCounterProjectionResultVO` does not exist.

### Task 2: Create Domain Value Object

- [ ] **Step 1: Add `PostCounterProjectionResultVO`**

Use immutable fields and Lombok `@Getter` plus `@Builder`. Do not add setters.

- [ ] **Step 2: Include stale flag**

`stale=true` means the repository rejected the event because `relationEventId <= last_event_id`; callers must not enqueue counter deltas.

### Task 3: Create Repository Interface

- [ ] **Step 1: Add `IPostCounterProjectionRepository`**

Place it under `cn.nexus.domain.social.adapter.repository` beside other social repository contracts.

- [ ] **Step 2: Document business invariant in Javadoc**

Add concise Javadoc:

```java
/**
 * Projects post published state for counters. A post's author is a business invariant;
 * implementations must not perform author migration.
 */
```

- [ ] **Step 3: Run focused test**

```powershell
& 'C:\Users\Administrator\Desktop\文档\.codex\tools\apache-maven-3.9.6\bin\mvn.cmd' -pl nexus-domain -Dtest=PostCounterProjectionResultVOTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 4: Commit**

```powershell
git add nexus-domain/src/main/java/cn/nexus/domain/social/model/valobj/PostCounterProjectionResultVO.java nexus-domain/src/main/java/cn/nexus/domain/social/adapter/repository/IPostCounterProjectionRepository.java nexus-domain/src/test/java/cn/nexus/domain/social/model/valobj/PostCounterProjectionResultVOTest.java
git commit -m "feat: add post counter projection contract"
```

## Ambiguity Review

No architecture choice remains. Contract exposes stale/no-op explicitly and does not support author migration.
