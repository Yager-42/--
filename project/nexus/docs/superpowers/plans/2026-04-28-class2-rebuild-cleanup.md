# Class 2 Rebuild Cleanup — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split Class 2 repair (following, follower, post) from like_received best-effort computation so rebuilds no longer enumerate all author posts.

**Architecture:** Add `rebuildClass2Slots` method to `UserCounterService`. Modify `rebuildUserSnapshot` to use it. `sumLikeReceivedBestEffort` kept for future low-priority refresh but removed from the synchronous repair path.

**Tech Stack:** Java 21+, Redis SDS, Lombok

---

### Task R9: Split Class 2 rebuild from like_received

**Files:**
- MODIFY: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/counter/service/UserCounterService.java`

- [ ] **Step 1: Add `rebuildClass2Slots` method**

Insert after the `rebuildUserSnapshot` method:

```java
private Map<String, Long> rebuildClass2Slots(Long userId, Map<String, Long> previousSnapshot) {
    Map<String, Long> rebuilt = CountRedisSchema.userSnapshotDefaults();
    rebuilt.put(UserCounterType.FOLLOWING.getCode(),
            Math.max(0L, relationRepository.countActiveRelationsBySource(userId, 1)));
    rebuilt.put(UserCounterType.FOLLOWER.getCode(),
            Math.max(0L, relationRepository.countFollowerIds(userId)));
    rebuilt.put(UserCounterType.POST.getCode(),
            Math.max(0L, contentRepository.countPublishedPostsByUser(userId)));
    long preservedLikeReceived = 0L;
    if (previousSnapshot != null) {
        Long prev = previousSnapshot.get(UserCounterType.LIKE_RECEIVED.getCode());
        if (prev != null) {
            preservedLikeReceived = Math.max(0L, prev);
        }
    }
    rebuilt.put(UserCounterType.LIKE_RECEIVED.getCode(), preservedLikeReceived);
    rebuilt.put(UserCounterType.FAVORITE_RECEIVED.getCode(), 0L);
    return rebuilt;
}
```

- [ ] **Step 2: Modify `rebuildUserSnapshot` to use new method**

Replace the body of `rebuildUserSnapshot` (lines 201-212):

```java
private Map<String, Long> rebuildUserSnapshot(Long userId) {
    return rebuildClass2Slots(userId, Map.of());
}
```

- [ ] **Step 3: Verify compilation**

```bash
cd /Users/rr/Desktop/revive/--/project/nexus && mvn compile -pl nexus-infrastructure -am -q -DskipTests
```
Expected: BUILD SUCCESS

- [ ] **Step 4: Update existing tests — four tests assert non-zero like_received after rebuild**

After R9, `rebuildUserSnapshot` no longer calls `sumLikeReceivedBestEffort`. The following 4 tests in `UserCounterServiceTest.java` must have their `like_received`/`likedPosts` assertions changed to `0L` and their dead stubs removed.

**Test 1: `malformedLikeReceivedSnapshotTriggersMixedRebuild` (line 81)**

Change:
```java
assertEquals(5L, likeReceived);
```
To:
```java
assertEquals(0L, likeReceived);
```

Remove dead stubs for `listPublishedPostIdsByUser` and `objectCounterService.getCounts` (lines 69-75).

**Test 2: `relationCounters_missingSnapshot_shouldRebuildAndReturnPublicFields` (line 195)**

Change:
```java
assertEquals(5L, counters.getLikedPosts());
```
To:
```java
assertEquals(0L, counters.getLikedPosts());
```

Remove dead stubs for `listPublishedPostIdsByUser` (lines 181-182) and `objectCounterService.getCounts` (lines 183-184).

**Test 3: `relationCounters_oversizedSnapshot_shouldRebuildAsMalformed` (line 230)**

Change:
```java
assertEquals(9L, counters.getLikedPosts());
```
To:
```java
assertEquals(0L, counters.getLikedPosts());
```

Remove dead stubs for `listPublishedPostIdsByUser` (lines 216-217) and `objectCounterService.getCounts` (lines 218-219).

**Test 4: `relationCounters_sampleMismatch_shouldTriggerRebuild` (lines 254, 267)**

Change:
```java
assertEquals(7L, counters.getLikedPosts());
```
To:
```java
assertEquals(0L, counters.getLikedPosts());
```

Remove dead stubs for `listPublishedPostIdsByUser` (lines 252-253) and `objectCounterService.getCounts` (lines 254-255).

Also remove dead stubs from `rebuildAllCountersUsesMixedSourcesAndWritesFavoriteZero` (lines 147-156): `listPublishedPostIdsByUser` and `objectCounterService.getCounts` stubs are no longer called.

- [ ] **Step 5: Run tests to verify they pass**

```bash
cd /Users/rr/Desktop/revive/--/project/nexus && mvn test -pl nexus-infrastructure -Dtest=UserCounterServiceTest -DfailIfNoTests=false
```
Expected: **All tests PASS**

- [ ] **Step 6: Commit**

```bash
git add nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/counter/service/UserCounterService.java \
        nexus-infrastructure/src/test/java/cn/nexus/infrastructure/adapter/counter/service/UserCounterServiceTest.java
git commit -m "refactor: split class2 repair from like_received enumeration in rebuild path"
```
