# Class 2 Regression Tests — Design Spec

**Topic:** Regression tests for INCRBY-based Class 2 projection
**Change:** reduce-class2-counter-rebuilds
**Group:** 1 of 5
**Date:** 2026-04-28

## Context

The `RelationCounterProjectionProcessor` currently calls `rebuildAllCounters` on every event. After this change, it will use synchronous Redis SDS atomic increments (`incrementFollowings`, `incrementFollowers`, `incrementPosts`) based on edge detection. Existing tests (8 tests in `RelationCounterProjectionProcessorTest.java`) verify old behavior and must be replaced with tests that lock in the new behavior before implementation begins.

## Scope

Rewrite `RelationCounterProjectionProcessorTest` to cover 4 task requirements (1.1–1.4) with ~9 test methods. Tests must FAIL with the current processor implementation and PASS after Groups 2–4 are implemented.

## Test Design

### Setup

- Test framework: JUnit Jupiter 5 + Mockito
- No Spring context (pure unit test)
- Mock dependencies: `IRelationRepository`, `IRelationAdjacencyCachePort`, `IUserCounterService`, plus new `IPostCounterProjectionRepository` for post tests
- Two helper helpers remain: `followEvent()`, `postEvent()` (updated), `blockEvent()` kept

### 1.1 Follow projection INCRBY on edge (2 tests)

**followActive_newEdge** — `saveFollowerIfAbsent` returns true
- Event: FOLLOW ACTIVE, source=1, target=2, relationEventId=100
- Stub: `findRelation(1, 2, 1)` → active RelationEntity
- Stub: `saveFollowerIfAbsent(100, 2, 1, ...)` → true
- Verify: `incrementFollowings(1, +1)`, `incrementFollowers(2, +1)`
- Verify: `addFollowWithTtl(1, 2, *, 7200)`
- Verify: `rebuildAllCounters` NEVER called for any user

**unfollowActive_removedEdge** — `deleteFollowerIfPresent` returns true
- Event: FOLLOW UNFOLLOW, source=1, target=2, relationEventId=101
- Stub: `findRelation(1, 2, 1)` → null (not active)
- Stub: `deleteFollowerIfPresent(2, 1)` → true
- Verify: `incrementFollowings(1, -1)`, `incrementFollowers(2, -1)`
- Verify: `removeFollowWithTtl(1, 2, 7200)`
- Verify: `rebuildAllCounters` NEVER called

### 1.2 Duplicate edge → no counter calls (2 tests)

**followActive_duplicate** — `saveFollowerIfAbsent` returns false
- Event: FOLLOW ACTIVE, same IDs
- Stub: `saveFollowerIfAbsent` → false
- Verify: `addFollowWithTtl` called (cache update still happens)
- Verify: `incrementFollowings` NEVER, `incrementFollowers` NEVER
- Verify: `rebuildAllCounters` NEVER

**unfollow_duplicate** — `deleteFollowerIfPresent` returns false
- Event: FOLLOW UNFOLLOW
- Stub: `deleteFollowerIfPresent` → false
- Verify: `removeFollowWithTtl` called (cache update still happens)
- Verify: `incrementFollowings` NEVER, `incrementFollowers` NEVER
- Verify: `rebuildAllCounters` NEVER

### 1.3 Post state edge INCRBY (4 tests)

**post_unpublished_to_published** — false→true edge
- Event: POST PUBLISHED, author=1, postId=100, relationEventId=200
- Stub: `postProjRepo.compareAndWrite(100, 1, true, 200)` → EDGE_TRANSITION (false→true)
- Verify: `incrementPosts(1, +1)`
- Verify: `rebuildAllCounters` NEVER

**post_published_to_unpublished** — true→false edge
- Event: POST UNPUBLISHED, author=1, postId=100, relationEventId=201
- Stub: `postProjRepo.compareAndWrite(100, 1, false, 201)` → EDGE_TRANSITION (true→false)
- Verify: `incrementPosts(1, -1)`
- Verify: `rebuildAllCounters` NEVER

**post_same_state** — no edge
- Event: POST PUBLISHED, same IDs
- Stub: `postProjRepo.compareAndWrite(100, 1, true, 202)` → SAME_STATE
- Verify: `incrementPosts` NEVER
- Verify: `rebuildAllCounters` NEVER

**post_stale_event** — rejected by monotonic eventId
- Event: POST PUBLISHED, relationEventId=199 (<= last_event_id=200)
- Stub: `postProjRepo.compareAndWrite(100, 1, true, 199)` → STALE_EVENT
- Verify: `incrementPosts` NEVER
- Verify: `rebuildAllCounters` NEVER

### 1.4 No rebuildAllCounters on normal path

Covered implicitly by `verify(userCounterService, never()).rebuildAllCounters(any())` in every test above. No separate test method needed — every test enforces this constraint.

### Test file structure

```
RelationCounterProjectionProcessorTest.java
├── setUp() — 4 mocks (add IPostCounterProjectionRepository)
├── followActive_newEdge()
├── unfollowActive_removedEdge()
├── followActive_duplicate()
├── unfollow_duplicate()
├── process_block() — kept from existing, no counter calls expected
├── post_unpublished_to_published()
├── post_published_to_unpublished()
├── post_same_state()
├── post_stale_event()
└── helpers: followEvent(), postEvent(), blockEvent()
```

## New Domain Contract

`IPostCounterProjectionRepository` (new interface in domain layer):

```java
enum EdgeResult { EDGE_TRANSITION, SAME_STATE, STALE_EVENT }

EdgeResult compareAndWrite(Long postId, Long authorId,
                           boolean targetPublished, Long relationEventId);
```

This is the single method the processor calls. Returns:
- `EDGE_TRANSITION` if state changed (false→true or true→false) and eventId > lastEventId
- `SAME_STATE` if target state equals projected state
- `STALE_EVENT` if eventId <= lastEventId

## Implementation Notes

- Tests are written in Group 1 but will FAIL until Group 3 (Processor Rewrite) is complete — this is expected TDD flow.
- The post projection repository mock will throw by default in follow tests (should not be called).
- Block tests remain unchanged — block projection doesn't call counter methods.
