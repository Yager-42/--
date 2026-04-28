## Context

Nexus separates counters into Class 1 interaction counters and Class 2 DB-derived counters. Class 1 uses Redis bitmap truth and asynchronous Kafka aggregation. Class 2 should use MySQL business truth, outbox events, RabbitMQ projection, persistent idempotency, and repair rebuilds.

The current Class 2 processor calls `rebuildAllCounters` during normal projection:

```text
FOLLOW/UNFOLLOW -> rebuild source + target (3 COUNT queries + post enumeration per user)
POST state      -> rebuild author (same cost)
```

Each rebuild reads `following`, `follower`, and `post` counts from MySQL, enumerates all published posts for the author, and sums per-post `like_received` from Class 1 counters. That is correct as a repair mechanism, but too expensive as the default event-consumption path.

## Goals / Non-Goals

**Goals:**

- Make normal Class 2 projection O(1) per effective event by using Redis snapshot increments.
- Preserve DB truth as the final repair source for `following`, `follower`, and `post`.
- Keep RabbitMQ outbox consumer idempotency as the transport safety boundary.
- Split `like_received` best-effort rebuild from Class 2 repair cost.
- Add minimal post projection state for published-state edge detection.

**Non-Goals:**

- Introduce MySQL delta outbox, rebuild request, or background worker tables.
- Change public relation-counter API response fields.
- Make `USER.like_received` a durable no-drift Class 2 counter.
- Change Class 1 object-like bitmap truth or Kafka aggregation behavior.
- Preserve legacy counter snapshots or provide migration/backfill compatibility. This change is destructive.

## Decisions

### 1. Normal Class 2 events apply synchronous Redis INCRBY, not rebuilds

The existing `UserCounterService.increment()` method already performs atomic Redis SDS slot increments via a Lua script (`CountRedisOperations.INCREMENT_SNAPSHOT_SLOT_SCRIPT`). The processor simply needs to call it.

Follow projection uses the existing follower table operation as the edge detector:

```text
FOLLOW ACTIVE
  saveFollowerIfAbsent true  -> incrementFollowings(source, +1) + incrementFollowers(target, +1)
  saveFollowerIfAbsent false -> no-op for counters

UNFOLLOW
  deleteFollowerIfPresent true  -> incrementFollowings(source, -1) + incrementFollowers(target, -1)
  deleteFollowerIfPresent false -> no-op for counters
```

Redis INCRBY executes within the same `@Transactional` boundary as the MySQL projection write. If Redis is unavailable, the exception triggers a transaction rollback — both MySQL and Redis remain consistent. The event is marked FAIL and retried by the outbox publisher.

The Lua script is atomic (Redis single-threaded execution), so concurrent follows on the same user are correctly serialized — unlike the current `COUNT + SET` rebuild which is Last-Writer-Wins and loses increments under concurrency.

Alternative considered: the delta outbox approach (durable MySQL delta rows + async worker). This adds 3 tables, 2 workers, and a PROCESSING→UNKNOWN_APPLIED state machine to handle a failure mode (Redis timeout after successful EVAL but before client response) that is both extremely rare and already covered by sampling verification.

### 2. Post projection uses a lightweight MySQL state table for edge detection

Unlike follow events where `saveFollowerIfAbsent` already detects edges, post events carry a status that needs previous-state comparison. Add a minimal state table:

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
event_id <= last_event_id: reject stale event
```

This is the only new table. A post's `author_id` is treated as a business invariant: the same `post_id` cannot change authors.

### 3. Rebuild is cold-path repair only

`rebuildAllCounters` stays for:

- Read-path malformed snapshot repair (`readRelationCountersWithVerification`)
- Sampling verification drift correction (`maybeVerifyRelationSlots`, every 300s)
- Operator-requested repair

Normal processor code must not call unguarded `rebuildAllCounters`. The sampling verification already compares Redis `following`/`follower` against MySQL COUNT on read, and triggers a rebuild on mismatch. This serves as the automatic drift correction safety net for the rare case of Redis timeout after successful EVAL.

### 4. Split Class 2 rebuild from like_received cost

Class 2 repair rebuilds only:

- `following`
- `follower`
- `post`
- reserved slots with zero/defaults

`like_received` remains Class 1 display-derived. Class 2 repair preserves the current `like_received` slot when a valid previous snapshot exists, or uses zero when no readable previous snapshot exists. A separate low-priority best-effort worker may refresh `like_received`; Class 2 repair never synchronously enumerates all author posts.

## Consistency Model

```text
Normal path (>99.99% of operations):
  Redis INCRBY Lua (atomic) -> instant consistency
  No concurrent write loss (Redis serializes all operations on a key)

Redis unavailable:
  Exception -> @Transactional rollback -> MySQL + Redis both unchanged
  Event retried via outbox -> consistent on retry

Redis timeout (EVAL succeeded, response lost):
  +1 drift -> sampling verification detects within 300s -> rebuild corrects
  Probability: extremely low (same-network Redis <1ms latency)
```

## Risks / Trade-offs

- [Redis timeout drift] -> Sampling verification corrects within 300s. Acceptable for social display counters.
- [Post events out of order] -> `post_counter_projection` rejects stale `relationEventId`; does not blindly apply event status deltas.
- [Concurrent follow rebuild lost] -> Eliminated by Redis atomic INCRBY replacing COUNT+SET Last-Writer-Wins.
- [Schema/data reset required] -> Destructive change; deploy with fresh projection table and no compatibility backfill.
- [Like-received less eagerly repaired] -> Acceptable; `like_received` is Class 1 display-derived, not a no-drift counter.

## Rollout Plan

1. Add tests that lock desired behavior: normal follow/post projection must not call `rebuildAllCounters`.
2. Add `post_counter_projection` storage and repository contract.
3. Change `RelationCounterProjectionProcessor` to use edge-detected INCRBY for follow/unfollow and projected-state INCRBY for posts.
4. Split Class 2 repair from `like_received` best-effort recomputation.
5. Remove normal-path `rebuildAllCounters` calls.
6. Run targeted unit/integration tests.

Rollback strategy: none at the data-compatibility level. If reverted, fresh projection table and counter snapshots must be recreated from business truth.
