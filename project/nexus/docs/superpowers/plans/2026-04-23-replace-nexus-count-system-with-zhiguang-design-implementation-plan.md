# Replace Nexus Count System With Zhiguang Design Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the current Nexus counting subsystem with the approved hybrid architecture: Kafka-backed zhiguang-style interaction counting for likes, RabbitMQ-backed DB-derived projections for business-truth counters, `ucnt:{userId}` mixed-source user counters, and removal of reply persistence plus replay/checkpoint/search-index count semantics.

**Architecture:** The work deletes the current Nexus hybrid count design instead of adapting it. Object likes move to Redis bitmap truth plus Kafka `counter-events` aggregation plus SDS `cnt:*`; relation, post, and any retained comment/reply-derived counters use MySQL truth plus transactional outbox plus RabbitMQ plus persistent idempotent projection into `ucnt` or derived caches. `knowpost` local side effects remain as fast-path cache and `like_received` updates, while rebuild is only a best-effort correction path for that Class 1 display-derived value. The implementation intentionally breaks old count contracts and removes reply persistence, old repair jobs, RabbitMQ primary object-aggregation paths, Canal relation counting, and count-field search-index consumers.

**Tech Stack:** Spring Boot, Redis, Redisson, RabbitMQ, Kafka, MyBatis, JUnit 5, Mockito, existing Nexus multi-module Maven build. Canal is not required for the revised DB-derived counter projection path.

**Counter Classes:**

- Class 1 interaction counters: `POST.like`, `COMMENT.like`, and `USER.like_received`. `like_received` is intentionally classified with likes, not with DB-derived relation/post counters, because it is derived from like interaction truth. These use Redis bitmap truth plus Kafka aggregation. Bitmap state transitions must be idempotent, but `cnt:*` and `like_received` are best-effort display values and do not carry a no-drift final-consistency guarantee.
- Class 2 DB-derived counters: `USER.following`, `USER.follower`, `USER.post`, and retained comment/reply-derived counters if product APIs later keep them. These use MySQL truth plus transactional outbox plus RabbitMQ projection with persistent idempotency and rebuild; they must not lose business-truth events.

---

## Chunk 1: Current-State File Map And Cutover Boundaries

### Task 1: Freeze the replacement boundary before code changes

**Files:**
- Reference: `docs/superpowers/specs/2026-04-23-replace-nexus-count-system-with-zhiguang-design.md`
- Reference: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/counter/port/ObjectCounterPort.java`
- Reference: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/counter/port/UserCounterPort.java`
- Reference: `../zhiguang_be/src/main/java/com/tongji/counter/service/impl/CounterServiceImpl.java`
- Reference: `../zhiguang_be/src/main/java/com/tongji/counter/service/impl/UserCounterServiceImpl.java`
- Reference: `../zhiguang_be/src/main/java/com/tongji/relation/api/RelationController.java`
- Reference: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/counter/support/CountRedisSchema.java`
- Reference: `nexus-domain/src/main/java/cn/nexus/domain/social/service/ReactionLikeService.java`
- Reference: `nexus-domain/src/main/java/cn/nexus/domain/social/service/RelationService.java`
- Reference: `nexus-domain/src/main/java/cn/nexus/domain/social/service/ContentService.java`
- Reference: `nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/PostLikeCountAggregateConsumer.java`
- Reference: `nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/RootReplyCountChangedConsumer.java`
- Reference: `nexus-trigger/src/main/java/cn/nexus/trigger/listener/social/RelationEventListener.java`

- [ ] **Step 1: Re-read the approved spec and list the required hard deletes**

Record the hard deletes in the working notes before touching code:

- `COMMENT.reply` persisted counting
- RabbitMQ primary object aggregation
- reaction replay, checkpoint, gap-log recovery roles
- `UserCounterRepairJob` semantics
- direct relation-write-side `userCounterPort.increment(...)` semantics
- search-index consumers for count-field propagation into ES
- old `ObjectCounterPort`/`UserCounterPort` abstractions after callers move to the new Nexus-named counter services

- [ ] **Step 2: Confirm the old implementation boundaries in current code**

Run:

```bash
rg -n "RootReplyCountChangedConsumer|PostLikeCountAggregateConsumer|UserCounterRepairJob|ReactionRedisRecoveryRunner|CountAofCheckpointJob|CountRdbCheckpointJob|RelationEventListener|ReactionEventLog" nexus-trigger nexus-domain nexus-infrastructure
```

Expected:
- Existing old-chain files are present and will become delete or rewrite targets.

- [ ] **Step 3: Create a short cutover checklist in working notes**

The checklist must separate:

- object count chain replacement
- Class 1 interaction user side-path replacement for `like_received`
- Class 2 DB-derived user count replacement
- `knowpost` side-path replacement
- delete-only legacy areas

- [ ] **Step 4: Commit the planning checkpoint**

```bash
git add docs/superpowers/specs/2026-04-23-replace-nexus-count-system-with-zhiguang-design.md docs/superpowers/plans/2026-04-23-replace-nexus-count-system-with-zhiguang-design-implementation-plan.md
git commit -m "docs: add zhiguang counter replacement implementation plan"
```

## Chunk 2: Replace Object Counter Schema And Redis Contracts

### Task 2: Replace the count Redis schema with zhiguang-compatible fixed slots

**Files:**
- Modify: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/counter/support/CountRedisSchema.java`
- Modify: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/counter/support/CountRedisKeys.java`
- Modify: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/counter/support/CountRedisCodec.java`
- Modify: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/counter/support/CountRedisOperations.java`
- Test: `nexus-infrastructure/src/test/java/cn/nexus/infrastructure/adapter/counter/support/CountRedisSchemaSupportTest.java`
- Test: `nexus-infrastructure/src/test/java/cn/nexus/infrastructure/adapter/counter/support/CountRedisOperationsTest.java`

- [ ] **Step 1: Write failing tests for zhiguang slot layouts**

Cover:

- object SDS constants exactly match zhiguang: `SCHEMA_ID=v1`, `SCHEMA_LEN=5`, `FIELD_SIZE=4`
- object SDS fields are big-endian 32-bit unsigned/int32-compatible counters
- object active metric is only `like`; `read`, `fav`, `comment`, and `repost` slots are reserved in this change
- object slot `0 read reserved`, `1 like`, `2 fav reserved`, `3 comment reserved`, `4 repost reserved`
- user API/logical indexes are `1 following`, `2 follower`, `3 post`, `4 like_received`, `5 favorite_received reserved`
- user internal byte offset is `(idx - 1) * 4`; do not describe this as storage slots `0..4`
- `COMMENT.reply` absent from active counter type mapping

- [ ] **Step 2: Run the schema tests and confirm failure**

Run:

```bash
mvn -pl nexus-infrastructure -Dtest=CountRedisSchemaSupportTest,CountRedisOperationsTest test
```

Expected:
- FAIL on slot count, slot mapping, or reply-slot expectations.

- [ ] **Step 3: Rewrite `CountRedisSchema` to the approved fixed-slot contract**

Implementation notes:

- object schema must stop exposing reply as an active metric
- object schema must expose only `like` as an active metric name in this change
- user schema must materialize five fixed user logical indexes with `(idx - 1) * 4` byte offsets
- schema names and slot ordering should match the approved spec, not the current `post_counter/comment_counter/user_counter` contract

- [ ] **Step 4: Update key helpers and codec helpers to support the new payload widths and naming**

Implementation notes:

- stop carrying legacy reply-specific bucket helpers as first-class active schema behavior
- object snapshot key is `cnt:v1:{etype}:{eid}`
- object bitmap shard key is `bm:{metric}:{etype}:{eid}:{chunk}` with `CHUNK_SIZE=32768`
- object aggregation key is `agg:v1:{etype}:{eid}`
- keep helper APIs small and explicit around object snapshot, object agg, bitmap shard, user snapshot, relation cache keys

- [ ] **Step 5: Run the focused schema tests again**

Run:

```bash
mvn -pl nexus-infrastructure -Dtest=CountRedisSchemaSupportTest,CountRedisOperationsTest test
```

Expected:
- PASS

- [ ] **Step 6: Commit**

```bash
git add nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/counter/support nexus-infrastructure/src/test/java/cn/nexus/infrastructure/adapter/counter/support
git commit -m "refactor: replace count redis schema with zhiguang slots"
```

## Chunk 3: Replace Object Counter Core With Bitmap Truth Plus Kafka Aggregation

### Task 3: Introduce the object counter service and demote old port abstractions

**Files:**
- Create: Nexus-named counter service interface under `nexus-domain` exposing `like/unlike/isLiked/getCounts/getCountsBatch`
- Create: Redis/Kafka-backed counter service implementation under `nexus-infrastructure`
- Modify or Create: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/counter/support/*` helpers needed for bitmap shards, agg flush, and rebuild support
- Delete: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/counter/port/ObjectCounterPort.java`
- Delete: `nexus-domain/src/main/java/cn/nexus/domain/counter/adapter/port/IObjectCounterPort.java`
- Test: replace port-centered tests with service-centered tests under `nexus-infrastructure/src/test/java/cn/nexus/infrastructure/adapter/counter/` or matching module package

- [ ] **Step 1: Write failing tests for object-count behavior**

Cover:

- like toggle changes bitmap truth only on state transition
- no-op like/unlike emits no delta
- bitmap key is `bm:{metric}:{etype}:{eid}:{chunk}` with `CHUNK_SIZE=32768`
- aggregation key is `agg:v1:{etype}:{eid}`
- single-entity `getCounts(...)` on malformed or missing SDS triggers bitmap rebuild
- batch `getCountsBatch(...)` on missing or malformed SDS returns zero values and does not trigger rebuild
- backoff gate returns zero values
- rate limiter rejection returns zero and escalates backoff state
- lock miss returns zero and escalates backoff state
- rebuild clears overlapping `agg` fields

- [ ] **Step 2: Run the object-counter service tests and confirm failure**

Run:

```bash
mvn -pl nexus-infrastructure -Dtest=*Counter* test
```

Expected:
- FAIL because current implementation is snapshot arithmetic, not bitmap truth.

- [ ] **Step 3: Add the object counter service API**

Implementation notes:

- use Nexus module naming; class and package names do not need to include `Zhiguang`
- the public boundary must match `CounterServiceImpl` semantics, not a Nexus-only generic `increment(...)` abstraction
- expose `like/unlike/isLiked/getCounts/getCountsBatch`
- delete old object counter port abstractions after migrating callers
- do not keep compatibility adapters or implement the replacement as an `ObjectCounterPort` rewrite with a service wrapper on top

- [ ] **Step 4: Replace arithmetic increment logic with bitmap-truth toggle logic**

Implementation notes:

- state read path `isLiked` must hit bitmap truth via `GETBIT`
- snapshot read path must decode SDS only
- `getCounts(...)` may rebuild from bitmap truth when the single object SDS is missing or malformed
- `getCountsBatch(...)` must not rebuild; missing or malformed object SDS returns zero for requested metrics
- old `increment(...)`-centric logic should not remain the conceptual center of the object counting design

- [ ] **Step 5: Implement zhiguang rebuild protections**

Implementation notes:

- this step covers read-path bitmap rebuild inside the counter service, not `CounterRebuildConsumer`
- backoff keys and escalation
- rate limiter gate with escalation on rejection
- rebuild lock with escalation on lock miss
- successful rebuild resets backoff
- current shard discovery can use existing Redis scan support, but behavior must match the approved degrade semantics
- `CounterRebuildConsumer` is a separate disaster-replay consumer and must remain disabled by default unless `counter.rebuild.enabled=true`

- [ ] **Step 6: Run focused object-counter tests**

Run:

```bash
mvn -pl nexus-infrastructure -Dtest=*Counter* test
```

Expected:
- PASS

- [ ] **Step 7: Commit**

```bash
git add nexus-domain nexus-infrastructure
git commit -m "refactor: add object counter service"
```

### Task 4: Delete RabbitMQ primary aggregation and introduce Kafka object aggregation

**Files:**
- Delete or Rewrite: `nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/PostLikeCountAggregateConsumer.java`
- Delete or Rewrite: `nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/strategy/SnapshotPostLikeCountAggregateStrategy.java`
- Create: Nexus-named equivalents of `CounterEventProducer`, `CounterAggregationConsumer`, and `CounterTopics` in the counter implementation area
- Test: `nexus-trigger/src/test/java/cn/nexus/trigger/mq/consumer/strategy/SnapshotPostLikeCountAggregateStrategyTest.java`
- Test: new Kafka aggregation tests

- [ ] **Step 1: Write failing tests for Kafka `counter-events` aggregation**

Cover:

- state-change delta event payload
- accumulation into sharded `agg:*` buckets so hot objects do not write one single Redis Hash key
- periodic atomic drain from `agg:*` into `cnt:*`
- concurrent deltas added during flush are not deleted or skipped
- active bucket indexes replace broad `KEYS agg:*` scans
- bitmap shard indexes replace broad `KEYS bm:*` scans for rebuild
- no RabbitMQ dependency in primary object count path

- [ ] **Step 2: Run tests and confirm failure**

Run:

```bash
mvn -pl nexus-trigger -Dtest=SnapshotPostLikeCountAggregateStrategyTest test
```

Expected:
- FAIL or become obsolete because current consumer is RabbitMQ-based.

- [ ] **Step 3: Delete the RabbitMQ primary path and add Kafka producer/consumer equivalents**

Implementation notes:

- `CounterEventProducer` uses Kafka topic `counter-events`
- `CounterAggregationConsumer` listens with group id `counter-agg`
- `CounterAggregationConsumer` flushes sharded `agg:*` into `cnt:*` with `@Scheduled(fixedDelay = 1000L)` or an equivalent scheduler
- flush must use atomic drain or an equivalent single-owner protocol; never delete an `agg` field after reading it if concurrent deltas may have been added
- maintain active aggregation and bitmap shard indexes instead of production `KEYS agg:*` or `KEYS bm:*` scans
- scope this task to the persisted object-count primary path only
- do not mix search-index consumers into the copied zhiguang counter chain

- [ ] **Step 4: Wire the new Kafka topics in config**

Implementation notes:

- topic name must be `counter-events`
- no old RabbitMQ count queue should remain the authoritative path

- [ ] **Step 5: Run trigger tests for the new object aggregation path**

Run:

```bash
mvn -pl nexus-trigger -Dtest=*Count* test
```

Expected:
- PASS for new Kafka path tests
- deleted RabbitMQ-primary tests removed or rewritten

- [ ] **Step 6: Commit**

```bash
git add nexus-trigger
git commit -m "refactor: replace rabbitmq count aggregation with kafka"
```

### Task 5: Delete search-index count-field consumers

**Files:**
- Delete: `nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/PostLikeCount2SearchIndexConsumer.java`
- Delete: `nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/strategy/SnapshotPostLikeCount2SearchIndexStrategy.java`
- Delete: `nexus-trigger/src/main/java/cn/nexus/trigger/mq/config/CountPostLike2SearchIndexMqConfig.java`
- Delete: `nexus-trigger/src/test/java/cn/nexus/trigger/mq/consumer/strategy/SnapshotPostLikeCount2SearchIndexStrategyTest.java`
- Modify: `nexus-trigger/src/main/java/cn/nexus/trigger/search/support/SearchIndexUpsertService.java`
- Modify: `nexus-trigger/src/main/java/cn/nexus/trigger/search/support/SearchDocumentAssembler.java`
- Modify: `nexus-app/src/main/java/cn/nexus/config/SearchIndexBackfillRunner.java`
- Modify: `nexus-app/src/main/java/cn/nexus/config/SearchIndexInitializer.java`
- Modify: `nexus-domain/src/main/java/cn/nexus/domain/social/adapter/port/ISearchEnginePort.java`
- Modify: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/SearchEnginePort.java`
- Modify: `nexus-domain/src/main/java/cn/nexus/domain/social/model/valobj/SearchDocumentVO.java`
- Modify: `nexus-api/src/main/java/cn/nexus/api/social/search/dto/SearchItemDTO.java`
- Test: update search service, search engine, upsert, backfill, and search API tests that assert count fields

- [ ] **Step 1: Write or update deletion assertions for ES count propagation**

Cover:

- no search-index consumer remains for count-field propagation
- no count replacement task writes like/favorite/comment/reply counters into Elasticsearch documents
- `SearchIndexUpsertService`, `SearchIndexBackfillRunner`, and `SearchIndexInitializer` no longer read, map, initialize, or backfill count fields
- `SearchEnginePort`, `SearchDocumentVO`, and `SearchItemDTO` remove count fields and count-based sort/return contracts
- keep user-state reaction booleans such as `liked`/`faved` if they are computed outside ES count fields
- this deletion is a Nexus ES target decision, not directly derived from `zhiguang_be` counter/** or knowpost/** code
- verify against the Nexus search module before deleting any non-count search behavior
- ES data-shape changes are outside this count replacement and must not reintroduce count consumers

- [ ] **Step 2: Delete the search-index count-field path**

Implementation notes:

- remove the RabbitMQ consumer, strategy, config, and tests listed above
- do not replace it with a Kafka consumer
- remove count fields from ES mapping initialization, search document assembly, search upsert, search backfill, search engine mapping, search DTOs, and count-based sorting
- preserve `liked`/`faved` response fields only as user-state fields computed outside the ES document count schema
- this is a hard delete because later ES document fields will not carry these counters

- [ ] **Step 3: Verify no search-index count consumer remains**

Run:

```bash
rg -n "PostLikeCount2SearchIndex|CountPostLike2SearchIndex|SnapshotPostLikeCount2SearchIndex|like_count|favorite_count|comment_count|reply_count|likeCount|favoriteCount|commentCount|replyCount|sort.*count|count.*sort" nexus-trigger nexus-domain nexus-infrastructure nexus-app nexus-api
```

Expected:
- no active count-field propagation consumer remains
- remaining `likeCount` references are non-search feed/comment/API fields backed by the new counter service, not ES document fields

- [ ] **Step 4: Commit**

```bash
git add nexus-trigger
git commit -m "refactor: remove search index count propagation"
```

## Chunk 4: Replace Reaction Like Write Path And Knowpost Local Side Effects

### Task 6: Rewrite reaction like writes to use the new object counter core

**Files:**
- Modify: `nexus-domain/src/main/java/cn/nexus/domain/social/service/ReactionLikeService.java`
- Delete after caller migration: `nexus-domain/src/main/java/cn/nexus/domain/social/adapter/port/IReactionCachePort.java`
- Delete after caller migration: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/ReactionCachePort.java`
- Modify: all current `IReactionCachePort` callers in the same chunk so the intermediate state compiles
- Delete or Rewrite: reaction-event-log MQ/outbox paths used only for old replay semantics
- Test: `nexus-domain/src/test/java/cn/nexus/domain/social/service/ReactionLikeServiceTest.java`
- Test: `nexus-app/src/test/java/cn/nexus/integration/interaction/ReactionHttpRealIntegrationTest.java`

- [ ] **Step 1: Write failing tests for zhiguang-style reaction semantics**

Cover:

- synchronous success point is Redis bitmap toggle
- no old reaction event log is required for correctness
- no old `ReactionCachePort` bitmap/factcnt/recovery capability remains as a parallel fact source
- post like uses the zhiguang-proven `knowpost` object counter path
- favorite is reserved and must not be wired in this task
- comment like uses the generic object counter core as a Nexus extension; zhiguang reference proves the `knowpost` like path
- `like_received` side effects are fast-path local side effects, not a durable correctness boundary
- comment-like notification remains as non-count business behavior

- [ ] **Step 2: Run the domain and integration tests and confirm failure**

Run:

```bash
mvn -pl nexus-domain -Dtest=ReactionLikeServiceTest test
mvn -pl nexus-app -Dtest=ReactionHttpRealIntegrationTest test
```

Expected:
- FAIL because current service still depends on old cache/MQ/event-log semantics.

- [ ] **Step 3: Rewrite `ReactionLikeService` to use the new object counter API**

Implementation notes:

- remove dependence on reaction replay logs for correctness
- replace all `IReactionCachePort` callers before deleting the port files, in this same chunk, so the codebase remains compilable at the task boundary
- introduce a Nexus-named reaction query service in the domain layer if callers need a business-facing wrapper around the counter service
- the reaction query service must delegate to the new counter service and must not own Redis fact state or recovery semantics
- merge any still-needed bitmap/fact-state capability into the counter service; do not leave `ReactionCachePort` as a sidecar source of truth
- on each successful toggle, emit both the Kafka object delta event and the local Spring `CounterEvent`
- keep comment-like notification side effects as required non-count business behavior
- keep comment-like notification outside the persisted counter correctness contract
- document comment-like object counting as a Nexus extension over the zhiguang-proven `knowpost` path

- [ ] **Step 4: Remove old replay-only ports and code paths**

Implementation notes:

- delete dead `ReactionEventLog` correctness dependencies
- delete old `ReactionCachePort`/`IReactionCachePort` after all callers compile against the new counter service or the Nexus-named reaction query service
- keep comment-like notification as business-side notification behavior outside counting
- keep only other business-side notifications/recommend feedback that still matter outside counting

- [ ] **Step 5: Run the focused tests again**

Run:

```bash
mvn -pl nexus-domain -Dtest=ReactionLikeServiceTest test
mvn -pl nexus-app -Dtest=ReactionHttpRealIntegrationTest test
```

Expected:
- PASS

- [ ] **Step 6: Commit**

```bash
git add nexus-domain/src/main/java/cn/nexus/domain/social/service/ReactionLikeService.java nexus-domain/src/test/java/cn/nexus/domain/social/service/ReactionLikeServiceTest.java nexus-app/src/test/java/cn/nexus/integration/interaction/ReactionHttpRealIntegrationTest.java
git commit -m "refactor: rewrite reaction likes around counter core"
```

### Task 7: Introduce the real `knowpost`-scoped local side-effect listener behavior

**Files:**
- Create or Rewrite: a `FeedCacheInvalidationListener` equivalent as a local Spring `@Component` with `@EventListener`, not in `nexus-trigger`, for `knowpost` counter changes
- Modify: `nexus-domain/src/main/java/cn/nexus/domain/social/service/ReactionLikeService.java`
- Modify: `nexus-domain/src/main/java/cn/nexus/domain/social/service/FeedService.java`
- Modify: feed cache repositories or ports under `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/*`
- Modify: post-author lookup ports and repositories as needed
- Test: new unit tests for the listener
- Test: `nexus-app/src/test/java/cn/nexus/integration/feed/FeedHttpRealIntegrationTest.java`

- [ ] **Step 1: Write failing tests for `knowpost` local side effects**

Cover:

- `knowpost` like delta increments `USER.like_received`
- favorite deltas are out of scope and do not increment `USER.favorite_received` in this change
- feed-page cache updates use reverse index semantics
- Redis page JSON TTL is preserved
- non-`knowpost` entity types do not run this listener

- [ ] **Step 2: Run tests and confirm failure**

Run:

```bash
mvn -pl nexus-app -Dtest=FeedHttpRealIntegrationTest test
```

Expected:
- FAIL because current Nexus has no zhiguang-equivalent local listener.

- [ ] **Step 3: Add the `knowpost` listener and reverse-index-based cache invalidation**

Implementation notes:

- do not pretend comment has the same proven side path
- keep listener local, not Kafka-routed
- follow the `FeedCacheInvalidationListener` shape: local Spring `@EventListener` reacting to `CounterEvent`, not a Kafka, RabbitMQ, or trigger consumer
- the same successful toggle point that publishes Kafka `counter-events` must also publish the local Spring `CounterEvent`
- update `like_received` as a fast path; `rebuildAllCounters(userId)` can only best-effort correct it from retained interaction truth

- [ ] **Step 4: Run the listener and feed tests again**

Run:

```bash
mvn -pl nexus-app -Dtest=FeedHttpRealIntegrationTest test
```

Expected:
- PASS

- [ ] **Step 5: Commit**

```bash
git add nexus-app nexus-trigger nexus-infrastructure nexus-domain
git commit -m "feat: add knowpost local counter side effects"
```

## Chunk 5: Replace User Counter Snapshot And Relation Processing

### Task 8: Introduce the user counter service and demote old user port abstractions

**Files:**
- Create: Nexus-named user counter service interface under `nexus-domain` exposing `incrementFollowings/incrementFollowers/incrementPosts/incrementLikesReceived/rebuildAllCounters`; favorite methods remain out of scope until favorite support is introduced
- Create: Redis-backed user counter service implementation under `nexus-infrastructure`
- Delete: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/counter/port/UserCounterPort.java`
- Delete: `nexus-domain/src/main/java/cn/nexus/domain/counter/adapter/port/IUserCounterPort.java`
- Modify: repositories needed to count posts and sum owned object counters
- Test: replace port-centered tests with service-centered tests under `nexus-infrastructure/src/test/java/cn/nexus/infrastructure/adapter/counter/` or matching module package

- [ ] **Step 1: Write failing tests for `rebuildAllCounters(userId)`**

Cover:

- following and follower rebuild from relation truth tables
- post rebuild from published content rows
- like_received rebuild by summing owned object like counters as the Class 1 interaction-derived correction path
- favorite_received remains reserved and rebuild writes zero until favorite support is introduced
- malformed `ucnt` self-heals
- sampled verification inputs are compatible

- [ ] **Step 2: Run the tests and confirm failure**

Run:

```bash
mvn -pl nexus-infrastructure -Dtest=*UserCounter* test
```

Expected:
- FAIL because current user rebuild covers only relation-derived slots.

- [ ] **Step 3: Add the user counter service API**

Implementation notes:

- use Nexus module naming; class and package names do not need to include `Zhiguang`
- the public boundary must match `UserCounterServiceImpl` semantics, not a Nexus-only generic port shape
- expose `incrementFollowings/incrementFollowers/incrementPosts/incrementLikesReceived/incrementFavsReceived/rebuildAllCounters`
- delete old user counter port abstractions after migrating callers
- do not keep compatibility adapters or implement the replacement as a `UserCounterPort` rewrite with a service wrapper on top

- [ ] **Step 4: Replace user rebuild logic with mixed-source rebuild**

Implementation notes:

- add an explicit `rebuildAllCounters(userId)` path
- stop treating follow/follower as the only rebuildable slots
- keep SDS fixed-slot snapshot shape

- [ ] **Step 5: Run the focused tests again**

Run:

```bash
mvn -pl nexus-infrastructure -Dtest=*UserCounter* test
```

Expected:
- PASS

- [ ] **Step 6: Commit**

```bash
git add nexus-domain nexus-infrastructure
git commit -m "refactor: add user counter service"
```

### Task 9: Replace relation write-side processing with transactional outbox plus RabbitMQ idempotent projection

**Files:**
- Modify: `nexus-domain/src/main/java/cn/nexus/domain/social/service/RelationService.java`
- Modify or Create: outbox payload model files
- Modify or Create: RabbitMQ outbox publisher and relation projection consumer under `nexus-trigger`
- Delete or Rewrite: `nexus-trigger/src/main/java/cn/nexus/trigger/listener/social/RelationEventListener.java`
- Delete or avoid: Canal bridge and Kafka `canal-outbox` relation consumer files if they exist
- Modify: `nexus-infrastructure/src/main/resources/mapper/social/RelationEventOutboxMapper.xml`
- Modify or Create: persistent processed-event mapper/table support for DB-derived projections
- Modify: `nexus-infrastructure/src/main/resources/mapper/social/FollowerMapper.xml`
- Modify: relation repositories and cache ports under `nexus-infrastructure`
- Test: `nexus-domain/src/test/java/cn/nexus/domain/social/service/RelationServiceTest.java`
- Test: `nexus-app/src/test/java/cn/nexus/integration/relation/RelationHttpRealIntegrationTest.java`

- [ ] **Step 1: Write failing tests for the relation outbox chain**

Cover:

- follow and unfollow both map to relation outbox events delivered through RabbitMQ to the new relation processor
- business transaction writes following truth plus outbox
- write-side transaction does not directly write follower table, Redis ZSets, or `ucnt`
- no direct after-commit user-counter increment path remains
- remove the `FOLLOW_LIMIT` or equivalent write-blocking upper-limit contract; do not enforce it through async `ucnt` or relation truth table
- outbox publisher uses RabbitMQ publisher confirms before marking rows as sent
- consumer manually acknowledges only after persistent idempotent processing succeeds
- processor records processed event ids persistently and uses actual relation state transitions before updating follower table plus `uf:flws:*` plus `uf:fans:*` plus `ucnt`

- [ ] **Step 2: Run tests and confirm failure**

Run:

```bash
mvn -pl nexus-domain -Dtest=RelationServiceTest test
mvn -pl nexus-app -Dtest=RelationHttpRealIntegrationTest test
```

Expected:
- FAIL because current implementation still performs direct after-commit counter writes or lacks the new reliable RabbitMQ outbox projection path.

- [ ] **Step 3: Rewrite `RelationService` to stop direct counter writes**

Implementation notes:

- follow transaction writes following truth plus outbox only
- unfollow transaction updates following truth plus outbox only
- DB state changes and outbox events must commit in the same transaction; outbox insert failure must roll back the business state change
- transaction must not directly maintain follower table, Redis ZSets, or `ucnt`
- remove any `FOLLOW_LIMIT` check based on async user counters and update the API contract so follow/unfollow no longer has a write-blocking upper-limit rule
- remove `userCounterRepairOutboxRepository` counting semantics
- remove old after-commit `userCounterPort.increment(...)`

- [ ] **Step 4: Introduce RabbitMQ outbox publisher and projection consumer scaffolding**

Implementation notes:

- RabbitMQ exchange and queue names must reflect DB-derived relation counter projection, not object-like interaction aggregation
- publisher reads unsent outbox rows and marks them sent only after RabbitMQ publisher confirm
- consumer uses manual acknowledgment and acknowledges only after idempotent processor success
- failed messages retry and then move to DLQ rather than being silently swallowed
- DLQ messages must be replayable after the underlying bug or dependency outage is fixed
- consumer hands payload to a relation processor
- processor owns persistent idempotency and cache/count mutation

- [ ] **Step 5: Rewrite relation-side listener logic into an idempotent state-transition processor**

Implementation notes:

- persistent idempotency is keyed by relation outbox event id; Redis TTL dedup may be used only as a fast-path optimization
- consumer idempotency must be backed by a persistent processed-event table or by the current business table state; Redis TTL de-duplication is not a final correctness boundary
- for `FollowCreated`, call a repository method that reports whether follower state actually changed from inactive to active
- for `FollowCanceled`, call a repository method that reports whether follower state actually changed from active to inactive
- processor does not maintain the following truth table; that table is owned by the write-side transaction
- update adjacency ZSets `uf:flws:*` and `uf:fans:*` with a fixed 2-hour TTL
- update `ucnt` only when the follower state transition actually changes the derived count
- record idempotency success before acknowledging the RabbitMQ message
- keep unrelated feed/risk/notification side effects as separate downstream consumers or local listeners outside the relation count processor

- [ ] **Step 6: Run focused relation tests again**

Run:

```bash
mvn -pl nexus-domain -Dtest=RelationServiceTest test
mvn -pl nexus-app -Dtest=RelationHttpRealIntegrationTest test
```

Expected:
- PASS

- [ ] **Step 7: Commit**

```bash
git add nexus-domain/src/main/java/cn/nexus/domain/social/service/RelationService.java nexus-trigger nexus-infrastructure/src/main/resources/mapper/social nexus-domain/src/test/java/cn/nexus/domain/social/service/RelationServiceTest.java nexus-app/src/test/java/cn/nexus/integration/relation/RelationHttpRealIntegrationTest.java
git commit -m "refactor: replace relation counting with rabbitmq projection"
```

### Task 10: Align relation read/controller behavior with zhiguang sampled verification semantics

**Files:**
- Modify: relation controller and read assembly files under `nexus-app` and `nexus-api`
- Modify: read-side relation/query services under `nexus-domain`
- Modify: any infrastructure repository helpers needed by sampled verification
- Test: `nexus-app/src/test/java/cn/nexus/integration/relation/RelationHttpRealIntegrationTest.java`

- [ ] **Step 1: Write failing tests for relation read-path sampled verification**

Cover:

- controller/read path exposes `followings/followers/posts/likedPosts`; favorite-facing public fields remain out of scope until favorite is introduced
- missing `ucnt` or SDS length `<20` immediately triggers `rebuildAllCounters(userId)` and a second read; if still invalid, return all five fields as zero
- `ucnt:{userId}` is only a projection; missing, damaged, or suspected-drift values must be rebuilt from MySQL truth for Class 2 slots, while `like_received` can only be best-effort rebuilt from retained interaction truth when available
- malformed/missing `ucnt` rebuild path is not gated by `ucnt:chk:{userId}`
- read path uses `ucnt:chk:{userId}` throttling with a fixed 300-second window only for sampled verification
- sampled verification compares only relation truth `followings/followers` against SDS segments 1/2
- sampled verification does not compare `posts/likedPosts`
- mismatch against relation truth triggers `rebuildAllCounters(userId)`
- sampled verification sits in the relation controller or read-service boundary, not as a detached DTO-only concern

- [ ] **Step 2: Run the relation integration tests and confirm failure**

Run:

```bash
mvn -pl nexus-app -Dtest=RelationHttpRealIntegrationTest test
```

Expected:
- FAIL because current read path does not yet match zhiguang sampled verification shape.

- [ ] **Step 3: Rewrite relation read/controller assembly**

Implementation notes:

- keep internal slot semantics internal
- expose the public response names `followings/followers/posts/likedPosts`; do not expose favorite-facing fields in this change
- if `ucnt` is missing or shorter than 20 bytes, immediately call `rebuildAllCounters(userId)`, read again, and return five zeros if still invalid
- do not treat RabbitMQ backlog or missing projection messages as data loss while the MySQL truth and outbox rows are intact
- perform sampled verification in the controller or read-service boundary that owns the public relation count response
- use `ucnt:chk:{userId}` with a 300-second TTL only before comparing `followings/followers` against relation table counts
- do not compare `posts/likedPosts` during sampled verification

- [ ] **Step 4: Run the relation integration tests again**

Run:

```bash
mvn -pl nexus-app -Dtest=RelationHttpRealIntegrationTest test
```

Expected:
- PASS

- [ ] **Step 5: Commit**

```bash
git add nexus-app nexus-api nexus-domain nexus-infrastructure
git commit -m "refactor: align relation read path with zhiguang verification"
```

## Chunk 6: Remove Reply Persistence And Old Repair/Replay Contracts

### Task 11: Delete reply persistence from the counting subsystem

**Files:**
- Delete or Rewrite: `nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/RootReplyCountChangedConsumer.java`
- Modify: `nexus-domain/src/main/java/cn/nexus/domain/social/service/InteractionService.java`
- Modify: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/CommentRepository.java`
- Modify: `nexus-domain/src/main/java/cn/nexus/domain/counter/model/valobj/ObjectCounterType.java`
- Test: `nexus-trigger/src/test/java/cn/nexus/trigger/mq/consumer/RootReplyCountChangedConsumerTest.java`
- Test: comment query/integration tests

- [ ] **Step 1: Write failing tests that assert reply is no longer a counter capability**

Cover:

- no reply slot in object counter schema behavior
- no reply consumer updates count snapshots
- comment read paths no longer depend on counter reply fields
- new comment API contract removes `replyCount` from response DTOs and API responses
- do not return counter-backed `replyCount`
- do not derive `replyCount` from comment truth data in this count-system replacement

- [ ] **Step 2: Run tests and confirm failure**

Run:

```bash
mvn -pl nexus-trigger -Dtest=RootReplyCountChangedConsumerTest test
```

Expected:
- FAIL because reply consumer still exists.

- [ ] **Step 3: Delete reply persistence behavior**

Implementation notes:

- comment business remains
- remove `replyCount` from comment response DTOs and API response assembly
- reply counters must not be maintained through the count subsystem
- do not keep `replyCount=0` as a compatibility fallback
- remove any remaining reply-specific aggregation overlap logic

- [ ] **Step 4: Run comment-related tests**

Run:

```bash
mvn -pl nexus-domain -Dtest=CommentQueryServiceTest test
mvn -pl nexus-app -Dtest=CommentHttpRealIntegrationTest test
```

Expected:
- PASS with comment business intact and reply persistence removed from counting.

- [ ] **Step 5: Commit**

```bash
git add nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer nexus-domain/src/main/java/cn/nexus/domain/social/service/InteractionService.java nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/CommentRepository.java nexus-domain/src/main/java/cn/nexus/domain/counter/model/valobj/ObjectCounterType.java
git commit -m "refactor: remove reply persistence from counting"
```

### Task 12: Delete old replay, checkpoint, repair, and legacy RabbitMQ count-recovery contracts

**Files:**
- Delete or Rewrite: old trigger jobs and runners under `nexus-trigger/src/main/java/cn/nexus/trigger/job/social/*`
- Delete or Rewrite: old reaction event-log repositories and ports
- Delete or Rewrite: `UserCounterRepairOutboxRepository` and related domain interfaces if they remain count-only
- Delete or Rewrite: `CountRecoveryControlPort` and gap-log/recovery classes
- Preserve the new RabbitMQ DB-derived projection path introduced for relation, post, and retained business-truth counters
- Tests: old trigger/job/repository tests affected by deletions

- [ ] **Step 1: Write or update tests to assert dead paths are removed**

Cover:

- no count checkpoint job remains authoritative
- no reaction replay runner remains authoritative
- no user-counter repair job remains authoritative
- new RabbitMQ projection outbox, publisher, consumers, DLQ, and idempotency records are not treated as legacy recovery contracts

- [ ] **Step 2: Delete the legacy recovery files and tests**

Run:

```bash
rg -n "ReactionRedisRecoveryRunner|CountAofCheckpointJob|CountRdbCheckpointJob|UserCounterRepairJob|CountGap|ReactionEventLog" nexus-trigger nexus-domain nexus-infrastructure
```

Expected:
- remaining references are delete targets or rewrite targets.

- [ ] **Step 3: Re-run module tests most affected by deletions**

Run:

```bash
mvn -pl nexus-trigger,nexus-infrastructure,nexus-domain test
```

Expected:
- PASS with deleted legacy contracts removed from build and runtime wiring.

- [ ] **Step 4: Commit**

```bash
git add nexus-trigger nexus-domain nexus-infrastructure
git commit -m "refactor: remove legacy count recovery contracts"
```

## Chunk 7: Wire Post Count Writes And Public Read Contracts

### Task 13: Wire post publish state changes into the Class 2 outbox projection path

**Files:**
- Modify: `nexus-domain/src/main/java/cn/nexus/domain/social/service/ContentService.java`
- Modify: content counter outbox publisher/consumer or post-publish downstream files
- Test: content publish tests and feed distribution tests

- [ ] **Step 1: Write failing tests for post-count outbox projection**

Cover:

- successful publish state transition writes content truth and a post-counter outbox event in the same transaction
- update/delete/unpublish flows emit the correct outbox event only when published-state truth changes
- retry or duplicate publish handling does not double-project post count
- RabbitMQ projection updates `ucnt.post` only after persistent idempotency succeeds
- `ucnt.post` can be rebuilt from published content rows when the projection is missing, damaged, or suspected-drift

- [ ] **Step 2: Run tests and confirm failure**

Run:

```bash
mvn -pl nexus-domain -Dtest=ContentServiceTest,FeedDistributionServiceTest test
```

Expected:
- FAIL because post count is not yet wired to the Class 2 outbox projection semantics.

- [ ] **Step 3: Add the post-count outbox projection at the correct publish state transition point**

Implementation notes:

- do not directly increment `ucnt.post` on the publish request path outside the Class 2 outbox/projection contract
- content truth changes and post-counter outbox events must commit in the same transaction
- RabbitMQ publisher confirms are required before marking the outbox row sent
- consumer processing must be persistently idempotent before acknowledging the RabbitMQ message
- keep the anti-duplicate publish state-transition guard so retries or updates do not create duplicate outbox events

- [ ] **Step 4: Run tests again**

Run:

```bash
mvn -pl nexus-domain -Dtest=ContentServiceTest,FeedDistributionServiceTest test
```

Expected:
- PASS

- [ ] **Step 5: Commit**

```bash
git add nexus-domain/src/main/java/cn/nexus/domain/social/service/ContentService.java nexus-domain/src/test/java
git commit -m "feat: wire post publish into user post counter"
```

### Task 14: Align non-relation public read APIs with the new count contracts

**Files:**
- Modify: relation API DTOs and controllers under `nexus-api` and `nexus-app`
- Modify: comment and feed assembly code that reads counts
- Test: relation, feed, comment, interaction integration tests

- [ ] **Step 1: Write failing tests for public count fields outside the dedicated relation sampled-verification path**

Cover:

- `/api/v1/relation/counter` exposes only `followings/followers/posts/likedPosts` in this change
- feed/comment/post reads consume public fields and new object snapshots correctly
- non-relation pages do not expose internal slot names such as `like_received` or `favorite_received`
- no API still expects reply from persisted count subsystem

- [ ] **Step 2: Run tests and confirm failure**

Run:

```bash
mvn -pl nexus-app -Dtest=FeedHttpRealIntegrationTest,CommentHttpRealIntegrationTest,ReactionHttpRealIntegrationTest test
```

Expected:
- FAIL on stale old-field assumptions or removed reply count behavior.

- [ ] **Step 3: Rewrite read adapters and DTO assembly**

Implementation notes:

- internal slot names must remain internal only
- `/api/v1/relation/counter` is the public relation counter contract and uses `followings/followers/posts/likedPosts` in this change
- non-relation page assembly may consume those public fields but must not expose internal `like_received` or `favorite_received` names
- comment read path must no longer ask the counter subsystem for reply persistence
- relation public read behavior is handled in Task 10, not this task

- [ ] **Step 4: Run the focused integration tests again**

Run:

```bash
mvn -pl nexus-app -Dtest=FeedHttpRealIntegrationTest,CommentHttpRealIntegrationTest,ReactionHttpRealIntegrationTest test
```

Expected:
- PASS

- [ ] **Step 5: Commit**

```bash
git add nexus-api nexus-app nexus-domain nexus-infrastructure
git commit -m "refactor: align public count reads with zhiguang contracts"
```

## Chunk 8: Full Verification And Cleanup

### Task 15: Run final module and integration verification

**Files:**
- Verify only

- [ ] **Step 1: Run infrastructure and domain tests**

Run:

```bash
mvn -pl nexus-infrastructure,nexus-domain test
```

Expected:
- PASS

- [ ] **Step 2: Run trigger tests**

Run:

```bash
mvn -pl nexus-trigger test
```

Expected:
- PASS

- [ ] **Step 3: Run app integration tests for social flows**

Run:

```bash
mvn -pl nexus-app -Dtest=ReactionHttpRealIntegrationTest,RelationHttpRealIntegrationTest,FeedHttpRealIntegrationTest,CommentHttpRealIntegrationTest,PostContentKvRealIntegrationTest test
```

Expected:
- PASS

- [ ] **Step 4: Run targeted grep-based contract verification**

Run:

```bash
rg -n "COMMENT.reply|RootReplyCountChangedConsumer|UserCounterRepairJob|ReactionRedisRecoveryRunner|CountAofCheckpointJob|CountRdbCheckpointJob|PostLikeCountAggregateConsumer|relation.follow.queue" nexus-trigger nexus-domain nexus-infrastructure nexus-app
```

Expected:
- only historical docs or intentionally retained non-count business references remain

- [ ] **Step 5: Write a short completion note in the spec or follow-up docs if needed**

Record:

- final Kafka topics used for interaction counters
- final RabbitMQ exchanges, queues, retry, DLQ, publisher-confirm, and idempotency config points used for DB-derived projections
- confirmation that favorite remains reserved and inactive in this change
- confirmation that search-index count propagation was deleted

- [ ] **Step 6: Commit final verification cleanup**

```bash
git add .
git commit -m "test: verify zhiguang counter replacement end to end"
```
