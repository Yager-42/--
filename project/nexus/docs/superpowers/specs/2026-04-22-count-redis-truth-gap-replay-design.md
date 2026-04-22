# Nexus Count Redis Truth And Gap Replay Design

## Status

Draft under review.

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

Current Nexus still keeps durable business tables such as `user_relation`, `user_follower`,
`content_post`, and `interaction_comment`.

Those tables remain important business storage in v1, but they are not the rebuild truth
for count-side toggle state and count-side current values.

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

### Follow-state mirror shape

Current Nexus has `IRelationAdjacencyCachePort`, but the default implementation is effectively a no-op
and queries still fall back to MySQL relation tables.

V1 should make the count-side follow mirror explicit instead of leaving it implicit:

- `state:user_following:{sourceUserId}`
  - member: `targetUserId`
  - score or payload: follow event-time from Redis server time
- `state:user_follower:{targetUserId}`
  - member: `sourceUserId`
  - score or payload: same follow event-time

Required semantics:

- a follow add writes both mirrored keys in the same Redis transition unit as the two user counters
- an unfollow removes both mirrored memberships in the same Redis transition unit
- a no-op follow on an existing membership does not re-increment counters
- a no-op unfollow on a missing membership does not decrement counters

The mirror exists for count and state repair first.
It does not require v1 query traffic to switch from MySQL relation pagination to Redis pagination immediately.

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

For v1 planning, the durable replay boundary is capability-dependent:

- precise AOF mode: `checkpoint:durable_time = min(checkpoint:aof_durable_time, checkpoint:rdb_durable_time)`
- conservative mode: `checkpoint:durable_time = checkpoint:rdb_durable_time` for purge correctness

If a newer in-memory value was not persisted before failure, it is intentionally not treated as recoverable.

### Checkpoint capability modes

The design must distinguish between two Redis capability levels instead of pretending all deployments
can confirm the same durability point with the same precision.

#### Mode A: precise AOF durability mode

Use this mode when the deployed Redis version and topology can explicitly confirm local AOF fsync completion,
for example Redis 7.2+ with `WAITAOF`.

In this mode:

- `checkpoint:aof_durable_time` is treated as an actual persisted durable watermark
- purge may use both AOF and RDB durable keys
- `checkpoint:durable_time` remains `min(aof_durable_time, rdb_durable_time)` when both exist

#### Mode B: conservative persistence mode

Use this mode when Redis cannot explicitly confirm local AOF fsync completion from application code.

In this mode:

- `checkpoint:aof_durable_time` may still be recorded for observability
- but it must not be treated as a strict purge guarantee
- purge and guaranteed replay boundary must rely on `checkpoint:rdb_durable_time`
- AOF restored data is still beneficial because it may reduce actual replay work,
  but correctness must not depend on application-side guesses about unconfirmed fsync state

V1 planning for Nexus should default to the conservative interpretation unless deployment validation confirms
that the count Redis runtime supports precise AOF confirmation in production.

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

For Nexus planning, this means:

- if Redis supports precise confirmation such as `WAITAOF`, the worker may:
  - call `TIME`
  - persist the returned time into `checkpoint:aof_durable_time`
  - call the durability confirmation command
  - only publish that time as purge-usable after confirmation succeeds
- if Redis does not support precise confirmation, the worker may still write the key for observability,
  but purge and guaranteed replay boundary must not rely on it

#### RDB checkpoint worker

The RDB worker runs periodically and:

1. asks Redis for server time
2. writes that value into `checkpoint:rdb_candidate_time`
3. triggers or coordinates a `BGSAVE`
4. after successful save, copies the candidate value into `checkpoint:rdb_durable_time`

If Redis later restores from an older RDB image, `checkpoint:rdb_durable_time` may conservatively lag
the exact newest snapshot coverage.
That is acceptable because it only causes extra replay, not missed replay.

For Nexus planning, the worker should use a concrete two-phase rule:

1. read Redis `TIME` into `candidate_time`
2. write `checkpoint:rdb_candidate_time = candidate_time`
3. record current `LASTSAVE` as `before_lastsave`
4. trigger `BGSAVE` only when no background save is already running
5. poll Redis persistence info until:
   - `rdb_bgsave_in_progress = 0`
   - `rdb_last_bgsave_status = ok`
   - `LASTSAVE > before_lastsave`
6. copy `checkpoint:rdb_candidate_time` into `checkpoint:rdb_durable_time`

If the save fails or times out:

- `checkpoint:rdb_durable_time` must not advance
- the stale candidate may remain in Redis as diagnostic state
- the next worker round will overwrite the candidate with a newer one

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

### Reaction write sequence

Recommended final sequence for reaction families:

1. `ReactionLikeService` builds `ReactionApplyCommand`
2. reaction transition port executes one Redis-side effective state transition
3. Redis returns `ReactionApplyResult`
4. if `effective = false`, service returns current state/count immediately
5. if `effective = true`, gap-log append path persists one reaction replay record
6. unrelated MQ side effects consume the already-computed apply result instead of recomputing transition outcome
7. service returns success

Key rule:

- `LIKE_RECEIVED` must be inside step 2, not a Java-side best-effort follow-up write

### Relation write sequence

Recommended final sequence for relation families:

1. `RelationService` completes the MySQL business transaction for relation truth and delivery outbox as it does today
2. after commit, service builds `RelationApplyCommand`
3. relation transition port executes one Redis-side follow-state mirror plus counter transition
4. if `effective = true`, append one `relation_gap_log` record
5. MQ delivery remains owned by `relation_event_outbox`

This is intentionally not identical to reaction:

- reaction already starts from Redis truth
- relation still starts from MySQL business-edge truth in the current Nexus domain model

The convergence target is count recovery consistency, not forcing relation business storage to migrate into Redis.

### Redis atomic mutation

For a `post like add`, the Redis-side atomic unit updates:

- post-like state
- `count:post:{postId}.like`
- `count:user:{authorId}.like_received`

Equivalent multi-target atomic updates apply to:

- `comment like add/remove`
- `follow/unfollow`

The write path must not split these mutations into separate application-level operations.

For current Nexus integration, this is a target-state requirement because the current code still splits
some of these effects across `ReactionCachePort`, `ObjectCounterPort`, and `UserCounterPort`
at the Java layer.

The final transition unit should return a structured apply result containing at least:

- `effective`
- `delta`
- `current_primary_count`
- `event_time`

where:

- `effective = false` means the request was a no-op against Redis truth
- `delta` is derived inside Redis from the actual state transition
- `event_time` is the Redis server time attached to the effective mutation

This apply result becomes the only input required by the asynchronous gap-log append path.

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

For Nexus specifically, the practical recommendation is:

- evolve `interaction_reaction_event_log` instead of creating a second reaction log table immediately
- create a dedicated relation gap log table instead of overloading `relation_event_outbox`

This asymmetry is intentional:

- reaction already has a close-enough log asset and recovery runner
- relation currently only has delivery-oriented outbox state, not replay-oriented gap-log state

### Reaction gap-log transport choice

Nexus currently uses:

- Redis transition success
- then RabbitMQ handoff
- then `ReactionEventLogConsumer`
- then MySQL append into `interaction_reaction_event_log`

There are only two coherent target choices:

#### Choice A: keep MQ handoff

Sequence:

- online service publishes a reaction gap-log message
- consumer appends into MySQL gap log

Advantages:

- reuse of existing queue, DLQ, and consumer-record machinery
- natural buffering when MySQL append path is temporarily slow

Costs:

- one more moving part in the recovery asset path
- recovery gap now depends on Redis durability plus MQ durability plus consumer drain
- replay asset visibility is delayed by queue lag

#### Choice B: direct best-effort append

Sequence:

- online service or dedicated append port writes directly into MySQL after the effective Redis transition

Advantages:

- gap-log semantics become simpler
- fewer components between Redis transition and durable replay asset
- easier checkpoint, backlog, and replay reasoning

Costs:

- online path now touches MySQL directly for every effective reaction transition
- no MQ buffering layer

Recommended direction for this design:

- reaction gap log should converge to direct best-effort append

Reason:

- the gap log is a recovery control-plane asset, not a fanout event stream
- direct append makes the accepted failure model much easier to reason about
- MQ is still appropriate for unrelated downstream business side effects, but is not ideal as the long-term durability bridge for the replay asset itself

Migration note:

- MQ handoff may remain in Stage 1 for compatibility
- but the target design should not treat it as the final architecture

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

The stronger recommendation for Nexus is a new `relation_gap_log`.

Reasons:

- `relation_event_outbox` is part of MQ delivery semantics and already carries retry and status workflow
- replay-gap retention is short-window and checkpoint-driven, which conflicts with outbox troubleshooting retention
- count replay only needs effective follow-state transitions and should not inherit unrelated outbox fields

Recommended v1 relation gap fields:

- `seq`
- `event_id`
- `source_user_id`
- `target_user_id`
- `op`
- `event_time`
- `create_time`

Recommended indexes:

- unique `event_id`
- range index on `event_time`
- optional composite `(source_user_id, event_time)`
- optional composite `(target_user_id, event_time)`

## Recovery Model

### Restore baseline

On Redis disaster recovery:

1. restore Redis from available `RDB + AOF`
2. compute the conservative replay checkpoint from restored Redis control keys
3. load gap log records at or after the replay overlap boundary
4. replay those records through the same Redis transition semantics used online

This replay flow applies to replay-backed toggle families only.

### Replay entrypoint contract

Replay must not call ad hoc rebuild logic.
It must call a dedicated replay entrypoint that shares the same Redis transition semantics as the online path.

Required replay entrypoint properties:

- takes the same logical transition fields as online writes
- does not emit a second gap-log record
- does not emit MQ side effects such as notify, recommend, or feed fanout events
- preserves no-op semantics on already-restored state
- returns whether the replay record was effective or skipped as no-op

For reaction replay, the minimum logical input is:

- `actor_user_id`
- `target_type`
- `target_id`
- `desired_state`
- `target_owner_user_id`
- `event_time`

For relation replay, the minimum logical input is:

- `source_user_id`
- `target_user_id`
- `op`
- `event_time`

Replay code may still use `seq` for stable pagination,
but `seq` is not the business truth checkpoint any more.

### Replay ordering rule

Replay should page records in ascending `(event_time, seq)` order, not just raw insertion order.

Reason:

- `event_time` defines the logical overlap and purge boundary
- `seq` breaks ties inside the same millisecond or same Redis time tuple

This allows:

- deterministic replay
- stable pagination under overlap windows
- future partitioning by event-time without changing replay semantics

### Target port and value-object contracts

The final design should stop letting recovery shape leak through legacy reaction-only APIs.

Recommended reaction-side apply contracts:

- `ReactionApplyCommand`
  - `actor_user_id`
  - `target_type`
  - `target_id`
  - `desired_state`
  - `target_owner_user_id`
  - `request_id`
  - `mode` where `mode in {ONLINE, REPLAY}`
- `ReactionApplyResult`
  - `effective`
  - `desired_state`
  - `delta`
  - `current_target_like_count`
  - `current_owner_like_received_count`
  - `event_time`

Recommended relation-side apply contracts:

- `RelationApplyCommand`
  - `source_user_id`
  - `target_user_id`
  - `op` where `op in {FOLLOW, UNFOLLOW}`
  - `request_id`
  - `mode` where `mode in {ONLINE, REPLAY}`
- `RelationApplyResult`
  - `effective`
  - `op`
  - `delta_following`
  - `delta_follower`
  - `current_source_following_count`
  - `current_target_follower_count`
  - `event_time`

Mode semantics:

- `ONLINE` allows downstream best-effort side effects after a successful effective transition
- `REPLAY` suppresses gap-log append and unrelated MQ side effects

Port ownership recommendation:

- `ReactionCachePort` should become the reaction transition boundary, not the reaction checkpoint boundary
- relation should gain an equivalent dedicated transition boundary instead of composing Redis count writes ad hoc in `RelationService`
- checkpoint read/write should move into a separate persistence control port used by checkpoint, recovery, and purge workers

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

- precise AOF mode: `effective_durable_time = min(restored_aof_durable_time, restored_rdb_durable_time)` when both exist
- conservative mode: `effective_durable_time = restored_rdb_durable_time`

Under conservative persistence mode, Nexus should additionally apply this stricter operational rule:

- `replay_start_time = restored_rdb_durable_time - replay_overlap_window` when only RDB durability is trusted for purge correctness

That means:

- restored AOF data may already include newer state
- replay still starts from the older trusted RDB durable point
- duplicate effects are absorbed by Redis no-op semantics

This intentionally biases toward extra replay instead of missed replay.

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

In conservative persistence mode, `effective_durable_time` for purge must mean
the trusted RDB durable checkpoint, not an inferred unconfirmed AOF point.

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

## Worker Topology And Ordering

The final v1 runtime should separate three control-plane jobs instead of letting one recovery runner own all concerns.

### 1. Checkpoint worker

Responsibilities:

- advance `checkpoint:aof_durable_time` when precise AOF durability confirmation is available
- advance `checkpoint:rdb_candidate_time`
- promote `checkpoint:rdb_durable_time` after successful `BGSAVE`
- expose the current effective durable boundary for operators

Cadence recommendation:

- shorter than purge cadence
- long enough to avoid pathological `BGSAVE` churn
- fixed cadence, not request-driven

Failure handling:

- checkpoint worker failure must not block online writes
- it only widens replay window and delays purge

### 2. Recovery worker

Responsibilities:

- run at process boot after Redis restore or on explicit operator request
- read restored checkpoint keys
- derive replay start boundary from capability mode plus overlap window
- stream reaction and relation gap logs in ascending `(event_time, seq)`
- call replay apply entrypoints until caught up

Start-order rule:

1. Redis baseline restore finishes
2. application starts count transition ports
3. recovery worker completes replay catch-up
4. then checkpoint and purge workers may resume normal cadence

Reason:

- purge must not race ahead of the first recovery pass
- replay should run against a stable restored baseline

### 3. Purge worker

Responsibilities:

- compute safe deletion boundary from restored durable checkpoint plus safety window
- delete only old reaction and relation gap log records
- expose backlog and deleted-row metrics

Safety rule:

- purge must not start until at least one successful recovery pass has completed after boot
- purge must use the trusted durable boundary for the active capability mode

### Optional 4. Operational repair worker

Current Nexus already has `UserCounterRepairJob`, but it still rebuilds follow counters from MySQL.

In the target design, an operational repair worker may remain for bounded repair scenarios:

- malformed user compressed object
- malformed object compressed counter
- partial Redis-side corruption outside full disaster recovery

But it should evolve toward:

- Redis mirror state based repair for `FOLLOWING` and `FOLLOWER`
- Redis state plus ownership metadata based reconciliation for `LIKE_RECEIVED`
- no MySQL current-truth dependency for replay-backed families

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

## Open Engineering Decisions

The following points are now narrowed down enough to be implementation-planning inputs,
but still need explicit confirmation before coding:

### 1. count Redis runtime capability

Need confirmation whether the final count Redis deployment is:

- Redis 7.2+ with `WAITAOF` available
- or a lower / different runtime where only conservative RDB-based purge guarantees should be used

This choice changes purge aggressiveness, not the basic recovery architecture.

### 2. reaction owner context source

`ReactionRedisRecoveryRunner` currently resolves owner user id at replay time by querying
`content_post` or `interaction_comment`.

That is functional but not ideal for replay isolation.

Preferred direction:

- persist `target_owner_user_id` in the reaction gap log

Fallback direction:

- keep replay-time owner lookup during migration

### 2a. reaction gap-log transport

The design recommendation is now explicit:

- migration stage may temporarily keep MQ handoff
- target state should converge to direct best-effort MySQL append after effective Redis transition

This should be treated as a design choice already recommended, not an open-ended undecided placeholder,
unless there is a strong operational reason to keep MQ in the durability path.

### 3. relation query traffic cutover

The design only requires Redis follow mirrors for count truth and repair.
It does not require relation list APIs to use Redis in v1.

Need confirmation whether v1 should:

- keep user-facing relation pagination on MySQL only
- or opportunistically expose Redis mirror reads for selected hot paths

### 4. compressed mutation vehicle

The compressed online truth remains.
The remaining implementation choice is whether the final atomic mutation is done through:

- Redis module commands
- Lua / function-based raw key mutation
- or an intermediate hybrid during migration

This choice changes implementation shape, not truth boundaries.

## Explicit Rejections

This design explicitly rejects:

- adding MySQL `post_like` / `comment_like` current-state tables as rebuild truth
- adding a separate MySQL checkpoint metadata table
- exporting periodic logical compressed snapshots outside Redis persistence
- using the gap replay log as full audit history
- recording derived counter deltas in the replay log

## Current Nexus Integration Impact

This design intentionally maps onto existing Nexus boundaries instead of inventing a new service layer first.

### Current write-path asymmetry

Current Nexus does not have one uniform count write chain yet.

Reaction today:

- `ReactionLikeService` calls `ReactionCachePort.applyAtomic(...)`
- successful effective transitions then publish a reaction event-log MQ message
- a separate consumer appends that message into `interaction_reaction_event_log`
- `LIKE_RECEIVED` is still incremented in Java best-effort after the reaction apply result

Relation today:

- `RelationService` still treats MySQL relation writes plus `relation_event_outbox` as the primary business transaction
- Redis follow-adjacency cache port is effectively a no-op
- user follow counters are incremented or repaired in after-commit callbacks
- `user_counter_repair_outbox` still rebuilds follow counts from MySQL counts

This means the migration cannot be one giant shared refactor.
Reaction and relation need different transition stages before they converge on the same Redis-truth recovery model.

### `ReactionCachePort`

`ReactionCachePort` remains the online state-transition boundary for reaction-like writes.

Target changes:

- evolve its Redis-side apply logic from bitmap-string count mutation toward final compressed-counter mutation semantics
- generate or surface Redis-side effective event-time for successful transitions
- keep a replay-specific apply entrypoint, similar in spirit to the current `applyRecoveryEvent`, but aligned with the final time-checkpoint design
- stop treating replay checkpoints as simple legacy sequence cursors only

More concretely, the current interface shape should evolve away from:

- `applyAtomic(...) -> currentCount + delta`
- `applyRecoveryEvent(...) -> boolean`
- `getRecoveryCheckpoint(...) / setRecoveryCheckpoint(...)`

toward:

- one online apply result that also includes `event_time` and `effective`
- one replay apply entrypoint that reuses the same Redis transition script or command family
- separate checkpoint operations owned by a persistence control component instead of by the reaction cache port itself

The current legacy checkpoint methods are reaction-specific and seq-specific.
They should not remain the long-term control surface once time-based durable checkpoints become global recovery metadata.

For migration ordering, reaction should first converge on:

- effective transition result as the only source for whether a gap log should be appended
- owner context attached before append
- replay ordered by `(event_time, seq)` instead of legacy `seq` checkpoint only

Only after that should reaction move its full compressed-counter mutation into the final Redis-side unit.

### `ObjectCounterPort`

`ObjectCounterPort` remains the read/write adapter for object compressed counters.

Target changes:

- keep compressed-object reads
- keep target-level in-Redis repair for replay-backed object like counters
- keep operational repair for `COMMENT.reply`
- retire legacy assumptions that object-counter repair should come from MySQL for replay-backed toggle families

The most important migration change is that object-like counts must stop depending on
bitmap-scan rebuild as the normal recovery model.

Bitmap scan may remain a bounded operational repair path during transition,
but final disaster recovery should prefer:

- Redis restored compressed counter truth
- plus replay of effective toggle transitions

### `UserCounterPort`

`UserCounterPort` remains the read/write adapter for user compressed counters.

Target changes:

- keep compressed-user-counter reads and writes
- for `USER.following` and `USER.follower`, repair from mirrored Redis follow-state truth rather than MySQL relation counts
- for `USER.like_received`, support baseline-plus-replay recovery and optional operational reconciliation, not mandatory request-time automatic rebuild

This is a direct change from the current implementation,
which still injects `IRelationRepository` and rebuilds `FOLLOWING` and `FOLLOWER` from MySQL counts.

That MySQL rebuild path should become transition-only fallback and then be removed from the count truth model.

Current `LIKE_RECEIVED` writes also remain Java-side best-effort increments after reaction apply.

In the final design, `LIKE_RECEIVED` should move into the same reaction transition unit that determines effective like-state change,
so that:

- online current truth stays inside one Redis mutation boundary
- replay does not have to re-derive user-counter deltas outside the apply result

### `interaction_reaction_event_log`

The current reaction event-log table is the closest existing asset to the final reaction gap log.

Target changes:

- persist only effective transitions
- stop depending on derived `delta` as the primary replay contract
- add missing replay context such as owner user id if needed by replay-backed user counters
- shift retention from long-lived history assumptions to short-window replay retention

Recommended schema evolution for Nexus:

- keep `seq`
- keep `event_id`
- keep `target_type`, `target_id`, `reaction_type`, `user_id`, `desired_state`, `event_time`, `create_time`
- add `target_owner_user_id`
- keep `delta` as an optional diagnostic field during migration, but not as replay truth
- add replay query support by `event_time` and tie-break by `seq`

This lets the current table support both:

- compatibility with existing code during migration
- final replay contract based on state transition, owner context, and event-time

Recommended target SQL shape:

```sql
CREATE TABLE interaction_reaction_event_log (
  seq BIGINT NOT NULL AUTO_INCREMENT,
  event_id VARCHAR(128) NOT NULL,
  actor_user_id BIGINT NOT NULL,
  target_type VARCHAR(32) NOT NULL,
  target_id BIGINT NOT NULL,
  reaction_type VARCHAR(16) NOT NULL,
  desired_state TINYINT NOT NULL,
  target_owner_user_id BIGINT NOT NULL,
  event_time BIGINT NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  delta TINYINT NULL,
  PRIMARY KEY (seq),
  UNIQUE KEY uk_event_id (event_id),
  KEY idx_replay_time_seq (event_time, seq),
  KEY idx_target_time_seq (target_type, target_id, reaction_type, event_time, seq),
  KEY idx_owner_time_seq (target_owner_user_id, event_time, seq)
);
```

Notes:

- `actor_user_id` is the semantic replacement for the current `user_id` naming
- `delta` may remain nullable during migration for diagnostics and temporary compatibility
- replay query path should converge on `ORDER BY event_time ASC, seq ASC`

### `relation_event_outbox`

The current relation outbox is not the final replay gap log.

Target changes:

- keep outbox responsibilities separate from replay-gap responsibilities
- plan either a new relation gap log table or a clear schema evolution path into one replay-specific table

The recommended concrete path is:

- do not mutate `relation_event_outbox` into a dual-purpose table
- add `relation_gap_log`
- append to it best-effort after Redis effective follow/unfollow transition
- keep `relation_event_outbox` focused on downstream event delivery

This keeps count recovery and MQ delivery independently operable.

Recommended target SQL shape:

```sql
CREATE TABLE relation_gap_log (
  seq BIGINT NOT NULL AUTO_INCREMENT,
  event_id VARCHAR(128) NOT NULL,
  source_user_id BIGINT NOT NULL,
  target_user_id BIGINT NOT NULL,
  op VARCHAR(16) NOT NULL,
  event_time BIGINT NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (seq),
  UNIQUE KEY uk_event_id (event_id),
  KEY idx_replay_time_seq (event_time, seq),
  KEY idx_source_time_seq (source_user_id, event_time, seq),
  KEY idx_target_time_seq (target_user_id, event_time, seq)
);
```

Notes:

- v1 only needs `FOLLOW` and `UNFOLLOW`
- block/unblock side effects that remove follow edges should either:
  - append equivalent effective unfollow replay records
  - or route through the same relation transition unit so replay semantics stay uniform
- relation MQ delivery stays in `relation_event_outbox` and does not reuse this table

### Block and unblock replay semantics

`BLOCK` is not a first-class count replay operation in this design.

Reason:

- count recovery only cares about the existence or absence of follow edges and the derived counters
- replaying `BLOCK` directly would force the count replay layer to understand more business meaning than it needs

Recommended rule:

- if a block operation removes one or two active follow edges, the relation transition layer must emit equivalent effective unfollow replay records for the removed edges
- if a block operation removes no active follow edge, it emits no relation gap-log record
- unblock emits no relation gap-log record by itself because it does not restore follow edges

This keeps the relation replay contract minimal:

- replay only understands `FOLLOW` and `UNFOLLOW`
- block remains a business-domain operation handled by MySQL business truth and downstream delivery logic

For current Nexus alignment, this means `RelationService.block(...)` should not write a raw `BLOCK` count replay record.
It should detect removed active follow edges and translate them into zero, one, or two effective unfollow replay transitions.

### `user_counter_repair_outbox`

`user_counter_repair_outbox` is a transition-era compensating asset, not part of the target replay baseline.

Current behavior:

- it captures failures when relation after-commit user counter writes do not succeed
- `UserCounterRepairJob` then repairs follow counts from MySQL relation counts

Target direction:

- keep it only as bounded operational compensation during migration
- stop using it as the normal recovery mechanism for `FOLLOWING` and `FOLLOWER`
- once Redis follow mirrors and relation replay are in place, limit it to exceptional repair workflows only

This table should not expand into a second long-lived source of count truth.

## Migration Stages

This design is not a flag-day rewrite.
Nexus needs an ordered migration so that recovery semantics improve before physical storage fully converges.

### Stage 1: make replay logs semantically correct

- keep current physical Redis encodings
- make reaction log append effective-transition-only
- add owner context to reaction replay records
- introduce `relation_gap_log`
- stop treating `delta` as the source of truth for replay decisions
- keep existing MQ/outbox responsibilities unchanged
- translate `BLOCK` follow-edge removals into equivalent `UNFOLLOW` replay records instead of introducing `BLOCK` replay semantics

Exit criteria:

- reaction replay records are sufficient to restore `POST.like`, `COMMENT.like`, and `USER.like_received`
- relation replay records are sufficient to restore `USER.following` and `USER.follower`

### Stage 2: introduce Redis persistence control plane

- add checkpoint workers
- store checkpoint keys in Redis
- switch recovery jobs from seq cursor to time-based overlap replay
- keep seq only as a page tie-breaker, not as durable checkpoint truth
- gate purge behind successful recovery completion after boot
- for reaction, begin removing MQ from the long-term replay-asset durability path

Exit criteria:

- recovery can derive `effective_durable_time` from restored Redis keys
- purge can delete old gap logs without any MySQL checkpoint table

### Stage 3: introduce mirrored Redis follow-state truth

- make `IRelationAdjacencyCachePort` actually maintain count-side follow mirrors in Redis
- change `UserCounterPort` rebuild for `FOLLOWING` and `FOLLOWER` to read Redis mirror state
- keep MySQL relation pagination unchanged for v1 user-facing query APIs
- stop using `UserCounterRepairJob` as a MySQL-count rehydration mechanism for normal count recovery

Exit criteria:

- follow counter repair no longer requires MySQL count queries
- replay and bounded repair both depend on Redis mirror state plus gap log

### Stage 4: collapse online counter mutation into one Redis transition unit

- move reaction and relation effective state mutation plus compressed counter mutation into one Redis-side unit per family
- make online apply result return `effective`, `delta`, and `event_time`
- keep asynchronous log append as a separate best-effort step after the Redis transition succeeds
- move side-effect publication to consume apply result instead of independently re-deriving transition outcome
- make reaction gap-log append direct best-effort append if Stage 2 still used MQ compatibility

Exit criteria:

- application service code no longer composes count truth mutation from multiple Java-side writes
- replay can call the same transition unit as online writes

### Stage 5: retire transition-only legacy rebuild paths

- remove reaction-specific seq checkpoint APIs
- downgrade bitmap scan rebuild to operational tool only
- remove MySQL follow-count rebuild from `UserCounterPort`
- remove replay-time dependence on content/comment owner lookup when reaction gap log already carries owner context

Exit criteria:

- count truth recovery depends only on Redis baseline, Redis control keys, and short-window gap replay logs

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
