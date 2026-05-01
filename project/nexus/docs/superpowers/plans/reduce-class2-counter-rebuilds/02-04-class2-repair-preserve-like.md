# Task 2.4 Class 2 Repair Preserve Like Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Class 2 repair that rebuilds `following`, `follower`, and `post` while preserving readable `like_received`.

**Architecture:** Class 2 repair is the cheap DB-truth repair path. It reads relation/post aggregate truth but does not enumerate author posts to recompute Class 1 display-derived `like_received`. If a previous Redis snapshot is readable, preserve `like_received` and `favorite_received`; otherwise use zero/defaults.

**Tech Stack:** Java, Redis via existing `CountRedisOperations`, Mockito tests, Maven.

---

## File Structure

- Modify: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/counter/service/UserCounterService.java`
- Modify: `nexus-infrastructure/src/test/java/cn/nexus/infrastructure/adapter/counter/service/UserCounterServiceTest.java`
- Reference: `nexus-domain/src/main/java/cn/nexus/domain/counter/model/valobj/UserCounterType.java`

## Exact Behavior

- `forceRebuildClass2Counters(userId)` writes a full user snapshot.
- Rebuilt values:
  - `FOLLOWING = relationRepository.countActiveRelationsBySource(userId, 1)`
  - `FOLLOWER = relationRepository.countFollowerIds(userId)`
  - `POST = contentRepository.countPublishedPostsByUser(userId)`
  - `LIKE_RECEIVED = previous readable snapshot value, else 0`
  - `FAVORITE_RECEIVED = previous readable snapshot value, else 0`
- Must not call `contentRepository.listPublishedPostIdsByUser(...)`.

### Task 1: Write Failing Tests

- [ ] **Step 1: Add preserve-like test**

```java
@Test
void forceRebuildClass2Counters_shouldPreserveReadableLikeReceived() {
    writeUserSnapshot(31L, Map.of(
            "following", 5L,
            "follower", 6L,
            "post", 7L,
            "like_received", 99L,
            "favorite_received", 8L
    ));
    when(relationRepository.countActiveRelationsBySource(31L, 1)).thenReturn(10);
    when(relationRepository.countFollowerIds(31L)).thenReturn(20);
    when(contentRepository.countPublishedPostsByUser(31L)).thenReturn(30L);

    service.forceRebuildClass2Counters(31L);

    UserRelationCounterVO counters = service.readRelationCountersWithVerification(31L);
    assertThat(counters.getFollowings()).isEqualTo(10L);
    assertThat(counters.getFollowers()).isEqualTo(20L);
    assertThat(counters.getPosts()).isEqualTo(30L);
    assertThat(counters.getLikedPosts()).isEqualTo(99L);
    verify(contentRepository, never()).listPublishedPostIdsByUser(any());
}
```

- [ ] **Step 2: Add malformed snapshot fallback test**

If previous snapshot cannot decode, expect `likedPosts == 0L` after repair.

### Task 2: Implement Minimal Repair

- [ ] **Step 1: Add helper `readCurrentUserSnapshot`**

Private helper returns decoded snapshot map or empty map.

- [ ] **Step 2: Add `rebuildClass2Snapshot`**

Do not call `sumLikeReceivedBestEffort`.

- [ ] **Step 3: Update `rebuildAllCounters`**

Because destructive change is allowed, make `rebuildAllCounters(userId)` delegate to `forceRebuildClass2Counters(userId)`.

### Task 3: Verify

- [ ] **Step 1: Run focused tests**

```powershell
& 'C:\Users\Administrator\Desktop\文档\.codex\tools\apache-maven-3.9.6\bin\mvn.cmd' -pl nexus-infrastructure -Dtest=UserCounterServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 2: Commit**

```powershell
git add nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/counter/service/UserCounterService.java nexus-infrastructure/src/test/java/cn/nexus/infrastructure/adapter/counter/service/UserCounterServiceTest.java
git commit -m "feat: add class2 counter repair preserving likes"
```

## Ambiguity Review

No architecture choice remains. `like_received` is preserved if readable and zeroed only if unreadable; synchronous post enumeration is forbidden.
