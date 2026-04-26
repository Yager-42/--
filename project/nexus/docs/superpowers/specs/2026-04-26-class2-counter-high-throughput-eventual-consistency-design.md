# Class 2 Counter High-Throughput Eventual Consistency Design

## Status

Draft for review.

## Context

The zhiguang counter replacement splits Nexus counters into two classes.
Class 1 interaction counters use Redis bitmap truth and Kafka aggregation.
Class 2 counters use MySQL business truth, transactional outbox, RabbitMQ projection, persistent idempotency, and Redis `ucnt:{userId}` snapshots.

The current Class 2 relation/post projection processor uses `rebuildAllCounters(userId)` in normal RabbitMQ consumption. That is correct-biased, but it is too expensive under high concurrency because each rebuild can run multiple MySQL count queries and can also read many object counters while summing `like_received`.

This design only changes the Class 2 projection strategy. It must not redesign Class 1 object likes, Kafka `counter-events`, bitmap truth, `cnt:*`, `COMMENT.reply` removal, or `like_received` fast-path behavior.

## Goals

- Keep Class 2 counters eventually consistent under high concurrency.
- Improve RabbitMQ projection availability and QPS by removing synchronous full rebuild from the normal consumer path.
- Prevent duplicate delivery, concurrent delivery, and retry from double-counting `USER.following`, `USER.follower`, or `USER.post`.
- Provide a bounded repair path for projection failures without blocking the main queue on expensive rebuilds.
- Keep `ucnt:{userId}` as the user counter snapshot shape.

## Non-Goals

- Do not change Class 1 `POST.like`, `COMMENT.like`, or `USER.like_received` truth/aggregation semantics.
- Do not add strong read-after-write consistency for public counter reads.
- Do not restore `COMMENT.reply`.
- Do not make `like_received` a durable Class 2 counter.
- Do not move relation or post business truth out of MySQL.

## Counter Scope

Active Class 2 slots in this change:

- `USER.following`
- `USER.follower`
- `USER.post`

Reserved or out-of-scope slots:

- `USER.like_received` remains a Class 1 display-derived slot. Class 2 repair should preserve or ignore it unless performing an explicit full mixed-source rebuild.
- `USER.favorite_received` remains reserved and inactive.

## Chosen Approach

Use state-transition incremental projection for the normal path, and move full repair into an asynchronous merged repair path.

Normal RabbitMQ consumers must be O(1) relative to user fanout size and post count. They must not call `rebuildAllCounters(userId)` during ordinary successful projection.

## Relation Truth Lifecycle Requirement

`RELATION_FOLLOW` must keep one durable business row identity per `(sourceId, targetId, relationType)`.

Required lifecycle:

- first follow inserts the row with version `0`
- unfollow does not physically delete the row; it transitions status to inactive and increments version
- re-follow reactivates the same row and increments version again

The write side must lock the row, apply `(status, version)` CAS semantics, and emit the committed post-update version into the projection event.

Without this monotonic row lifecycle, durable projection ordering by relation version is not defensible.

## Normal Projection Flow

### Common Consumer Contract

1. The consumer receives a RabbitMQ relation/post projection event.
2. The consumer calls `reliable_mq_consumer_record.startManual(eventId, consumerName, payload)`.
3. `DUPLICATE_DONE` is acknowledged immediately.
4. `IN_PROGRESS` is requeued or retried according to the existing manual-ack behavior.
5. `STARTED` enters the processor.
6. The processor applies only confirmed state-transition side effects.
7. The consumer marks the event `DONE` only after all required side effects or repair registration completes.
8. The consumer acknowledges only after the transaction succeeds.
9. If the consumer transaction later fails after a Redis delta was already applied, replay must still be safe because Redis counter mutation is per-event idempotent by event id and counter slot.

### Follow Created Projection

For a `FOLLOW ACTIVE` event:

1. Reject or no-op old events using the projection ordering rule.
2. Confirm the current relation truth row is active.
3. Call `saveFollowerIfAbsent(targetId, sourceId)`.
4. If the insert changes state:
   - apply `USER.following` for `sourceId` by `+1` through per-event idempotent Redis delta
   - apply `USER.follower` for `targetId` by `+1` through per-event idempotent Redis delta
   - add adjacency cache entries `uf:flws:{sourceId}` and `uf:fans:{targetId}`
5. If the insert is ignored:
   - do not guess that the system is already converged
   - register Class 2 repair for `sourceId` and `targetId`
   - repair may coalesce duplicates, but the event must not silently skip correction work

### Follow Canceled Projection

For a `FOLLOW UNFOLLOW` event:

1. Reject or no-op old events using the projection ordering rule.
2. Confirm the current relation truth row is no longer active.
3. Call `deleteFollowerIfPresent(targetId, sourceId)`.
4. If the delete changes state:
   - apply `USER.following` for `sourceId` by `-1` through per-event idempotent Redis delta
   - apply `USER.follower` for `targetId` by `-1` through per-event idempotent Redis delta
   - remove adjacency cache entries
5. If the delete affects no rows:
   - do not assume counters are already correct
   - register Class 2 repair for `sourceId` and `targetId`
   - repair may coalesce duplicates, but the event must not silently skip correction work

### Block Projection

`BLOCK` is consumed from a different RabbitMQ queue than `FOLLOW`, so it must not own follower-table or counter transitions.

Required behavior:

- `BLOCK` may invalidate adjacency cache in both directions
- `BLOCK` must not delete `user_follower` rows
- `BLOCK` must not increment or decrement `USER.following` or `USER.follower`

The paired `FOLLOW UNFOLLOW` events remain the only counter-transition carriers for relation counters.

### Post Projection

The content write side must emit a post counter outbox event only for a real published-state transition.

For a `POST PUBLISHED` event:

- apply `USER.post` for the author by `+1` through per-event idempotent Redis delta

For a `POST UNPUBLISHED` or `POST DELETED` event:

- apply `USER.post` for the author by `-1` through per-event idempotent Redis delta

Duplicate event ids must not increment twice.

## Projection Ordering

The processor must reject old events before applying incremental side effects.

Preferred ordering source:

- relation business row version for relation events
- content post version for post events

Acceptable short-term fallback:

- monotonic outbox event id or outbox create time, only if the deployment guarantees monotonicity for the relevant entity key

Ordering granularity:

- relation events use `projection_key = follow:{sourceId}:{targetId}`
- post events use `projection_key = post:{postId}`

The system must persist the last projected version/watermark for each `projection_key` in a durable projection-state record.

Required semantics:

- `projection_key` is the only durable ordering identity
- `projection_type` may be stored as audit metadata, but it is not a second primary-key dimension
- an event with version less than or equal to the stored watermark is acknowledged without counter mutation
- repository APIs must return an explicit result such as `ADVANCED` or `STALE`; they must not rely on JDBC affected-row guessing for the idempotency boundary

If the first implementation uses monotonic outbox event id as the ordering source, the spec requires that the id source is monotonic for all events of the same relation pair or post. If that property cannot be proven, the processor must treat ordering as uncertain and enqueue repair rather than applying an incremental update.

If no trustworthy ordering value is available, the event must not apply an incremental counter update in uncertain conditions. It should register Class 2 repair for the affected users instead.

## Projection Failure Handling

The design must handle the window where MySQL projection state changes but Redis `ucnt` increment fails.

Example:

1. `saveFollowerIfAbsent` succeeds.
2. Redis increment fails.
3. The message is retried.
4. `saveFollowerIfAbsent` now returns ignored.

The retry must not blindly skip all side effects, because Redis may still be missing the increment.

Required behavior:

- If state transition and Redis delta both complete, mark the event done.
- If state transition succeeds but Redis or cache side effects fail, register Class 2 repair for affected users before marking the event done.
- If repair registration fails, do not mark the event done; allow retry or DLQ.
- The main consumer must not run full rebuild inline for this failure path.
- If Redis delta succeeded but the enclosing DB transaction later rolls back or `markDone(...)` fails, redelivery must not double-apply the delta; the Redis mutation path must therefore be per-event idempotent.
- If projection watermark advanced but later side effects fail, the system must either register repair before ack or rely on retry/DLQ replay without allowing duplicate Redis delta application.

Affected repair users:

- follow create/cancel: `sourceId` and `targetId`
- post publish/unpublish/delete: author id

## Class 2 Repair Path

Introduce a repair path for Class 2 slots only.

The repair path must use a DB-backed repair task table as the durable source of repair work. Redis Set/Stream may be added only as a coalescing or wake-up optimization, not as the correctness boundary.

Repair task requirements:

- key by `userId` and repair type
- coalesce duplicate pending tasks for the same user
- support retry with backoff
- support lease-based claim recovery for worker crash or long GC pause
- record last error for operation visibility
- allow manual enqueue
- persist claim ownership fields such as `claim_owner`, `claimed_at`, and `lease_until`

Repair worker behavior:

1. Poll or consume pending repair tasks.
2. Acquire per-user lock, for example `ucnt:repair:lock:{userId}`.
3. Apply per-user rate limit, for example 30 to 60 seconds.
4. Rebuild only Class 2 slots by default:
   - `following` from relation truth
   - `follower` from relation truth
   - `post` from published content rows
5. Preserve current `like_received` and `favorite_received` slots unless an explicit full mixed-source rebuild is requested.
6. Write the corrected `ucnt:{userId}` snapshot atomically enough for readers to see either old or new payload, never a malformed partial payload.
7. Mark the repair task done or retry later.

If the worker claims a task but cannot get the Redis rate-limit token or user lock, it must release or reschedule the task explicitly. It must not leave the task stranded in `RUNNING`.

`rebuildAllCounters(userId)` may remain for full mixed-source read repair, but normal Class 2 projection and Class 2 repair should prefer a narrower operation such as `repairClass2Counters(userId)`.

## Read Path

The relation counter read path remains eventually consistent.

It should keep the existing safeguards:

- missing or malformed `ucnt:{userId}` triggers rebuild and second read
- `ucnt:chk:{userId}` throttles sampled verification
- sampled verification compares `following`, `follower`, and `post` against MySQL truth
- mismatch triggers repair or guarded rebuild

For high availability, sampled mismatch should prefer enqueueing Class 2 repair when the request path cannot afford synchronous rebuild. Synchronous guarded rebuild is acceptable for low-frequency malformed snapshot recovery.

## Consistency Guarantees

The design guarantees eventual consistency for Class 2 counters if:

- MySQL business truth and outbox writes commit atomically.
- RabbitMQ eventually delivers or DLQ replay is performed.
- Consumer idempotency is persisted by event id and consumer name.
- `RELATION_FOLLOW` row version is monotonic across follow, unfollow, and re-follow.
- Relation follower table has a unique key on `(user_id, follower_id)` for projection dedupe.
- Redis user-slot delta application is atomic and per-event idempotent.
- Projection failure registers repair when side effects are uncertain.
- Repair worker eventually runs successfully.

The design does not guarantee immediate read-after-write counter accuracy.

## High-Concurrency Behavior

Duplicate follow deliveries:

- handled by consumer idempotency and follower-table uniqueness.

Concurrent follow deliveries:

- only one insert changes follower state, so only one `+1/+1` is allowed.

Concurrent unfollow deliveries:

- only one delete changes follower state, so only one `-1/-1` is allowed.

Follow/unfollow race:

- processor ordering/watermark prevents older events from mutating counters after newer entity state is projected.
- uncertain ordering falls back to repair rather than guessing.

Block/follow-unfollow cross-queue race:

- `BLOCK` does not mutate follower rows or counters, so it cannot consume the only `-1` transition that `FOLLOW UNFOLLOW` needs to apply.
- if `FOLLOW UNFOLLOW` finds projection-state uncertainty or follower-table drift, it must enqueue repair rather than silently no-op.

Redis increment contention:

- user slots must be updated with Lua-based atomic slot mutation, not read-modify-write.
- redelivery after partial failure must not double-apply deltas because the Redis mutation is deduped by event id and slot.

MQ backlog:

- business truth remains in MySQL.
- `ucnt` may lag but should converge as consumers catch up.

## Availability Characteristics

Normal projection is designed to be constant cost:

- no full user rebuild
- no scan of all posts for `like_received`
- no per-event MySQL count queries
- no object-counter reads

Expensive counting is moved to repair workers with user-level coalescing, locking, and rate limiting.

This reduces pressure on:

- RabbitMQ consumers
- MySQL relation/content indexes
- Redis object counter reads

This design is not a full high-availability design for Redis outage scenarios.

It improves steady-state projection availability and gives durable recovery through repair plus DLQ replay, but it does not define:

- Redis-free read fallback
- Redis-free write degradation
- zero-intervention recovery guarantees during prolonged Redis outage

## Observability

The implementation should expose logs or metrics for:

- projection processed count
- duplicate event count
- old event ignored count
- repair task enqueued count
- repair task success/failure count
- repair lag
- consumer DLQ count
- consumer replay count
- repair claim lease timeout count
- `ucnt` sampled verification mismatch count

## Testing Requirements

Unit tests:

- duplicate `FOLLOW ACTIVE` event does not double increment
- concurrent `FOLLOW ACTIVE` processing only increments once
- duplicate `FOLLOW UNFOLLOW` event does not double decrement
- concurrent `FOLLOW UNFOLLOW` processing only decrements once
- old relation event is acknowledged without counter mutation
- old post event is acknowledged without counter mutation
- Redis increment failure after follower-table transition enqueues repair
- Redis delta success followed by consumer transaction rollback does not double increment on replay
- repair registration failure does not mark the event done
- normal processor path does not call `rebuildAllCounters`
- Class 2 repair preserves `like_received` unless full rebuild is requested
- `BLOCK` projection does not mutate follower table or counters
- projection-state repository returns explicit `ADVANCED` / `STALE`
- repair-task claim reclaims expired leases
- rate-limit or user-lock refusal does not strand repair task in `RUNNING`

Integration tests:

- follow/unfollow high-concurrency smoke test converges `ucnt` to MySQL truth
- post publish/unpublish/delete converges `USER.post`
- RabbitMQ redelivery does not double count
- `BLOCK` delivered before paired `FOLLOW UNFOLLOW` still converges counters
- DLQ replay or repair task processing corrects drift
- dead-lettered relation projection is replayable through the existing replay job
- sampled verification mismatch triggers repair or guarded rebuild
- repair worker crash after claim is recoverable by lease expiry
- multi-worker repair claim does not execute one task twice

Load or stress tests:

- normal projection QPS remains stable without per-event MySQL count amplification
- repair worker rate limiting prevents rebuild storms for high-fanout users

## Migration Plan

1. Add tests that capture current rebuild-heavy projection cost and desired no-rebuild normal path.
2. Make `RELATION_FOLLOW` lifecycle monotonic across follow/unfollow/re-follow.
3. Add durable projection ordering/watermark support keyed by `projection_key`.
4. Add the DB-backed Class 2 repair task table and repository with lease-based claim semantics.
5. Change relation/post processor from rebuild-based updates to state-transition increments with per-event idempotent Redis delta application.
6. Add Class 2 repair registration for uncertain side-effect failures.
7. Add Class 2 repair worker processing with lock, coalescing, lease reclaim, and rate limiting.
8. Keep read-path sampled verification as a final guard.
9. Verify old full rebuild is no longer used by normal RabbitMQ projection.
