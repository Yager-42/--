# Nexus Count Redis Compressed Counter Design

## Context

Nexus currently stores unified counters in ordinary Redis string keys and reuses the reaction Redis model for post/comment like counts.

That leaves three problems unresolved:

- repeated field names and per-key structural overhead waste memory
- counter storage is not specialized for a small fixed schema
- reaction state and reaction count are tied to a Redis model optimized for native bitmap/string commands, not for compressed counter storage

The confirmed direction is:

- add a dedicated `Count Redis` deployment
- do not modify Redis core
- implement a Redis module for compressed counter storage
- move the following online capabilities into `Count Redis`:
  - `POST.like`
  - `COMMENT.like`
  - `COMMENT.reply`
  - `USER.following`
  - `USER.follower`
- move reaction online state for `POST.like` and `COMMENT.like` into `Count Redis` as well
- keep all other non-counter Redis usage unchanged

## Goals

- make `Count Redis` the only online truth source for the five confirmed counter families
- remove repeated field-name storage from per-object counter values
- use fixed-width compact integer encoding for predictable memory usage
- keep application integration realistic for the current Nexus codebase
- preserve single-Redis atomicity for reaction like state and like count
- keep recovery and audit paths operationally repairable

## Non-Goals

- modifying Redis core internals
- preserving compatibility with native `HINCRBY`, `HGETALL`, or Redis bitmap commands
- migrating historical fake data from the current environment
- changing unrelated Redis subsystems such as relation adjacency cache, locks, inboxes, or KV content storage
- introducing dynamic schema management in v1

## Alternatives Considered

### Option 1: Dedicated Count Redis with compressed counters and reaction state in the same module

This design uses a dedicated Redis deployment plus a Redis module that manages:

- `CountInt` objects for compressed counters
- `RoaringBitmap`-backed state objects for post/comment likes
- atomic module commands that mutate both state and count inside the same Redis command

This is the recommended design because it keeps ownership clear, preserves atomicity, and delivers the intended memory reduction.

### Option 2: Dedicated Count Redis for counters only, keep reaction state in the current Redis

This reduces infrastructure churn but splits like state and like count across two Redis deployments.

That creates a cross-Redis consistency problem and weakens the design goal of keeping reaction online truth in one place.

This option was rejected.

### Option 3: Dedicated Count Redis without a Redis module

This would still use Redis native string/hash/bitmap structures.

It can be implemented faster, but it does not remove the structural overhead that motivated the redesign.

This option was rejected.

## Chosen Architecture

### Source of truth

`Count Redis` is the only online truth source for:

- `POST.like` state
- `POST.like` count
- `COMMENT.like` state
- `COMMENT.like` count
- `COMMENT.reply` count
- `USER.following` count
- `USER.follower` count

The current reaction bitmap/count keys in the existing business Redis are retired from the online truth model.

All other Redis usage remains in the existing business Redis deployment.

### Storage model

The Redis module defines two internal object families:

- `CountInt`
  - fixed-schema compressed multi-field counters
  - fixed-width unsigned `INT5` slots
- `RoaringState`
  - roaring-bitmap-backed user membership set for reaction state

The module exposes custom commands only. Native Redis hash or bitmap compatibility is not required.

### Why two object families

The two storage problems are different:

- counter values are small fixed-schema integer vectors
- like state is a user-id membership set

`CountInt` is optimal for the first case.

`RoaringBitmap` is more appropriate than fixed bitmap shards for the second case because it compresses sparse and mixed-density user-id sets better than a large pre-allocated bit range.

## Schema Model

Schema is code-defined in both:

- the Redis module
- the Nexus application adapter layer

V1 uses a static schema registry. There is no Redis-, MySQL-, or config-center-driven schema source in v1.

### Schema families

#### `post_counter`

- slot `0` -> `like`
- slot width `5 bytes`
- total payload length `5 bytes`

#### `comment_counter`

- slot `0` -> `like`
- slot `1` -> `reply`
- slot width `5 bytes`
- total payload length `10 bytes`

#### `user_counter`

- slot `0` -> `following`
- slot `1` -> `follower`
- slot width `5 bytes`
- total payload length `10 bytes`

### Encoding

All counter slots use unsigned `INT5` encoding.

Rules:

- range is `0` to `2^40 - 1`
- application may pass negative `delta`
- the final stored result must never be negative
- `COUNT.INCRBY` clamps underflow to `0`
- `COUNT.SET` rejects values outside the allowed range
- `REACTION.APPLY` rejects any overflow and rejects any detected state/count invariant corruption instead of silently repairing it

V1 keeps slot width uniform across all schemas to avoid mixed-width complexity.

## Key Model

### Counter keys

- `count:post:{postId}`
- `count:comment:{commentId}`
- `count:user:{userId}`

### Reaction state keys

- `state:post_like:{postId}`
- `state:comment_like:{commentId}`

### Recovery checkpoint keys

- `count:recovery:cp:POST:LIKE`
- `count:recovery:cp:COMMENT:LIKE`

V1 stores recovery checkpoints only for reaction families that replay from the append-only reaction event log.

`COMMENT.reply`, `USER.following`, and `USER.follower` do not use incremental log checkpoints in v1. They are rebuilt by full recomputation from MySQL truth tables when repair is needed.

## Module Command Design

### Counter commands

- `COUNT.INCRBY key field delta`
- `COUNT.GET key field`
- `COUNT.GETALL key`
- `COUNT.MGETALL key1 key2 ...`
- `COUNT.SET key field value`
- `COUNT.DEL key`

Required command semantics:

- infer schema from key family
- validate `field` against the schema
- create zero-initialized object on first write for `COUNT.INCRBY` and `COUNT.SET`
- keep all slot values non-negative
- return normalized integer values only
- `COUNT.GET` on a missing key returns `0`
- `COUNT.GETALL` returns an alternating field/value array in schema order, for example `["like", 12, "reply", 3]`
- `COUNT.GETALL` on a missing key returns the same alternating field/value array with zero values for the full schema
- `COUNT.MGETALL` returns a request-order array of per-key records, where each record is `[key, field1, value1, field2, value2, ...]`
- `COUNT.MGETALL` on missing keys returns the same per-key record shape with zero-filled schema values
- `COUNT.INCRBY` returns the updated slot value after mutation
- `COUNT.INCRBY` clamps any negative result to `0` and increments an underflow-clamp metric
- `COUNT.INCRBY` returns `ERR COUNT_OVERFLOW` and performs no mutation if the result would exceed `2^40 - 1`
- `COUNT.SET` is operational-only and used by bootstrap, rebuild, and admin tooling
- `COUNT.SET` returns `ERR COUNT_OVERFLOW` or `ERR COUNT_NEGATIVE_VALUE` and performs no mutation for invalid values
- `COUNT.DEL` is operational-only and deletes the whole counter object; no application hot path may depend on it

### Reaction commands

- `REACTION.APPLY stateKey countKey userId desiredState field`
- `REACTION.STATE stateKey userId`

`REACTION.APPLY` is the critical write-path command.

Its internal logic is:

1. load `RoaringState`
2. check whether `userId` already exists
3. compare current state with `desiredState`
4. if unchanged, return `delta=0`
5. if changed:
   - mutate `RoaringState`
   - mutate `CountInt[field]` by `+1` or `-1`
6. return a two-element array:
   - index `0` -> `currentCount`
   - index `1` -> `delta`

Additional command contract:

- missing `stateKey` and `countKey` are created on first write
- `desiredState` must be `0` or `1`, otherwise return `ERR INVALID_DESIRED_STATE`
- if increment would overflow, return `ERR COUNT_OVERFLOW` and perform no mutation
- if decrement would require a negative count while the state indicates a present user, return `ERR REACTION_CORRUPT_STATE` and perform no mutation

`REACTION.STATE` returns `false` when the state key does not exist.

This command is the reason reaction like state and count remain atomically consistent in the new design.

## Nexus Integration Design

### Existing domain boundaries to preserve

The redesign should preserve the current domain-service call shape wherever possible.

The main change happens inside infrastructure adapter implementations.

Relevant current boundaries include:

- `IReactionCachePort`
- `IObjectCounterPort`
- `IUserCounterPort`

### Reaction path

`IReactionCachePort` remains the reaction online truth adapter.

Its implementation is rewritten to call `Count Redis` module commands instead of the current business Redis bitmap/string model.

Mapping:

- `applyAtomic(...)` -> `REACTION.APPLY`
- `getState(...)` -> `REACTION.STATE`
- `getCount(...)` -> `COUNT.GET`
- `batchGetCount(...)` -> grouped `COUNT.GET` or `COUNT.MGETALL`
- `getCountFromRedis(...)` -> same `Count Redis` direct read path

Return-shape contract:

- `REACTION.APPLY` module result is mapped to `ReactionApplyResultVO`
- index `0` maps to `currentCount`
- index `1` maps to `delta`
- `firstPending` remains hard-coded to `false` in v1 and is not provided by the module
- `COUNT.GETALL` and `COUNT.MGETALL` keep their module-native alternating-array wire format inside the infrastructure adapter only
- current Java port signatures do not change in v1
- `IReactionCachePort.batchGetCount(...)` continues to return `Map<String, Long>`
- `IObjectCounterPort.batchGetCount(...)` continues to return `Map<String, Long>`
- for those batch methods, the adapter unwraps only the requested field from each module result and returns `targetKey -> requestedFieldValue`
- multi-field maps are an adapter-internal parsing detail, not an exposed domain-port contract in v1

The current reaction bitmap/count keys under `interact:reaction:*` are removed from the online path.

#### Interface delta for `IReactionCachePort`

V1 planning assumes an explicit interface cleanup instead of keeping bitmap-era methods with undefined behavior.

- keep:
  - `applyAtomic`
  - `getCount`
  - `batchGetCount`
  - `getCountFromRedis`
  - `getState`
  - `applyRecoveryEvent`
  - `getRecoveryCheckpoint`
  - `setRecoveryCheckpoint`
  - `getWindowMs`
- remove:
  - `bitmapShardExists`
  - `setState`
  - `setCount`

Removed methods are bitmap-era operational helpers and should not be redefined on top of the roaring-state model in v1.

`applyRecoveryEvent`, `getRecoveryCheckpoint`, and `setRecoveryCheckpoint` remain recovery-only methods.

`getWindowMs` remains temporarily for interface compatibility only.

V1 rule:

- the adapter does not read this value from `Count Redis`
- the adapter returns the caller-provided `defaultMs` directly
- the method is treated as deprecated and excluded from the Count Redis truth model

### Object counter path

`ObjectCounterPort` becomes a pure `Count Redis` adapter.

Mapping:

- `POST.like` -> `count:post:{postId}` / `like`
- `COMMENT.like` -> `count:comment:{commentId}` / `like`
- `COMMENT.reply` -> `count:comment:{commentId}` / `reply`

### User counter path

`UserCounterPort` becomes a pure `Count Redis` adapter.

Mapping:

- `USER.following` -> `count:user:{userId}` / `following`
- `USER.follower` -> `count:user:{userId}` / `follower`

#### Interface delta for counter ports

`IObjectCounterPort` and `IUserCounterPort` keep their current method shapes.

Semantic changes:

- `increment(...)` uses `COUNT.INCRBY`
- `setCount(...)` is operational-only and used by rebuild/bootstrap code paths
- `evict(...)` is operational-only and used by rebuild/bootstrap/tests, not by hot-path application logic

### Read-side services

These services keep their current port-level dependencies and do not need architecture-level redesign:

- content detail query
- feed card stat assembly
- search index upsert/backfill
- user profile query
- comment hot-rank dependent consumers

They change only because the underlying port implementations change.

### Write-side services

The write-side behavior remains conceptually stable:

- follow/unfollow/block still update user counters after authoritative relation writes
- root reply count changed still updates `COMMENT.reply`
- reaction like apply still uses `IReactionCachePort.applyAtomic(...)`

The difference is that the authoritative counter/state mutation now lands in `Count Redis`.

## Write Path Design

### Post/comment like

1. API receives reaction request.
2. `ReactionLikeService` validates the request and target.
3. `IReactionCachePort.applyAtomic(...)` calls `REACTION.APPLY`.
4. `Count Redis` atomically updates:
   - reaction state set
   - corresponding like count slot
5. If `delta == 0`, return success immediately.
6. If `delta != 0`, publish asynchronous side effects:
   - reaction event log message
   - post/comment domain side-effect message
   - notification or recommendation messages when required

### Comment reply count

1. upstream comment event produces reply delta
2. `RootReplyCountChangedConsumer` calls `objectCounterPort.increment(comment.reply, delta)`
3. adapter issues `COUNT.INCRBY count:comment:{id} reply delta`
4. only after the `Count Redis` mutation succeeds does the consumer update DB derived columns and hot-rank side effects

If the `Count Redis` mutation fails:

- the consumer throws
- the DB transaction rolls back
- RabbitMQ retry or dead-letter handling becomes the repair path

This keeps `COMMENT.reply` mutation fail-fast instead of best-effort.

### User follow/follower count

1. authoritative relation write succeeds in MySQL
2. after commit, relation service updates:
   - `count:user:{sourceId}.following`
   - `count:user:{targetId}.follower`
3. block/unfollow flows decrement the same counters after commit

If the after-commit `Count Redis` update fails:

- the original relation write remains successful
- the failure is logged and metered
- repair is triggered only for confirmed `Count Redis` write failure after retry exhaustion, not for every relation event
- before the retry path returns, Nexus must durably persist a `user_counter_repair_outbox` record in MySQL
- each repair record includes at minimum:
  - `sourceUserId`
  - `targetUserId`
  - `operation` such as `FOLLOW`, `UNFOLLOW`, or `BLOCK`
  - `reason` such as `COUNT_REDIS_WRITE_FAILED`
  - request or event correlation id when available
- a dedicated user-counter repair consumer reads only these failure records
- the repair consumer recomputes exact `following` and `follower` values for all affected user ids from MySQL truth and writes them with `COUNT.SET`
- repair records are idempotent; duplicate repair execution is acceptable because recomputation writes absolute counts

This is intentionally best-effort because the authoritative follow truth already lives in MySQL.

## Read Path Design

### Post like count

Read from `COUNT.GET count:post:{postId} like`.

### Comment like/reply count

Read from `COUNT.GET count:comment:{commentId} like` and `reply`.

### User relation stats

Read from `COUNT.GET count:user:{userId} following` and `follower`.

### Reaction state

Read from `REACTION.STATE state:* userId`.

No DB fallback exists for online counter/state reads in the chosen design.

If `Count Redis` is unavailable:

- reaction apply and reaction state APIs fail the request
- user-profile relation-stat reads fail the request
- content-detail like count, feed/search like count, and other best-effort display counts degrade to `0` with warning logs and metrics
- asynchronous hot-rank or indexing side effects log and retry or skip according to their existing retry model

## Failure Semantics

### Online request success

`Count Redis` mutation success defines API success for the online reaction path.

Asynchronous downstream failures do not retroactively change the API result.

This matches the current Nexus philosophy of Redis-first online truth with asynchronous persistence and repair.

### Counter underflow

Commands must never store negative results.

For v1:

- `COUNT.INCRBY` clamps underflow to `0`
- reaction apply returns no-op when requested state does not change
- reaction apply returns `ERR REACTION_CORRUPT_STATE` if state and count invariants are already broken

### Partial side-effect failure

If event-log publish, search update, notification publish, or hot-rank refresh fails after `Count Redis` success:

- online success remains valid
- failure is handled via retry, replay, or operational repair

### Overflow

The module never wraps integer values.

For v1:

- `COUNT.INCRBY` and `COUNT.SET` return `ERR COUNT_OVERFLOW` and perform no mutation when a result would exceed `2^40 - 1`
- `REACTION.APPLY` returns `ERR COUNT_OVERFLOW` and performs no mutation when the target like count would exceed `2^40 - 1`

## Recovery Design

### Reaction recovery

Reaction recovery replays the append-only reaction event log into `Count Redis`.

Recovery semantics must use desired-state replay, not blind delta replay:

- desired `1` adds `userId` only if absent, then increments count
- desired `0` removes `userId` only if present, then decrements count

This keeps replay idempotent.

### Comment reply recovery

`COMMENT.reply` can be rebuilt from comment tables or reply-derived aggregates.

### User follow/follower recovery

`USER.following` and `USER.follower` can be rebuilt from relation truth tables and follower adjacency tables.

### Checkpoints

Reaction recovery checkpoints are stored in `Count Redis` for fast incremental replay.

Disaster-recovery rule:

- if `Count Redis` survives, replay resumes from the stored checkpoint
- if `Count Redis` is lost or reset, the recovery process starts from checkpoint `0` and replays the full MySQL reaction event log into an empty deployment

`COMMENT.reply`, `USER.following`, and `USER.follower` do not rely on checkpoints in v1. They are rebuilt by full recomputation jobs after a full restore.

Recovery only advances a checkpoint after a full page replay succeeds.

### Disaster-recovery serving mode

V1 disaster recovery uses isolated rebuild, not live in-place replay on the active serving deployment.

Required DR sequence:

1. provision a fresh replacement `Count Redis` deployment
2. block Nexus traffic from switching to that replacement until rebuild is complete
3. replay the full reaction event log from checkpoint `0` into the replacement deployment
4. run full recomputation for:
   - `COMMENT.reply`
   - `USER.following`
   - `USER.follower`
5. run validation checks against MySQL and sampled application reads
6. switch Nexus traffic to the rebuilt replacement deployment
7. retire the failed or reset deployment

The active deployment is not expected to accept live writes while it is being rebuilt from empty state.

This avoids the need to reconcile concurrent live traffic with a from-zero replay process.

## Rollout and Cutover

The current environment has no requirement to preserve existing fake counter data.

V1 rollout therefore uses cold cutover, not migration or dual-write.

Steps:

1. deploy `Count Redis` with the module loaded
2. deploy Nexus adapters that can talk to `Count Redis`
3. start with an empty `Count Redis`
4. switch application traffic to the new adapters
5. allow new traffic to create counters and reaction-state objects on demand
6. retire old `interact:reaction:bm:*` and `interact:reaction:cnt:*` keys operationally after validation

There is no backfill or dual-write requirement for the current rollout.

For any future production-like environment where historical data matters, a separate bootstrap specification is required before cutover.

## Operational Design

### Deployment

`Count Redis` is a dedicated deployment, isolated from the current business Redis.

The module is loaded only into this deployment.

### Runtime contract

V1 runtime is intentionally narrow:

- Redis module target ABI is Redis `7.x`
- the validated distribution family is `redis/redis-stack-server`, matching the image family already used by the repo
- V1 deployment topology is single-node standalone `Count Redis`
- Redis Cluster and Redis Sentinel are out of scope for v1
- local and integration environments add a separate `count-redis` service instead of reusing the current business `redis` service
- that dedicated `count-redis` service loads the counter module at process start via Redis module load configuration
- ABI compatibility with non-Redis-7 deployments is not part of the v1 commitment

### Persistence

`Count Redis` is not a disposable cache.

It must use durable persistence appropriate for the online-truth role, such as AOF or an equivalent operationally accepted persistence mode.

### Monitoring

Minimum required telemetry:

- command QPS and P99 for `REACTION.APPLY`
- command QPS and P99 for `COUNT.INCRBY`
- module command failure count
- object counts by family
- memory usage by family
- average bytes per object family
- recovery checkpoint lag
- replay throughput and replay error count

### Verification jobs

Operational verification should include scheduled or manual sampling jobs that compare:

- reaction state/count from `Count Redis`
- reconstructed values from the event log
- relation counts reconstructed from MySQL
- reply counts reconstructed from comment truth tables

## Testing Strategy

### Module tests

- `CountInt` INT5 encode/decode correctness
- field-to-slot mapping correctness
- underflow and overflow behavior
- `RoaringState` add/remove/contains correctness
- `REACTION.APPLY` atomic behavior
- `COUNT.MGETALL` mixed-key behavior

### Application unit tests

- reaction cache adapter command mapping
- object counter adapter command mapping
- user counter adapter command mapping
- existing service tests updated to the new adapter semantics

### Integration tests

- post like/unlike converges correctly under concurrency
- comment like/unlike converges correctly under concurrency
- comment reply increments/decrements correctly
- follow/unfollow/block updates user counters correctly
- recovery rebuild produces the same state and counts as online mutation

## Breaking Changes

- the existing business Redis no longer stores online reaction truth for post/comment likes
- online reaction state/count compatibility with native Redis bitmap/string commands is removed
- the unified counter implementation no longer uses ordinary Redis string values for the covered families

## Open Constraints Accepted By Design

- schema is static in v1 and requires coordinated release of module and application code when changed
- v1 does not pursue mixed-width integer optimization beyond uniform `INT5`
- v1 intentionally specializes only the confirmed five online counter families

## Final Recommendation

Implement a dedicated `Count Redis` deployment backed by a Redis module with:

- `CountInt` compressed counter objects using static-schema unsigned `INT5`
- `RoaringBitmap`-backed reaction state objects
- custom atomic commands for reaction state plus like-count mutation

This design gives Nexus the intended memory reduction without Redis core changes, keeps the ownership boundary narrow, and remains operationally realistic for the current codebase.
