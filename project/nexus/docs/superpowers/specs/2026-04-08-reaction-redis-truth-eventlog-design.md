# Reaction Redis-Truth Event Log Redesign

## Context

The reaction chain previously duplicated truth across Redis, MySQL current-state tables, MySQL aggregate-count tables, and several persistence consumers. That made recovery and consistency harder than necessary.

The confirmed direction is:

- Redis is the only online truth source for reaction state and counts.
- MySQL stores only append-only reaction event log rows.
- RabbitMQ is used as the asynchronous best-effort handoff for MySQL event-log persistence.
- Request success is defined by Redis mutation success, not by MySQL append success.

## Goals

- Keep the online reaction path Redis-first.
- Remove MySQL reaction current-state and aggregate-count persistence from the write path.
- Persist effective reaction transitions as append-only MySQL event-log rows for audit and recovery.
- Support Redis rebuild from MySQL event log using monotonic checkpoints.
- Keep post/comment side effects asynchronous.

## Non-Goals

- Preserving DB-backed liker pagination.
- Keeping ES ranking dependent on reaction counts.
- Reintroducing DB fallback for reaction state or count reads.

## Chosen Architecture

### Source of truth

Redis is the only online truth source for:

- current liked state
- current reaction count

### Persistence model

MySQL stores only append-only rows in `interaction_reaction_event_log`.

Only effective transitions with `delta in (-1, 1)` are recorded.

No MySQL table stores final reaction state or final reaction count.

### Handoff model

`ReactionLikeService` applies Redis first.

If Redis apply returns `delta != 0`, the service best-effort publishes a reaction event-log message to RabbitMQ. A dedicated RabbitMQ consumer appends that message into `interaction_reaction_event_log`.

This is intentionally not a transactional outbox design for the reaction event log. The accepted trade-off is:

- Redis success means API success.
- RabbitMQ publish or MySQL append may fail independently and are repaired operationally.
- MySQL event log is for audit/recovery, not for online success confirmation.

### Recovery model

Redis recovery replays MySQL event-log rows in ascending `seq` order.

Redis stores per-stream-family checkpoints:

- `POST:LIKE`
- `COMMENT:LIKE`

Recovery resumes from `seq > checkpoint`.

## Data Model

### Event log table

`interaction_reaction_event_log`

Columns:

- `seq BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY`
- `event_id VARCHAR(128) NOT NULL`
- `target_type VARCHAR(32) NOT NULL`
- `target_id BIGINT NOT NULL`
- `reaction_type VARCHAR(16) NOT NULL`
- `user_id BIGINT NOT NULL`
- `desired_state TINYINT NOT NULL`
- `delta TINYINT NOT NULL`
- `event_time BIGINT NOT NULL`
- `create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP`

Indexes:

- unique index on `event_id`
- index on `(target_type, target_id, reaction_type, seq)`
- index on `(user_id, seq)`

Rules:

- `delta = 0` is not persisted.
- duplicate `event_id` must be idempotent.
- repository append returns `inserted` or `duplicate`.

### Removed tables from the reaction truth model

- `interaction_reaction`
- `interaction_reaction_count`
- `interaction_reaction_count_delta_inbox`

## Redis Model

Redis keys remain the online truth store:

- bitmap shard keys under `interact:reaction:bm:*`
- count keys under `interact:reaction:cnt:*`
- recovery checkpoint keys under `interact:reaction:recovery:cp:*`

Removed from the architecture:

- reaction ops snapshot keys
- delayed sync flags
- Redis Stream durable handoff for reaction event logging

## Write Path Design

1. API receives a reaction request.
2. `ReactionLikeService.applyReaction()` generates or preserves a stable request id.
3. `ReactionCachePort.applyAtomic()` atomically mutates Redis bitmap and count.
4. If `delta == 0`, return success immediately and do not publish an event-log message.
5. If `delta != 0`, best-effort publish a RabbitMQ reaction event-log message carrying:
   - `event_id`
   - `target_type`
   - `target_id`
   - `reaction_type`
   - `user_id`
   - `desired_state`
   - `delta`
   - `event_time`
6. Publish other asynchronous side effects as needed:
   - post like/unlike
   - comment like changed
   - notifications
   - recommend feedback

### Success semantics

- Redis apply success means API success.
- RabbitMQ publish failure does not roll back the API result.
- MySQL append failure in the async consumer does not retroactively change the API result.

This trade-off is intentional for higher QPS and simpler hot-path semantics.

## Read Path Design

### `reaction/state`

- Read only from Redis bitmap and Redis count.
- No DB fallback.

### `reaction/likers`

### Count reads

- Read only from Redis.
- No rebuild from MySQL aggregate tables.

## Async Event-Log Consumer

A RabbitMQ consumer appends messages from `reaction.event.log.queue` into `interaction_reaction_event_log`.

Required behavior:

- validate required fields
- append to MySQL event log
- treat repository `inserted` and `duplicate` as success
- record consumer status for idempotent processing

## Recovery Design

1. Load checkpoint for one stream family.
2. Query MySQL rows with:
   - `target_type = ?`
   - `reaction_type = ?`
   - `seq > checkpoint`
   - `ORDER BY seq ASC`
   - `LIMIT ?`
3. Replay rows using Redis state semantics, not blind count mutation.
4. Advance checkpoint only after a full page succeeds.
5. Repeat until exhausted.

### Idempotency

Recovery must be safe to rerun.

Replay uses desired-state semantics:

- `desired_state = 1` increments only if bitmap changes `0 -> 1`
- `desired_state = 0` decrements only if bitmap changes `1 -> 0`

## Testing Strategy

### Unit tests

- Redis-only reaction apply semantics
- event-log append idempotency by `event_id`
- RabbitMQ event-log consumer success/duplicate/failure behavior
- checkpoint-based recovery semantics

### Real integration tests

- `ReactionHttpRealIntegrationTest`
- `HighConcurrencyConsistencyAuditIntegrationTest`

The validated assertions are:

- Redis count correctness
- MySQL event-log correctness
- notification side effects still work
- concurrent unique likes converge correctly
## Breaking Changes

- MySQL is no longer a current-state source for reaction reads.
- reaction online success is no longer tied to MySQL persistence success.

## Implementation Status

This design has been implemented as-built with RabbitMQ handoff, not Redis Stream handoff.
