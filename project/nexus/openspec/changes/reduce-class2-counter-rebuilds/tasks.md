## 1. Regression Tests

- [x] 1.1 Add domain tests proving follow projection increments `following` and `follower` via INCRBY without calling `rebuildAllCounters`.
- [x] 1.2 Add domain tests proving duplicate follow and duplicate unfollow projections do not increment counters (edge detector returns false).
- [x] 1.3 Add domain tests proving post projection increments `post` counter only on real published-state edges and rejects stale `relationEventId` events.
- [x] 1.4 Add domain tests proving post projection does not call `rebuildAllCounters`.

## 2. Post Projection State

- [x] 2.1 Add `post_counter_projection` DDL (single table: `post_id`, `author_id`, `projected_published`, `last_event_id`, timestamps).
- [x] 2.2 Add domain repository contract for post counter projection state.
- [x] 2.3 Add infrastructure DAO, PO, and MyBatis mapper for `post_counter_projection`.

## 3. Processor Rewrite

- [x] 3.1 Change follow projection to call `incrementFollowings` / `incrementFollowers` based on `saveFollowerIfAbsent` / `deleteFollowerIfPresent` return values.
- [x] 3.2 Change unfollow projection similarly, using decrement deltas (-1).
- [x] 3.3 Change post projection to use `post_counter_projection` for edge detection, rejecting stale `relationEventId`, and calling `incrementPosts` on real edges.
- [x] 3.4 Remove all normal-path calls to unguarded `rebuildAllCounters` from `RelationCounterProjectionProcessor`.
- [x] 3.5 Keep adjacency cache updates behavior compatible with current relation queries.

## 4. Rebuild Cleanup

- [x] 4.1 Split Class 2 repair method to rebuild only `following`, `follower`, and `post`, preserving readable `like_received` from existing snapshot or defaulting to zero.
- [x] 4.2 Keep `maybeVerifyRelationSlots` sampling verification as the automatic drift correction safety net.
- [x] 4.3 Keep explicit `rebuildAllCounters` available for read-path malformed snapshot repair.

## 5. Verification

- [x] 5.1 Run targeted unit tests for `RelationCounterProjectionProcessor` and `UserCounterService`.
- [x] 5.2 Run trigger listener tests to verify RabbitMQ consumer idempotency still works.
- [x] 5.3 Run integration or smoke tests covering follow/unfollow, publish/delete, relation counter reads, and malformed snapshot repair.
