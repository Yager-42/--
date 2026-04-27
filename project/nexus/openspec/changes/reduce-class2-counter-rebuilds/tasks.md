## 1. Regression Tests

- [ ] 1.1 Add domain tests proving follow projection enqueues durable `following` and `follower` delta outbox rows without calling `rebuildAllCounters`.
- [ ] 1.2 Add domain tests proving duplicate follow and duplicate unfollow projections do not enqueue counter delta outbox rows.
- [ ] 1.3 Add domain tests proving post projection enqueues `post` delta outbox rows only on durable published-state edges and rejects stale `relationEventId` events.
- [ ] 1.4 Add infrastructure tests for MySQL-backed coalesced user rebuild requests, durable counter delta outbox rows, and bounded dirty-user draining.

## 2. Projection State And Interfaces

- [ ] 2.1 Add a MySQL-backed post counter projection state repository contract keyed by `postId`.
- [ ] 2.2 Add infrastructure storage and mapper support for `post_counter_projection`.
- [ ] 2.3 Extend counter domain interfaces with repair-oriented APIs and durable delta-outbox APIs while preserving existing public count reads.
- [ ] 2.4 Add a Class 2 repair method that rebuilds `following`, `follower`, and `post` while preserving readable `like_received` and avoiding synchronous post enumeration for `like_received`.

## 3. Processor Rewrite

- [ ] 3.1 Change follow projection to use `saveFollowerIfAbsent` and `deleteFollowerIfPresent` as edge detectors for durable counter delta outbox enqueue.
- [ ] 3.2 Change post projection to compare durable projected state, reject stale `relationEventId` events, and enqueue only real published/unpublished edge deltas.
- [ ] 3.3 Remove normal-path calls to unguarded `rebuildAllCounters` from `RelationCounterProjectionProcessor`.
- [ ] 3.4 Keep cache updates and follower projection table maintenance behavior compatible with current relation queries.

## 4. Rebuild Control

- [ ] 4.1 Implement MySQL-backed dirty-user rebuild scheduling with per-user coalescing/rate limiting.
- [ ] 4.2 Add a bounded repair worker or scheduled job to drain durable dirty user rebuild requests.
- [ ] 4.3 Add a bounded counter-delta worker to apply retryable durable outbox rows to Redis snapshots and terminalize stale `PROCESSING` rows as `UNKNOWN_APPLIED` repair requests.
- [ ] 4.4 Update sampled verification to request durable coalesced repair instead of synchronously amplifying rebuilds.
- [ ] 4.5 Keep explicit `forceRebuild` behavior available for tests, migration, and operator repair.

## 5. Verification

- [ ] 5.1 Run targeted unit tests for `RelationCounterProjectionProcessor`, `UserCounterService`, projection-state repository, rebuild-request repository, and delta-outbox repository code.
- [ ] 5.2 Run trigger listener tests to verify RabbitMQ consumer idempotency still marks events done only after successful projection.
- [ ] 5.3 Run integration or smoke tests covering follow/unfollow, publish/delete, relation counter reads, and malformed snapshot repair.
- [ ] 5.4 Document rollout and rollback notes for the new incremental Class 2 projection path.
