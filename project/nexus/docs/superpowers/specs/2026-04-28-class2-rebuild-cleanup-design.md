# Class 2 Rebuild Cleanup — Design Spec

**Topic:** Split Class 2 repair from like_received best-effort computation
**Change:** reduce-class2-counter-rebuilds
**Group:** 4 of 5
**Date:** 2026-04-28

## Context

Current `rebuildUserSnapshot` rebuilds all 5 slots including `like_received` by enumerating all author posts and calling `ObjectCounterService.getCounts()` per post. This is the dominant cost of a rebuild. `like_received` is Class 1 derived — it should not block Class 2 repair.

## Scope

Modify `UserCounterService` to split Class 2 repair (following, follower, post) from like_received computation. Sampling verification and read-path repair guards remain unchanged.

## Design

### New Method: `rebuildClass2Slots`

Adds a dedicated Class 2 rebuild that touches only `following`, `follower`, and `post`. Preserves `like_received` from existing readable snapshot, or defaults to zero.

```java
private Map<String, Long> rebuildClass2Slots(Long userId, Map<String, Long> previousSnapshot) {
    Map<String, Long> rebuilt = new HashMap<>(CountRedisSchema.userSnapshotDefaults());
    
    // Class 2 truth from MySQL COUNT
    rebuilt.put(UserCounterType.FOLLOWING.getCode(),
            Math.max(0L, relationRepository.countActiveRelationsBySource(userId, 1)));
    rebuilt.put(UserCounterType.FOLLOWER.getCode(),
            Math.max(0L, relationRepository.countFollowerIds(userId)));
    rebuilt.put(UserCounterType.POST.getCode(),
            Math.max(0L, contentRepository.countPublishedPostsByUser(userId)));
    
    // Preserve Class 1 derived like_received from previous snapshot
    long preservedLikeReceived = 0L;
    if (previousSnapshot != null) {
        Long prev = previousSnapshot.get(UserCounterType.LIKE_RECEIVED.getCode());
        if (prev != null) preservedLikeReceived = Math.max(0L, prev);
    }
    rebuilt.put(UserCounterType.LIKE_RECEIVED.getCode(), preservedLikeReceived);
    rebuilt.put(UserCounterType.FAVORITE_RECEIVED.getCode(), 0L);
    
    return rebuilt;
}
```

### Modified: `rebuildAllCounters`

Call chain: `rebuildAllCounters` → `rebuildSnapshot` → `rebuildUserSnapshot` (currently builds all 5). Change `rebuildUserSnapshot` to call `rebuildClass2Slots` and remove `sumLikeReceivedBestEffort` from the rebuild path.

### Unchanged

- `maybeVerifyRelationSlots` — still compares following/follower against MySQL COUNT, triggers rebuild on drift
- `readRelationCountersWithVerification` — still guards with lock + rate limit, calls rebuild on malformed snapshot
- `sumLikeReceivedBestEffort` — method kept but only called by a separate low-priority refresh path (future), not during Class 2 repair
