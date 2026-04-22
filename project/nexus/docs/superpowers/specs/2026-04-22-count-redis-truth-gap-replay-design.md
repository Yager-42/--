# Nexus Count Redis Truth And Gap Replay Design

## Status

Approved for planning.

This design supersedes the recovery direction in
`docs/superpowers/specs/2026-04-10-count-redis-compressed-counter-design.md`
for the current Nexus counter work.

The key change is:

- keep compressed counter objects in Redis
- stop treating exported compressed snapshots as the recovery mechanism
- use Redis `RDB + AOF` as the persistence baseline
- use a short-window external gap replay log to bridge the persistence gap

## Context

Nexus already moved toward Redis-centered counter truth:

- object counters are stored as compressed fixed-slot values
- reaction writes already depend on Redis-side atomic state transitions
- relation counters and user counters are treated as online cache truth, not as MySQL query results

The remaining design problem is not whether compressed counters should exist.
It is how Redis-centered counter truth should recover after Redis failures
without reintroducing MySQL as the normal counter truth source.

The confirmed direction from brainstorming is:

- Redis is the only current truth for online counter state and online counter values
- the compressed counter object model stays
- MySQL is not used as the current truth table for likes or follows
- Redis persistence artifacts are the recovery baseline
- MySQL only keeps a short-window effective transition log for replay
- the system accepts a very small unrecoverable window when Redis succeeds,
  but neither Redis persistence nor the gap log has durably captured the change yet

## Goals

- keep Redis as the only online truth for current toggle state and current counters
- keep compact fixed-schema compressed counter objects for user, post, and comment counters
- avoid introducing a new MySQL current-state truth table for likes or follows
- make disaster recovery depend on Redis `RDB + AOF` plus a short-window replay log
- keep the write path Redis-first and operationally simple
- keep replay logic aligned with the same Redis-side state transition semantics used online

## Non-Goals

- strict zero-loss recovery for every successful online write
- building a long-term audit ledger of every reaction or relation change
- reintroducing exported logical compressed snapshots as a second recovery system
- making MySQL the current truth source for likes, follows, or counters
- redesigning unrelated Redis usage such as inboxes, locks, feed caches, or content KV storage
- supporting view/play/exposure counters in this design

## Confirmed Scope

This design covers the current confirmed counter families:

- `POST.like`
- `COMMENT.like`
- `COMMENT.reply`
- `USER.following`
- `USER.follower`
- `USER.like_received`

Future families such as favorites, views, exposures, and play counts are out of scope.

### Family classification

V1 uses two recovery classes:

- replay-backed toggle families
- rebuild-backed additive families

Replay-backed toggle families:

- `POST.like`
- `COMMENT.like`
- `USER.following`
- `USER.follower`
- `USER.like_received`

Rebuild-backed additive families:

- `COMMENT.reply`

`COMMENT.reply` remains current truth in Redis during normal online serving,
but when repair is required it is rebuilt from comment table truth rather than from the gap replay log.

## Alternatives Considered

### Option 1: Redis truth plus MySQL current-state tables for rebuild

This would add tables such as `post_like`, `comment_like`, and follow-state tables,
then rebuild Redis from those tables after a disaster.

This was rejected because it splits truth semantics:

- Redis would be the online truth
- MySQL would still become the authoritative rebuild truth

That conflicts with the confirmed direction that current counter truth should live in Redis.

### Option 2: Redis truth plus exported compressed snapshot files

This would periodically export Redis compressed counter objects to a separate snapshot system,
then replay external logs after restore.

This was rejected because Redis already provides `RDB + AOF`.
Adding a second snapshot mechanism duplicates persistence concerns and creates another operational system.

### Option 3: Redis truth plus Redis persistence baseline and short-window gap replay log

This design keeps:

- current truth in Redis
- persistence baseline in Redis `RDB + AOF`
- a short-window external effective transition log only for the gap between durable Redis checkpoints

This is the chosen design because it keeps truth ownership clean and recovery operationally bounded.

## Chosen Architecture

### Truth boundaries

Redis is the only current truth for:

- current reaction/follow state
- current object counters
- current user counters

MySQL is not the current truth for likes, follows, or counters.

MySQL only stores the replay gap log needed to reconstruct Redis changes
that may not yet be covered by recovered `RDB + AOF`.

### Storage roles

#### Redis

Redis stores:

- toggle state truth
- compressed counter truth
- recovery control metadata

#### MySQL

MySQL stores:

- short-window gap replay logs only

These logs are not complete audit history and are not current truth.

## Redis Data Model

### State truth

Toggle-like state remains separate from compressed counters.

Representative key families:

- `state:post_like:{postId}`
- `state:comment_like:{commentId}`
- `state:user_following:{sourceUserId}`
- `state:user_follower:{targetUserId}`

The exact internal encoding may vary by family, but these keys are the current online truth for state.

For follow state, v1 should keep mirrored Redis state families instead of one vague logical key.

That mirror is recommended because it gives bounded operational repair for:

- `USER.following`
- `USER.follower`

without reintroducing MySQL relation counts as the counter repair truth.

### Compressed counter truth

Compressed counter objects remain the online truth for current counts.

Representative key families:

- `count:post:{postId}`
- `count:comment:{commentId}`
- `count:user:{userId}`

The retained properties of the compressed implementation are:

- one object per Redis key
- fixed schema
- multiple fixed slots
- compact binary encoding

The design change is not to remove compressed counters.
The design change is to stop using exported compressed snapshots as the recovery system.

### Recovery control metadata

Recovery control metadata stays in Redis, not in a separate MySQL checkpoint table.

V1 control keys:

- `checkpoint:aof_durable_time`
- `checkpoint:rdb_candidate_time`
- `checkpoint:rdb_durable_time`

Optional observability keys:

- `checkpoint:last_checkpoint_time`
- `checkpoint:mode`

`checkpoint:aof_durable_time` represents the maximum event-time point
confirmed durable in AOF.

`checkpoint:rdb_candidate_time` is the candidate event-time written immediately before
a checkpoint-triggered `BGSAVE`.

`checkpoint:rdb_durable_time` is the last candidate that was confirmed by a successful `BGSAVE`.

For v1 planning, the conservative durable replay boundary is:

- `checkpoint:durable_time = min(checkpoint:aof_durable_time, checkpoint:rdb_durable_time)`

If one side is missing, recovery falls back to the other persisted value plus the fixed replay overlap window.

If a newer in-memory value was not persisted before failure, it is intentionally not treated as recoverable.

### Checkpoint advancement algorithm

The control keys above are not arbitrary timestamps.
They are advanced by explicit persistence-aware workers.

#### AOF checkpoint worker

The AOF worker runs periodically and:

1. asks Redis for server time
2. writes that value into `checkpoint:aof_durable_time`
3. waits for local AOF durability confirmation before treating it as usable for purge

The important property is:

- the timestamp must come from Redis server time, not from application-node wall clock
- the worker must only treat a value as durable after Redis persistence confirms it

#### RDB checkpoint worker

The RDB worker runs periodically and:

1. asks Redis for server time
2. writes that value into `checkpoint:rdb_candidate_time`
3. triggers or coordinates a `BGSAVE`
4. after successful save, copies the candidate value into `checkpoint:rdb_durable_time`

If Redis later restores from an older RDB image, `checkpoint:rdb_durable_time` may conservatively lag
the exact newest snapshot coverage.
That is acceptable because it only causes extra replay, not missed replay.

### Why Redis-only checkpoint metadata is acceptable

The design does not need a MySQL checkpoint table because checkpoint metadata is not business truth.

The persisted Redis dataset itself is the recovery baseline.
If a checkpoint key survives restore, that key survived as part of the same persistence image
used to restore state and counters.

The checkpoint keys therefore act as persisted recovery watermarks attached to the same recovery asset,
not as a second source of business truth.

## Write Path

### Main rule

All toggle-like counter writes use a Redis-first flow:

1. receive request
2. execute Redis-side atomic state transition
3. if transition is effective, mutate Redis counters in the same atomic unit
4. if transition is effective, asynchronously append one replay gap log record
5. return success

### Redis atomic mutation

For a `post like add`, the Redis-side atomic unit updates:

- post-like state
- `count:post:{postId}.like`
- `count:user:{authorId}.like_received`

Equivalent multi-target atomic updates apply to:

- `comment like add/remove`
- `follow/unfollow`

The write path must not split these mutations into separate application-level operations.

### No-op semantics

Repeated requests that do not change the final state are treated as no-op:

- no counter mutation
- no replay gap log append
- success response may still return current state

This is required so that replay may safely cover an overlap window without inflating counters.

## Failure Semantics

This design explicitly supports three outcomes:

### Redis mutation failed

- request fails
- no state truth changed
- no counter truth changed
- no replay log record is required

### Redis mutation succeeded and replay log append succeeded

- online truth is correct
- replay gap asset is available
- recovery can reconstruct the change if needed

### Redis mutation succeeded and replay log append did not succeed

- online truth is still correct
- recovery asset for that change may be missing
- if Redis persistence later covers the change, no problem remains
- if Redis persistence does not cover the change and the replay log is missing,
  the change may become unrecoverable

This is an accepted design trade-off.
The system does not promise strict zero-loss recovery for every successful online write.

## Replay Gap Log Model

### Role

The external log is a gap replay log only.

It is not:

- full event history
- current truth
- long-term audit ledger
- generic outbox

### What the log records

The log records only effective base state transitions:

- `POST like add/remove`
- `COMMENT like add/remove`
- `FOLLOW add/remove`

The log does not record derived counter deltas such as:

- `like_received +1/-1`
- `follower +1/-1`
- `post.like +1/-1`

Derived counters are replayed by reusing the same Redis transition semantics.

### Recommended field set

For reaction replay logs:

- `seq`
- `event_id`
- `actor_user_id`
- `target_type`
- `target_id`
- `op`
- `target_owner_user_id`
- `event_time`
- `create_time`

For relation replay logs:

- `seq`
- `event_id`
- `source_user_id`
- `target_user_id`
- `relation_type`
- `op`
- `event_time`
- `create_time`

### Event-time source

`event_time` used for replay, overlap, and purge must come from Redis-side server time.

It must not come from:

- application node wall clocks
- client request timestamps
- MySQL insertion time

The Redis-side transition logic should produce the effective event-time once,
then the asynchronous gap-log append path should persist that exact value.

This avoids skew between:

- checkpoint watermarks
- replay log filtering
- multi-node application clocks

### Table shape

Use typed gap log tables by domain instead of one generic payload table.

Recommended families:

- `reaction_gap_log`
- `relation_gap_log`

This keeps indexes, retention, and replay jobs simple.

In the current Nexus schema, this does not require adopting those exact physical table names immediately.
The implementation may evolve the existing reaction event-log table into the reaction gap log role
as long as the final semantics match this design:

- effective transitions only
- no derived counter deltas
- short-window retention
- replay-oriented indexing

For relation replay, the current `relation_event_outbox` should not be reused as the final gap-log table.

It has delivery/outbox semantics:

- status
- retry scheduling
- consumer delivery workflow

The gap replay log needs different semantics:

- effective transitions only
- overlap replay filtering by event-time or sequence
- short-window retention based on Redis checkpoint progress

V1 planning should therefore treat relation replay as requiring either:

- a new typed `relation_gap_log`
- or a schema evolution of the current relation event storage into that role

## Recovery Model

### Restore baseline

On Redis disaster recovery:

1. restore Redis from available `RDB + AOF`
2. compute the conservative replay checkpoint from restored Redis control keys
3. load gap log records at or after the replay overlap boundary
4. replay those records through the same Redis transition semantics used online

This replay flow applies to replay-backed toggle families only.

### Replay-backed family repair semantics

Replay-backed families do not all repair the same way.

#### `POST.like` and `COMMENT.like`

If the compressed counter object is missing or malformed while Redis like-state truth still exists,
the count may be recomputed from Redis state truth for that single target.

This is an in-Redis repair path, not a MySQL rebuild path.

#### `USER.following` and `USER.follower`

If mirrored Redis follow-state truth exists as designed in v1,
these counters may be recomputed from Redis follow-state truth for the affected user.

This is why mirrored follow-state families are part of the recommended Redis state model.

#### `USER.like_received`

`USER.like_received` is replay-backed for disaster recovery,
but it does not have to support cheap automatic read-path recomputation in v1.

Its normal recovery path is:

- Redis `RDB + AOF`
- plus overlap replay from the reaction gap log

If an isolated `USER.like_received` counter object is malformed outside disaster recovery,
the allowed v1 repair path is operational rather than hot-path automatic:

- recompute from Redis object-counter truth and durable ownership metadata
- or perform a bounded offline reconciliation job

V1 planning must not assume request-time automatic rebuild for `USER.like_received`.

### Rebuild-backed additive families

Not every counter family uses gap replay in v1.

`COMMENT.reply` is handled differently:

- online truth remains in Redis compressed counters
- normal writes still update Redis first
- when repair or cold rebuild is required, the value is recomputed from durable comment-table truth

For `COMMENT.reply`, the rebuild source is the durable comment table state,
for example the set of visible child comments under each root comment,
instead of the short-window gap replay log.

This is acceptable because:

- reply count is additive, not a user membership toggle
- Nexus already has durable comment business tables
- the design only needs gap replay where Redis durability gaps would otherwise lose toggle transitions

### Replay overlap

Replay must not start from the exact checkpoint boundary.

Instead, replay starts from:

- `event_time >= effective_durable_time - replay_overlap_window`

This overlap window is intentionally fixed and conservative.

Because replay uses the same no-op-aware Redis transition semantics,
overlapping records are safe:

- already-applied transitions become no-op
- missing transitions are applied

### Why time-based checkpoint is sufficient in v1

The chosen design intentionally keeps recovery control simple:

- no external checkpoint table
- no separate durable sequence registry outside Redis

Recovered Redis persistence already tells the system what checkpoint metadata survived.
Anything later than that point is not guaranteed durable and must be treated as replay territory.

For planning purposes, the recovery worker should derive:

- `effective_durable_time = min(restored_aof_durable_time, restored_rdb_durable_time)` when both exist
- otherwise use whichever restored durable key exists

The replay overlap window must be large enough to cover:

- asynchronous gap-log append delay
- checkpoint worker cadence
- Redis persistence cadence
- replay query batching jitter

## Purge Model

Gap logs are short-window recovery assets, not permanent history.

They may be purged once they are safely behind the durable Redis checkpoint plus the overlap buffer.

Recommended logical purge boundary:

- delete only records older than `effective_durable_time - purge_safety_window`

`purge_safety_window` should be a fixed configured time window.

`purge_safety_window` must be strictly larger than `replay_overlap_window`.

In planning terms:

- `replay_overlap_window` protects replay correctness
- `purge_safety_window` protects operational mistakes and persistence jitter

V1 should size both as fixed windows, not dynamic windows.

Physical deletion may still use time-based table partitioning for operational efficiency,
but correctness is defined by the logical checkpoint boundary above.

## Compressed Counter Implementation Position

The existing compressed counter implementation remains part of the design.

Its role changes:

- it remains the physical representation of Redis current counter truth
- it is no longer exported as an independent logical recovery snapshot system

The medium-term implementation direction is:

- keep fixed-schema compressed multi-slot counter objects
- move toward Redis-side atomic updates for those objects
- stop relying on application-side full-object decode/mutate/encode as the final architecture shape

## Units And Boundaries

### Redis state transition unit

Responsible for:

- checking current state
- deciding whether a transition is effective
- mutating state truth
- mutating compressed counter truth
- producing Redis-side effective event-time for the gap log

This unit owns online correctness.

### Gap log append unit

Responsible for:

- appending a replay log record after an effective Redis transition
- doing nothing for no-op requests

This unit owns only short-window replay assets.

### Replay apply entrypoint

Replay must call the same Redis transition semantics used online,
but through a replay-specific entrypoint that does not append a new gap log record again.

That replay entrypoint:

- preserves no-op behavior
- preserves the same state/count mutation rules
- suppresses recursive replay-log append
- suppresses unrelated downstream side effects

### Checkpoint worker

Responsible for:

- advancing AOF and RDB durable checkpoint keys
- using Redis-side persistence-aware confirmation rules
- exposing an effective durable replay boundary to purge and recovery workers

### Recovery worker

Responsible for:

- reading Redis checkpoint keys
- deriving `effective_durable_time`
- loading gap logs from the overlap boundary
- replaying through the same Redis transition semantics

This unit does not own alternative counter logic.

### Purge worker

Responsible for:

- computing the safe deletion boundary from Redis checkpoint metadata
- deleting only records behind the fixed safety window

## Validation Requirements

The implementation plan derived from this design must include verification for:

- duplicate `ADD` and duplicate `REMOVE` requests producing no counter inflation
- `Redis success + gap log fail` leaving online reads correct
- Redis restore followed by overlap replay converging to correct state and counts
- AOF unavailable but RDB restore plus overlap replay still converging correctly
- purge deleting only logs older than the safe boundary
- `COMMENT.reply` rebuild recomputing the same Redis value from durable comment truth after repair
- Redis-side event-time generation staying monotonic enough for overlap replay within one Redis deployment
- AOF checkpoint advancement and RDB checkpoint advancement producing conservative durable boundaries

Replay validation must call the same Redis transition semantics used by the online write path.

## Operational Expectations

Operators need visibility into:

- whether `checkpoint:aof_durable_time` is advancing
- whether `checkpoint:rdb_durable_time` is advancing
- whether gap log backlog is growing abnormally
- whether purge is stalled
- whether replay runs are succeeding
- whether no-op replay ratio changes unexpectedly

These are observability needs, not additional truth tables.

## Explicit Rejections

This design explicitly rejects:

- adding MySQL `post_like` / `comment_like` current-state tables as rebuild truth
- adding a separate MySQL checkpoint metadata table
- exporting periodic logical compressed snapshots outside Redis persistence
- using the gap replay log as full audit history
- recording derived counter deltas in the replay log

## Current Nexus Integration Impact

This design intentionally maps onto existing Nexus boundaries instead of inventing a new service layer first.

### `ReactionCachePort`

`ReactionCachePort` remains the online state-transition boundary for reaction-like writes.

Target changes:

- evolve its Redis-side apply logic from bitmap-string count mutation toward final compressed-counter mutation semantics
- generate or surface Redis-side effective event-time for successful transitions
- keep a replay-specific apply entrypoint, similar in spirit to the current `applyRecoveryEvent`, but aligned with the final time-checkpoint design
- stop treating replay checkpoints as simple legacy sequence cursors only

### `ObjectCounterPort`

`ObjectCounterPort` remains the read/write adapter for object compressed counters.

Target changes:

- keep compressed-object reads
- keep target-level in-Redis repair for replay-backed object like counters
- keep operational repair for `COMMENT.reply`
- retire legacy assumptions that object-counter repair should come from MySQL for replay-backed toggle families

### `UserCounterPort`

`UserCounterPort` remains the read/write adapter for user compressed counters.

Target changes:

- keep compressed-user-counter reads and writes
- for `USER.following` and `USER.follower`, repair from mirrored Redis follow-state truth rather than MySQL relation counts
- for `USER.like_received`, support baseline-plus-replay recovery and optional operational reconciliation, not mandatory request-time automatic rebuild

### `interaction_reaction_event_log`

The current reaction event-log table is the closest existing asset to the final reaction gap log.

Target changes:

- persist only effective transitions
- stop depending on derived `delta` as the primary replay contract
- add missing replay context such as owner user id if needed by replay-backed user counters
- shift retention from long-lived history assumptions to short-window replay retention

### `relation_event_outbox`

The current relation outbox is not the final replay gap log.

Target changes:

- keep outbox responsibilities separate from replay-gap responsibilities
- plan either a new relation gap log table or a clear schema evolution path into one replay-specific table

## Summary

The chosen design is:

- Redis current truth
- compressed counters retained
- Redis `RDB + AOF` as the recovery baseline
- Redis-stored durable checkpoint metadata advanced by persistence-aware workers
- MySQL short-window gap replay log
- overlap replay through the same Redis transition semantics
- mirrored Redis follow-state truth for bounded follow-counter repair
- no mandatory request-time rebuild guarantee for `USER.like_received`

This keeps the core promise intact:

- Redis remains the current truth for counters
- MySQL does not become the current truth source
- recovery stays possible without introducing a second snapshot system
