# Nexus Replace Count System With Zhiguang Design

## Status

Draft under review.

## Context

Nexus currently carries multiple incompatible counter contracts:

- Redis-truth reaction state with MySQL event-log replay
- Count Redis compressed snapshots and gap-log recovery control
- RabbitMQ-driven comment like and reply-count derived updates
- MySQL-truth repair for follow and follower counters

That is not the same system as `zhiguang_be`.
It mixes several generations of counter design inside one codebase.

The confirmed direction from brainstorming is destructive replacement:

- copy the real `zhiguang_be` counting implementation shape rather than refining the current Nexus design
- allow breaking changes
- allow introducing Kafka
- do not preserve current count replay, checkpoint, or gap-log semantics
- do not preserve `COMMENT.reply` as a persisted counter capability
- do not keep count-derived Elasticsearch/search-index consumers because ES count fields will be removed in a later ES data-shape refactor
- keep comment business itself, including comment-like notification side effects, but remove reply-count persistence from the counter system

The source implementation being copied is the real code under:

- `../zhiguang_be/src/main/java/com/tongji/counter`
- `../zhiguang_be/src/main/java/com/tongji/relation`
- `../zhiguang_be/src/main/java/com/tongji/knowpost/listener/FeedCacheInvalidationListener.java`

## Goals

- replace the current Nexus counting system with a hybrid counting architecture that keeps zhiguang-style Kafka aggregation for interaction counters and uses RabbitMQ-backed DB-derived projections for business-truth counters
- introduce Redis bitmap fact state plus SDS snapshots for content object counters
- introduce Kafka `counter-events` aggregation for content counter snapshots
- introduce `ucnt:{userId}` fixed-slot user counters
- make follow, follower, post, and any retained comment/reply counters follow DB-truth transactional outbox, RabbitMQ delivery, idempotent projection, and rebuild semantics
- keep read-time rebuild and sampled user-counter verification behavior aligned with zhiguang
- delete reply-count persistence from the counting system

## Non-Goals

- preserving the current Nexus count replay or gap-log recovery model
- preserving the current RabbitMQ-based object interaction aggregation chain as the primary path
- keeping `COMMENT.reply` as a persisted counter family
- migrating legacy counter values
- dual-writing old and new counter structures
- preserving current count interfaces or port abstractions when their shape conflicts with the zhiguang service boundary
- keeping Elasticsearch/search-index count consumers for count propagation

## Final Scope

The replacement explicitly separates counter families into two classes with different consistency and transport contracts.

### Class 1: interaction counters

Class 1 counters are not backed by mandatory business rows for each user action. They use Redis bitmap truth and Kafka aggregation for best-effort display snapshots. The system guarantees idempotent bitmap state transitions, but it does not promise final consistency for `cnt:*` display snapshots or derived Class 1 display fields.

- `POST.like`
- `COMMENT.like`
- `USER.like_received`

`USER.like_received` belongs to Class 1 because it is derived from like interaction truth. The local listener may update it as a fast path, and rebuild may best-effort correct it from owned content like counters while bitmap truth is retained. This is not a durable no-drift guarantee.

### Class 2: DB-derived counters

Class 2 counters are derived from business operations that must be persisted in MySQL. They use MySQL truth, transactional outbox, RabbitMQ projection, persistent idempotency, and rebuild semantics. RabbitMQ is not the truth source.

- `USER.following`
- `USER.follower`
- `USER.post`
- comment/reply counts if product APIs retain them later

The sections below list the active persisted counter families and reserved schema slots.

### Object counter families

- `POST.like`
- `COMMENT.like`

`POST.favorite` / `fav` is reserved for a later change and is not active in this replacement scope.

### User counter families

- `USER.following`
- `USER.follower`
- `USER.post`
- `USER.like_received`

`USER.favorite_received` remains a reserved fixed-slot user-counter position for schema compatibility, but it is not active until favorite support is introduced.

### Explicitly removed from the counter system

- `COMMENT.reply`

`COMMENT.reply` no longer belongs to the persisted counting subsystem.
Comment business stays, but reply count is not maintained as Redis counter truth, Kafka aggregation output, repair target, or rebuild target.

## Chosen Architecture

The final system copies two real zhiguang chains rather than forcing Nexus into one abstracted contract.

### Chain A: Class 1 interaction counter chain

This chain is used for content object likes and the `USER.like_received` value derived from those likes.

Flow:

1. synchronous Redis bitmap truth toggle via Lua
2. emit Kafka `counter-events` only when state actually changes
3. aggregate deltas into Redis `agg:*`
4. flush aggregated deltas every second into SDS snapshot `cnt:*`
5. on malformed or missing snapshot, rebuild from Redis bitmap truth

This chain is the Nexus equivalent of:

- `CounterServiceImpl`
- `CounterEventProducer`
- `CounterAggregationConsumer`
- bitmap rebuild inside `CounterServiceImpl`

### Chain B: DB-derived user and business counter chain

This chain uses business tables as truth and RabbitMQ only as an asynchronous projection transport.
It is not a pure Redis-truth system and does not depend on RabbitMQ as a durable source of truth.

Sub-paths:

- `following` and `follower`
  - business truth in MySQL relation tables
  - the same transaction writes a relation counter outbox event
  - an outbox publisher sends durable RabbitMQ messages with publisher confirms
  - RabbitMQ consumers manually acknowledge only after idempotent projection succeeds
  - the processor updates follower table, Redis ZSet cache, and `ucnt` based on actual state transitions
- `post`
  - business truth in published content rows
  - publish/unpublish/delete transitions produce DB-backed outbox events or guarded direct projections
- comment/reply counts, if product APIs retain them
  - business truth in comment tables
  - RabbitMQ projection may update cache or derived snapshots
  - rebuild must come from comment table truth, not Redis bitmap interaction state

This chain is the Nexus equivalent of:

- `UserCounterServiceImpl`
- `RelationServiceImpl` outbox behavior
- RabbitMQ outbox publisher and DB-derived projection consumers
- `RelationEventProcessor`
- `FeedCacheInvalidationListener`

## Redis Contract

### Content object bitmap fact keys

Format:

- `bm:{metric}:{etype}:{eid}:{chunk}`

Examples:

- `bm:like:post:123:0`
- `bm:like:comment:456:12`

Rules:

- chunking follows the zhiguang bitmap shard rule
- the bitmap layer is the truth for like-state membership
- snapshot corruption never promotes MySQL to object-like truth

### Content object aggregation keys

Format:

- `agg:{schema}:{etype}:{eid}`

Example:

- `agg:v1:post:123`

Rules:

- Hash field is the schema slot index
- Hash value is accumulated delta
- flush consumes and reduces these deltas into SDS snapshots
- slot numbering follows the fixed zhiguang schema indexes rather than a Nexus-local remapping

### Content object snapshot keys

Format:

- `cnt:{schema}:{etype}:{eid}`

Example:

- `cnt:v1:post:123`

Rules:

- binary SDS payload
- fixed-length schema-controlled slot layout
- normal reads use snapshot only

### User counter snapshot keys

Format:

- `ucnt:{userId}`

Example:

- `ucnt:10001`

Rules:

- binary SDS payload
- fixed 5-slot layout
- malformed or missing payload triggers `rebuildAllCounters(userId)`

### Relation cache keys copied from zhiguang

Format:

- `uf:flws:{userId}`
- `uf:fans:{userId}`

Rules:

- Redis ZSet
- query/cache helper, not the authoritative source for follow truth
- maintained by relation event processor
- short TTL behavior may remain aligned with zhiguang

## Schema Contract

### Object snapshot schema

The object snapshot schema follows the zhiguang fixed-slot SDS pattern.

- schema id: `v1`
- field size: `4 bytes`
- schema length: `5`

V1 fixed slot layout copied from `zhiguang_be`:

- slot `0` -> `read` reserved
- slot `1` -> `like`
- slot `2` -> `fav`
- slot `3` -> `comment` reserved in schema only
- slot `4` -> `repost` reserved in schema only

`COMMENT.reply` is removed and does not occupy any active persisted object-counter capability in the new Nexus counting contract.
The reserved `comment` schema slot is not a reply-count contract and must not be repurposed as reply persistence.

### User snapshot schema

`ucnt:{userId}` keeps the zhiguang 5-slot layout.

1. `following`
2. `follower`
3. `post`
4. `like_received`
5. `favorite_received`

Even when some families are not wired in v1, the payload still materializes the full schema.

## Async Transport Contracts

### Kafka counter events topic

Topic:

- `counter-events`

Purpose:

- carry object counter delta events for `POST.like` and `COMMENT.like`

Required event shape:

- `entityType`
- `entityId`
- `metric`
- `idx`
- `userId`
- `delta`

The event represents an effective state change delta, not a final count. This Kafka chain is optimized for high-throughput interaction counters. It guarantees idempotent bitmap state changes, while `cnt:*` remains a best-effort display snapshot that may tolerate bounded loss.

Class 1 aggregation reliability requirements:

- aggregation buckets must be sharded so a hot object does not concentrate all writes into one Redis Hash key
- flush must use an atomic drain or equivalent single-owner protocol so concurrent deltas added during flush are not deleted or skipped
- active bucket and bitmap shard indexes must replace broad `KEYS agg:*` and `KEYS bm:*` scans in production paths
- bounded loss is acceptable only for display snapshots, not for corrupting bitmap state membership

### RabbitMQ DB-derived projection queues

DB-derived counters use RabbitMQ rather than Canal plus Kafka. RabbitMQ is only a transport for projection work; MySQL business tables and transactional outbox rows are the durable sources of truth.

Required parts:

- business table update and counter outbox row in the same transaction
- durable RabbitMQ exchange, queue, and message for DB-derived counter projection
- publisher confirms before marking outbox rows as sent
- manual consumer acknowledgment after idempotent projection success
- dead-letter and retry handling for failed projections
- persistent processed-event records keyed by event id
- consumer idempotency must be backed by a persistent table or by the current business table state; Redis TTL de-duplication is not a final correctness boundary
- DLQ messages must be replayable after the underlying failure is fixed

The semantic chain is:

1. write business truth and outbox in one transaction
2. outbox publisher sends the event to RabbitMQ and waits for publisher confirm
3. RabbitMQ consumer deserializes the event and checks persistent idempotency state
4. processor applies only real state-transition deltas to `ucnt` or derived caches
5. successful processing records the event as processed and acknowledges the message

Generic fire-and-forget RabbitMQ publishing is insufficient for this design.

## Write Contract

### Content like writes

For `POST.like` and `COMMENT.like`:

1. compute bitmap shard key and bit offset from `userId`
2. execute Lua toggle against Redis bitmap fact truth
3. if state did not change, return `changed=false` and emit nothing
4. if state changed, return success and emit:
   - Kafka `counter-events` delta for object count aggregation
   - local Spring event for downstream side effects

The counter core is generic across entity types, matching zhiguang's counter service.
The proven zhiguang local side-effect listener in the referenced codebase is `knowpost`-scoped, not a generic listener for every entity type.
Comment-like notification is retained as Nexus non-count business behavior, but it must not become part of the persisted count correctness contract.

The synchronous success point is the Redis bitmap state transition, matching zhiguang.

### Author like-received writes

When the `knowpost` local counter event listener observes an effective content like delta:

1. resolve the content owner
2. call `UserCounterService.incrementLikesReceived(ownerId, delta)`

This local listener is a fast path, not a durable correctness boundary.
If it fails, `rebuildAllCounters(userId)` may best-effort correct `like_received` from owned content and retained object-like truth, but `like_received` remains a Class 1 display-derived value and does not carry a no-drift guarantee.
The same listener must also keep zhiguang's cache-side responsibilities:

- locate affected feed pages via reverse index `feed:public:index:{entityId}:{hour}`
- update local feed cache entries
- update Redis page JSON while preserving TTL
- remove stale reverse-index members when referenced page keys no longer exist

### Follow and unfollow writes

The follow path must not be redesigned into Redis-first count truth.
It must use the DB-derived RabbitMQ projection flow:

1. business transaction writes relation truth in MySQL
2. the same transaction writes a counter outbox event
3. outbox publisher sends a durable RabbitMQ message with publisher confirm
4. RabbitMQ consumer manually acknowledges only after idempotent processing succeeds
5. processor uses persistent event idempotency and actual relation state transitions
6. successful processing updates:
   - follower table maintenance
   - Redis `uf:flws:*` and `uf:fans:*`
   - `ucnt.following` and `ucnt.follower`

### Post-count writes

When content publish/unpublish/delete changes the published-content truth, the same MySQL transaction must write a post counter outbox event. RabbitMQ projection updates `ucnt.post` idempotently after publisher confirm and consumer processing. `ucnt.post` must not be updated by an unguarded direct increment that bypasses the Class 2 outbox, idempotency, and rebuild path.

### Favorite support

Favorite support is reserved for a later change.
This replacement keeps the fixed SDS slots compatible with the zhiguang shape but does not wire `fav/unfav/isFaved`, `favorite_received`, or favorite-facing public API fields.

## Read Contract

### Object count reads

Normal reads:

- read `cnt:{schema}:{etype}:{eid}`
- decode fixed slots

Malformed or missing snapshot:

- check zhiguang-style rebuild backoff gate first
- apply zhiguang-style rebuild rate limiting
- acquire rebuild lock
- if backoff gate blocks, rate limiter rejects, or lock is unavailable, return zero values for the requested metrics
- rebuild count from bitmap truth using shard `BITCOUNT`
- write rebuilt SDS snapshot
- clear overlapping `agg` fields for rebuilt slots

Behavior notes copied from zhiguang:

- successful rebuild resets backoff state
- rate limiter rejection escalates backoff
- rebuild failure or lock miss escalates backoff
- current zhiguang code discovers bitmap shards with `KEYS bm:{metric}:{etype}:{eid}:*` and then pipelines `BITCOUNT`, but Nexus production behavior must use active shard indexes instead
- the degrade semantics above are required behavior; broad `KEYS` scans are historical reference only and not an accepted production path

### Object state reads

User-specific state reads such as "liked or not" read bitmap truth directly.
They do not read SDS snapshots.

### User count reads

Normal reads:

- read `ucnt:{userId}`
- decode all 5 slots

Internal slot semantics remain:

1. `following`
2. `follower`
3. `post`
4. `like_received`
5. `favorite_received`

Public response naming must stay distinct from internal slot semantics when copying zhiguang's relation controller behavior:

- `followings`
- `followers`
- `posts`
- `likedPosts`

Favorite-facing public fields are out of scope until favorite support is introduced.

Malformed or missing snapshot:

- call `rebuildAllCounters(userId)`
- read `ucnt:{userId}` again

`ucnt:{userId}` is only a projection. Missing, malformed, or suspected-drift values must be rebuilt from MySQL truth for Class 2 slots. `like_received` is a Class 1 display-derived slot and can only be best-effort rebuilt from retained interaction truth while bitmap/object-like snapshots are available.

### User count rebuild sources

`rebuildAllCounters(userId)` follows zhiguang's real mixed-source behavior:

- `following` from relation truth tables
- `follower` from relation truth tables
- `post` from published content rows
- `like_received` best-effort by listing owned content and summing retained object-like snapshots or bitmap-derived counts when available
- `favorite_received` remains reserved and is written as zero until favorite support is introduced

### Sample verification

The design keeps zhiguang-style sampled verification for `following` and `follower`:

- `ucnt:chk:{userId}` throttles verification with zhiguang's 300-second behavior
- compare `ucnt` values against relation table counts
- mismatch triggers `rebuildAllCounters(userId)`

## Failure Handling

### Object counter failures

- bitmap toggle is the synchronous truth point
- Kafka aggregation lag only delays object-like snapshot visibility
- malformed snapshots self-heal from bitmap truth
- small bounded loss in interaction snapshot aggregation is acceptable when bitmap truth remains available

### User counter failures

- RabbitMQ projection lag delays DB-derived `ucnt` values but must not lose business-truth events
- local event listener failure can delay `like_received` fast-path updates
- local event listener failure can also leave feed-page cache invalidation temporarily stale
- malformed or missing `ucnt` self-heals through `rebuildAllCounters`
- sampled verification repairs silent drift for relation-derived user slots

### No reply-count repair

Because `COMMENT.reply` is removed from the persisted counting subsystem, there is no reply-count rebuild, replay, repair, or checkpoint requirement.

## Delete And Replace Plan

The following current Nexus count semantics must be removed rather than adapted.

### Remove current replay and checkpoint recovery semantics

Remove the current design role of:

- `CountGapReplayRecoveryRunner`
- `ReactionRedisRecoveryRunner`
- `CountAofCheckpointJob`
- `CountRdbCheckpointJob`
- `CountRecoveryControlPort`
- `CountGapLogPurgeJob`
- `RelationGapLogRepository`
- reaction-event-log-driven replay as the primary count recovery contract

### Remove reply-count persistence semantics

Remove the current design role of:

- `RootReplyCountChangedConsumer`
- persisted `COMMENT.reply` counter contracts
- reply rebuild and reply repair jobs or logic
- any count-module schema slots dedicated to persisted reply count

### Remove current MySQL-truth follow counter repair semantics

Remove the current design role of:

- `FollowCountStatePort`
- `UserCounterRepairJob`
- any path whose purpose is to restore Redis follow counters from current Nexus count-repair semantics

### Replace current RabbitMQ count aggregation semantics

RabbitMQ-derived counter aggregation paths must not remain the primary persisted object-count path after cutover.
The copied zhiguang object-count chain is Kafka-based.

### Delete old counter port abstractions

`ObjectCounterPort`, `IObjectCounterPort`, `UserCounterPort`, and `IUserCounterPort` must be hard-deleted when their callers are migrated.
Do not keep thin compatibility adapters.
The replacement service boundary must follow the zhiguang-shaped counter services directly.

## Operational Notes

- Kafka becomes a required dependency only for the high-throughput object-like interaction counter chain
- RabbitMQ becomes the transport for DB-derived counter projections such as relation, post, and retained comment/reply-derived counters
- Canal is not required by this replacement because DB-derived projections use transactional outbox publishing instead of binlog bridging
- Redis becomes the bitmap truth and snapshot store for content object counters
- user counters keep mixed rebuild semantics rather than a pure Redis-truth rebuild model
- Elasticsearch does not receive count-field update consumers from this replacement because count fields are being removed from ES documents
- `COMMENT.reply` disappears from the persisted count contract and from all associated operational runbooks

## Risks

- this is a destructive replacement and intentionally drops the current Nexus recovery model
- the hybrid model has two transport paths: Kafka for interaction counters and RabbitMQ for DB-derived projections
- favorite is reserved but not active in this change, so later Nexus favorite business wiring must explicitly opt into the object-counter path
- introducing Kafka plus RabbitMQ increases operational surface area, but avoids requiring Canal for DB-derived counters
- sampled user-counter verification must be carried over correctly or silent drift detection will regress
- omitting persistent idempotency for RabbitMQ projections would make DB-derived counters and caches drift under repeated delivery
- omitting feed-cache side effects from the local counter listener would regress interaction read freshness, but it must not be the durable correctness boundary

## Success Criteria

The design is considered correctly implemented only when all of the following are true:

- object likes use bitmap truth, Kafka aggregation, `agg:*`, and `cnt:*`; favorite remains reserved and inactive
- `ucnt:{userId}` is the only persisted user counter snapshot object
- follow and follower counters are maintained through MySQL relation truth, transactional outbox, RabbitMQ, persistent idempotent projection, and `ucnt`
- `knowpost` author `like_received` fast-path updates happen through the local event-listener side path, while rebuild is only a best-effort correction path for this Class 1 display-derived value
- comment-like notification remains as non-count business behavior, separate from persisted counter correctness
- the copied `knowpost` local event listener still performs feed cache invalidation duties in addition to `like_received` fast-path updates
- no search-index consumer remains for count-field propagation into Elasticsearch
- no persisted `COMMENT.reply` counter capability remains in the counting subsystem
- no gap-log, checkpoint, or RabbitMQ-based counter replay semantics remain as the primary counting contract
