# Class 2 Verification — Design Spec

**Topic:** Run verification tests to confirm INCRBY projection correctness
**Change:** reduce-class2-counter-rebuilds
**Group:** 5 of 5
**Date:** 2026-04-28

## Context

After Groups 1-4 implementation, all existing and new tests must pass. Verification covers unit tests for the rewritten processor and rebuild service, consumer idempotency tests, and integration smoke tests.

## Scope

3 tasks covering the complete test suite for the changed components. No new code — all tests already exist or were created in Group 1.

## Test Plan

### 5.1 Unit Tests

```
RelationCounterProjectionProcessorTest (Group 1)
├── followActive_newEdge → PASS: incrementFollowings(+1), incrementFollowers(+1)
├── unfollowActive_removedEdge → PASS: incrementFollowings(-1), incrementFollowers(-1)
├── followActive_duplicate → PASS: no counter calls
├── unfollow_duplicate → PASS: no counter calls
├── post_unpublishedToPublished → PASS: incrementPosts(+1)
├── post_publishedToUnpublished → PASS: incrementPosts(-1)
├── post_sameState → PASS: no incrementPosts
├── post_staleEvent → PASS: no incrementPosts
├── post_nullAuthor → PASS: no incrementPosts
└── process_block → PASS: cache removal only

UserCounterServiceTest → verify existing tests still pass
  - rebuildAllCounters no longer enumerates posts for like_received
  - rebuildClass2Slots preserves previous like_received value
```

### 5.2 Consumer Idempotency

```
RelationCounterProjectConsumerTest or equivalent trigger tests
├── Duplicate event → DUPLICATE_DONE → acked without re-processing
├── Successful event → STARTED → process → DONE → acked
└── Failed event → markFail → nacked without requeue
```

### 5.3 Integration / Smoke

```
RelationEventOutboxPublishJobTest
├── Follow event publishes to Q_FOLLOW and is consumed correctly
├── Unfollow event removes follower and decrements counter
├── Post publish creates projection state and increments author post count
├── Post delete decrements author post count
├── Counter read returns correct values after projection
└── Malformed snapshot repair triggers rebuild
```

### Expected Results

| Component | Expected |
|-----------|----------|
| `RelationCounterProjectionProcessorTest` | All 10 tests PASS |
| `UserCounterServiceTest` | All existing tests PASS |
| Trigger consumer tests | All existing tests PASS |
| Integration tests | All existing tests PASS |
