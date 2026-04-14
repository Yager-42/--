## Context

Nexus currently keeps counting behavior split across multiple implementations. `ReactionCachePort` owns bitmap state and direct `cnt` values for likes, `ObjectCounterPort` and `UserCounterPort` act as partial facades with Redis string keys and database rebuilds, and reply and user counters rely on separate cache paths. This does not match the target Count Redis model discussed for Nexus: one Redis-first counting system with compressed snapshots, explicit rebuild rules, and a single operational story.

The target shape is zhiguang's counting architecture without Kafka. Count Redis will own online counter state and snapshots, RabbitMQ will keep asynchronous delta fanout, and MySQL event logs will remain the replay and audit ledger. The design intentionally preserves Redis-first latency and accepts that replay logs are not part of the synchronous success contract.

## Goals / Non-Goals

**Goals:**
- Replace Nexus's current counting path with a dedicated Count Redis design using bitmap facts, aggregation buckets, SDS snapshots, and rebuild controls.
- Move supported object counters to Count Redis: `POST.like`, `COMMENT.like`, and `COMMENT.reply`.
- Move supported user counters to Count Redis: `USER.following`, `USER.follower`, and `USER.like_received`, while reserving schema slots for `USER.post` and `USER.favorite_received`.
- Provide read-time rebuild, bad-data self-healing, and replay checkpoints so corrupted or missing snapshots can be repaired.
- Keep domain counter interfaces stable so callers do not need a second API migration during the storage cutover.

**Non-Goals:**
- Introducing Kafka or RabbitMQ Streams.
- Migrating legacy fake counter data before cutover.
- Reworking unrelated Redis usage outside the counting system.
- Shipping INT5 custom slot encoding in the first cutover. The first version uses simpler fixed-width SDS slots and reserves schema versioning for later compression upgrades.
- Expanding product behavior beyond the supported metrics above.

## Decisions

### 1. Count Redis becomes the single online counter store

The new design treats Count Redis as the only online source for counter state and snapshot reads. Existing `counter:object:*`, `counter:user:*`, and direct `interact:reaction:cnt:*` reads stop being primary data sources after cutover.

Why this over keeping the current split model:
- It removes duplicate counting semantics spread across reaction cache, counter facades, and DB fallbacks.
- It aligns object and user counters under one key model and one rebuild story.
- It matches the explicit direction to let Count Redis own both state and counts.

### 2. Supported object metrics use two truth models

`POST.like` and `COMMENT.like` use per-user bitmap facts plus asynchronous aggregation into SDS snapshots. `COMMENT.reply` does not use a per-user bitmap because reply count is not a toggle fact; it uses event-driven aggregation into SDS and rebuilds from database truth when needed.

Why this over forcing every metric into bitmap form:
- Like state is naturally representable as a user/object toggle and can be rebuilt from bitmap cardinality.
- Reply count is an aggregate business event, not a membership set, so forcing bitmap state would add storage cost without adding truth value.

### 3. Supported user metrics use SDS snapshots with source-specific rebuild logic

User snapshots use a fixed slot layout: `following`, `follower`, `post`, `like_received`, `favorite_received`. `following` and `follower` rebuild from relation storage. `like_received` is updated from effective post/comment like deltas. `post` and `favorite_received` remain reserved slots until their write paths are migrated.

Why this over keeping string-per-counter keys:
- The fixed slot model is the direct equivalent of zhiguang's `ucnt:{userId}` contract.
- It keeps future expansion compatible with schema versioning and avoids repeated key names.

### 4. Schema is code-defined and versioned

Object and user slot definitions live in application code. The Redis layer never looks up schema metadata at runtime from Redis or a separate service.

Why this over a runtime schema registry:
- The schema is small, static, and deployment-coupled.
- Runtime schema lookups would add latency and new failure modes to every read/write path.
- It matches the previously accepted decision to keep schema embedded in code.

### 5. RabbitMQ remains the asynchronous fanout path; Kafka is not replaced

The synchronous path ends once Count Redis fact state is updated. Effective deltas are then published through the existing RabbitMQ stack for aggregation and downstream side effects. Kafka is not introduced, and RabbitMQ is not treated as a source of truth for replay completeness.

Why this over synchronous aggregation or introducing Kafka:
- Reusing RabbitMQ keeps the architecture close to the current Nexus stack and avoids a new operational dependency.
- Asynchronous fanout protects request latency better than synchronously updating every affected snapshot in the write path.
- Introducing Kafka would add operational cost without solving the accepted Redis-first replay gap by itself.

### 6. MySQL event logs remain replay and audit ledger, not synchronous success criterion

Replayable counter events are persisted through the existing event-log path and replayed with checkpoints into Count Redis. The design does not require event-log durability before the user-facing write returns success.

Why this over a strong synchronous ledger:
- It preserves Redis-first latency and matches zhiguang's practical success contract.
- The system can still self-heal snapshot corruption through bitmap or database rebuild even if some replay records are missing.
- A stronger event-log guarantee would require a different success contract and materially higher write-path cost.

Alternative considered:
- Promote event-log persistence to the synchronous success criterion. Rejected for this change because it changes the business write contract and defeats the stated Redis-first performance goal.

### 7. Fixed-width SDS first, INT5 later

The first schema version uses fixed-width slots to reduce implementation risk during the architectural replacement. INT5 or other denser encodings are deferred to a later schema version.

Why this over implementing INT5 immediately:
- The cutover already replaces storage shape, rebuild logic, consumers, and tests.
- Fixed-width SDS is simpler to verify, replay, and debug.
- Compression can be introduced later behind a schema-versioned codec once the system is behaviorally stable.

### 8. Migration is destructive and does not preserve legacy counting data

This change uses a destructive cutover. Legacy fake counter data is not migrated. Once the new Count Redis path is ready, legacy counting code, unused counting tables, obsolete Redis keyspaces, old checkpoints, and stale middleware messages are removed or cleared so that only the final counting structures remain active.

Why this over dual-write or staged compatibility:
- The current environment does not require preserving historical counter values.
- Dual-write and compatibility layers would add complexity to a system that is already being structurally replaced.
- Clearing stale intermediate state prevents the new replay and rebuild flows from consuming legacy data with incompatible semantics.

## Risks / Trade-offs

- `[Redis-first replay gap]` -> If Redis fact writes succeed but event-log persistence or async fanout fails, cross-Redis disaster replay may miss some deltas. Mitigation: keep this as an explicit accepted trade-off, document it, and rely on bitmap/database rebuild for in-Redis self-healing.
- `[Eventual consistency window]` -> SDS snapshots may lag behind recent writes because aggregation is asynchronous. Mitigation: keep reads on snapshots, trigger rebuild on malformed data, and confine strict truth reads to rebuild paths.
- `[Cutover complexity]` -> Existing code paths read several legacy keys and repositories. Mitigation: replace adapters behind existing interfaces first, then remove old key usage once integration tests pass.
- `[No legacy fallback after cutover]` -> Once destructive cleanup runs, the old counting result cannot be restored from previous Redis keys or fake cache tables. Mitigation: finish verification before cleanup and treat cleanup as the final cutover step.
- `[Reply rebuild cost]` -> Reply snapshots rebuild from DB truth, which is more expensive than bitmap cardinality. Mitigation: reserve rebuild for exceptional paths, guard with locks/rate limits/backoff, and keep normal reads on snapshots.
- `[Reserved user slots]` -> `post` and `favorite_received` exist before full write-path support. Mitigation: define them explicitly as zero-preserving reserved slots until later migration.
- `[Legacy middleware contamination]` -> Old queued messages or replay checkpoints can corrupt the new counting model after cutover. Mitigation: clear legacy queue payloads, replay checkpoints, and obsolete aggregation keys during the destructive migration window.

## Migration Plan

1. Add the final Count Redis schema, final replay/checkpoint tables, and final adapter implementations in code without attempting to transform legacy fake data.
2. Switch supported read and write entry points to the new Count Redis path for object and user counters while preserving the public domain port signatures.
3. Verify the new path with targeted unit, integration, replay, and rebuild tests before any destructive cleanup.
4. Remove legacy counting code that is no longer referenced once the new path owns all supported metrics.
5. Drop or retire legacy counting tables that are no longer needed by any non-counting workflow, and keep only the final tables required by the new design.
6. Clear obsolete Redis keys, aggregation buckets, replay checkpoints, and middleware backlog that belong to the previous counting model so the new system starts from a clean state.
7. Treat cleanup as the final irreversible cutover step; before that point rollback is code-based, and after that point rollback means redeploying the old implementation and rebuilding state rather than restoring preserved fake data.

## Open Questions

- Whether `USER.post` should be migrated in the same change or left reserved for the next change remains open; this design leaves it reserved.
- Whether replay logs should later be strengthened into a synchronous success criterion remains open and is intentionally deferred.
