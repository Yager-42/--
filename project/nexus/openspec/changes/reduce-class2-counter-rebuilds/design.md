## Context

Nexus separates counters into Class 1 interaction counters and Class 2 DB-derived counters. Class 1 uses Redis bitmap truth and asynchronous aggregation. Class 2 should use MySQL business truth, outbox events, RabbitMQ projection, persistent idempotency, and repair rebuilds.

The current Class 2 processor calls `rebuildAllCounters` during normal projection:

```text
FOLLOW/UNFOLLOW -> rebuild source + target
POST state      -> rebuild author
```

Each rebuild reads relation counts, post counts, published post IDs, and then sums object-like counters for the author's posts. That is correct as a repair mechanism, but too expensive as the default event-consumption path.

## Goals / Non-Goals

**Goals:**

- Make normal Class 2 projection O(1) per effective event by using Redis snapshot increments.
- Preserve DB truth as the final repair source for `following`, `follower`, and `post`.
- Keep RabbitMQ/outbox/persistent consumer idempotency as the transport safety boundary.
- Coalesce rebuild requests in MySQL so repair load is bounded under duplicate events, drift, or hot users without losing requests if Redis is flushed.
- Prevent `like_received` best-effort rebuild from dominating Class 2 repair cost.

**Non-Goals:**

- Change public relation-counter API response fields.
- Make `USER.like_received` a durable no-drift Class 2 counter.
- Reintroduce legacy replay/gap-log counter semantics.
- Change Class 1 object-like bitmap truth or Kafka aggregation behavior.
- Preserve legacy counter snapshots or provide migration/backfill compatibility. This change is destructive.

## Decisions

### 1. Normal Class 2 events enqueue durable counter deltas, not rebuild snapshots

Follow projection will use the projection table operation as the edge detector:

```text
FOLLOW ACTIVE
  saveFollowerIfAbsent true  -> enqueue source.following +1, target.follower +1
  saveFollowerIfAbsent false -> no-op for counters

UNFOLLOW
  deleteFollowerIfPresent true  -> enqueue source.following -1, target.follower -1
  deleteFollowerIfPresent false -> no-op for counters
```

The processor writes these delta rows to MySQL in the same transaction as follower/post projection state. It does not synchronously mutate Redis. A separate bounded worker applies committed deltas to Redis user snapshots and marks rows done. This adds small async visibility latency, but it prevents MySQL projection-state success from being lost if Redis is unavailable after the state change commits.

Alternative considered: keep rebuilds but rate-limit them. That still burns MySQL on normal traffic and only hides the issue under light load.

### 2. Post projection tracks published-state edges

Post events need explicit projection state because event type alone does not prove a counter edge. Add a lightweight MySQL state table:

```text
post_counter_projection
  post_id BIGINT PRIMARY KEY
  author_id BIGINT NOT NULL
  projected_published TINYINT NOT NULL
  last_event_id BIGINT NOT NULL
  create_time DATETIME NOT NULL
  update_time DATETIME NOT NULL
```

Projection compares previous state to target state:

```text
false -> true   author.post +1
true  -> false  author.post -1
same state      no-op
```

The repository must update this row transactionally with counter delta outbox enqueue. If an incoming post event has `relationEventId <= last_event_id`, the repository returns a stale/no-op result and does not change projected state or enqueue a delta. A post's `author_id` is treated as a business invariant: the same `post_id` cannot change authors. Implementation may log or test-guard mismatches, but must not design author migration behavior in this change.

Redis-only state and embedding projection fields into `content_post` are rejected for this change: Redis can lose the final idempotency boundary, while `content_post` would couple content truth to counter projection internals.

### 3. Counter deltas are applied through a MySQL outbox

Introduce a durable delta outbox:

```text
user_counter_delta_outbox
  id BIGINT PRIMARY KEY
  source_event_id BIGINT NOT NULL
  counter_user_id BIGINT NOT NULL
  counter_type VARCHAR(32) NOT NULL       -- FOLLOWING, FOLLOWER, POST
  delta BIGINT NOT NULL
  status VARCHAR(16) NOT NULL             -- PENDING, PROCESSING, DONE, FAIL
  retry_count INT NOT NULL
  next_retry_time DATETIME NULL
  processing_time DATETIME NULL
  last_error VARCHAR(512) NULL
  create_time DATETIME NOT NULL
  update_time DATETIME NOT NULL
  UNIQUE KEY uk_event_user_type (source_event_id, counter_user_id, counter_type)
```

The processor enqueues one row per affected counter slot. Duplicate enqueue for the same `(source_event_id, counter_user_id, counter_type)` is an idempotent no-op. The apply worker fetches bounded `PENDING` and retryable `FAIL` rows, marks each row `PROCESSING`, calls the existing Redis snapshot increment method for the slot, then marks `DONE`.

`PROCESSING` rows are not replayed as increments. If a worker crashes after Redis increment succeeds but before `markDone`, the exact Redis side effect is unknown and re-running the delta can double-count. A stale `PROCESSING` row is therefore finalized as `UNKNOWN_APPLIED`, and the worker records a coalesced `user_counter_rebuild_request` for that `counter_user_id` with reason `DELTA_UNKNOWN_RESULT`. Class 2 repair then converges `following`, `follower`, and `post` from MySQL business truth. If Redis increment throws before returning, the worker records `FAIL`, increments `retry_count`, and schedules `next_retry_time`; only `FAIL` rows are retried as deltas.

This is the chosen consistency trade-off:

```text
RabbitMQ event
  -> consumer idempotency row STARTED
  -> projection state + delta outbox commit
  -> consumer idempotency row DONE
  -> async delta worker applies Redis
```

Public counter reads can temporarily lag behind committed projection state until the worker catches up. Availability is preferred over synchronous Redis coupling; durable outbox rows make the lag recoverable.

### 4. Rebuild requests become MySQL-backed coalesced repair work

Introduce repair-oriented service entry points:

```text
requestRebuild(userId, reason)      // durable, coalesced, async
forceRebuildClass2(userId)          // immediate, explicit repair
tryRepairClass2Within(userId, ms)   // read-path short timeout
```

`requestRebuild` writes or updates `user_counter_rebuild_request`:

```text
user_counter_rebuild_request
  user_id BIGINT PRIMARY KEY
  reason VARCHAR(64) NOT NULL
  status VARCHAR(16) NOT NULL      -- PENDING, PROCESSING, DONE, FAIL
  request_count INT NOT NULL
  next_retry_time DATETIME NULL
  processing_time DATETIME NULL
  last_rebuild_time DATETIME NULL
  last_error VARCHAR(512) NULL
  create_time DATETIME NOT NULL
  update_time DATETIME NOT NULL
```

Repeated requests for the same user update the existing row instead of inserting duplicates. A scheduled worker fetches bounded `PENDING`, retryable `FAIL`, and stale `PROCESSING` rows. If `last_rebuild_time` is inside the configured coalescing window, it leaves the row pending with `next_retry_time` at the end of the window. Otherwise it marks the row `PROCESSING`, runs Class 2 repair, and then marks `DONE` with `last_rebuild_time = NOW()` or marks `FAIL` with retry metadata. Stale `PROCESSING` requests are eligible for retry after the configured stale timeout so worker crashes do not permanently strand repair intent.

Read-time malformed snapshot repair uses a short synchronous attempt first. If lock acquisition, rate limit, or timeout prevents repair, the read path records a durable async repair request and returns a safe fallback. Normal processor code must not call unguarded `rebuildAllCounters`.

### 5. Split Class 2 rebuild from Class 1 derived rebuild cost

The cheap Class 2 repair rebuilds only:

- `following`
- `follower`
- `post`
- reserved slots with zero/defaults

`like_received` remains Class 1 display-derived. Class 2 repair must preserve the current `like_received` slot when a valid previous snapshot exists, or use zero only when no readable previous snapshot exists. A separate low-priority best-effort worker may refresh `like_received`; Class 2 repair never synchronously enumerates all author posts for this slot.

## Risks / Trade-offs

- [Lost increment due to projection bug] -> Keep consumer idempotency tests and edge-detector tests for duplicate and out-of-order events.
- [Delta outbox backlog] -> Counter reads may lag, but pending rows are durable; expose worker batch/retry settings and keep repair path available.
- [Unknown Redis increment result] -> Never replay stale `PROCESSING` delta rows as increments; finalize them as unknown and schedule Class 2 repair.
- [Post events arrive out of order] -> Use durable projection state and reject `relationEventId <= last_event_id`; do not blindly apply event status deltas.
- [Temporary drift after repair is deferred] -> Read path attempts short synchronous repair first, then persists durable async repair and returns a fallback if repair cannot complete quickly.
- [Schema/data reset required] -> This is an accepted destructive change; deploy with fresh projection tables and no compatibility backfill.
- [Like-received becomes less eagerly repaired] -> This is acceptable because `like_received` is Class 1 display-derived, not a Class 2 no-drift counter.

## Destructive Rollout Plan

1. Add tests that lock current desired behavior: normal follow/post projection must not call `rebuildAllCounters`.
2. Add `post_counter_projection`, `user_counter_delta_outbox`, and `user_counter_rebuild_request` storage and repository contracts.
3. Change `RelationCounterProjectionProcessor` to use edge-based durable delta enqueue.
4. Add the bounded counter-delta apply worker.
5. Introduce coalesced rebuild request APIs and update sampled verification to use durable repair requests.
6. Split Class 2 repair from `like_received` best-effort recomputation.
7. Run targeted domain/infrastructure/trigger tests and relevant integration smoke tests.

Rollback strategy: none at the data-compatibility level. If this change is reverted, fresh projection tables and counter snapshots must be recreated from business truth.
