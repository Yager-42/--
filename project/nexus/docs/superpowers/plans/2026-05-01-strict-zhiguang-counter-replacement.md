# Strict Zhiguang Counter Replacement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Nexus counters with the approved strict `zhiguang_be` counter model: post `like/fav`, user `followings/followers/posts/likesReceived/favsReceived`, and removal of every Nexus-only counter capability.

**Architecture:** Redis bitmap is the only truth source for post `like/fav` membership; Kafka `counter-events` accumulates object deltas into `agg:v1:post:{postId}` and flushes them into `cnt:v1:post:{postId}`. User counters live in `ucnt:{userId}`, normal paths update through `IUserCounterService`, and rebuild recomputes all five slots from relation/content facts plus post object counters. Old generic reaction counters, comment counters, search count propagation, Count Redis module paths, RabbitMQ object aggregation, and Nexus repair/projection tables are deleted instead of wrapped.

**Tech Stack:** Spring Boot, Java, Redis, Kafka, MyBatis, Maven multi-module build, JUnit 5, Mockito, existing Nexus HTTP/controller conventions.

---

## Source Of Truth

Implementation must follow `docs/superpowers/specs/2026-05-01-strict-zhiguang-counter-replacement-design.md`. That spec supersedes `docs/superpowers/specs/2026-04-23-replace-nexus-count-system-with-zhiguang-design.md` and its older implementation plan.

Binding interpretation:

- Strict zhiguang behavior wins over current Nexus behavior.
- Only active object counters are `POST.like` and `POST.fav`.
- Only active user counters are `followings`, `followers`, `posts`, `likesReceived`, and `favsReceived`.
- Removed behavior must disappear from active code, tests, schemas, runtime config, and API routes.
- Reserved SDS slots are schema compatibility only; they must not become product behavior.
- No generic reaction/counter platform is allowed as the replacement abstraction.

## Locked Implementation Decisions

These decisions remove freedom that would otherwise cause divergent implementations:

- **Object action result:** Create one domain result model, `PostActionResultVO`, with `changed`, `liked`, `faved`, `likeCount`, and `favoriteCount`. Every action response must populate both `liked` and `faved` from bitmap truth. Counts in this result are display snapshots and are allowed to lag bitmap truth.
- **Object service boundary:** `IObjectCounterService` exposes post-only concrete methods: `likePost`, `unlikePost`, `favPost`, `unfavPost`, `isPostLiked`, `isPostFaved`, `getPostCounts`, and `getPostCountsBatch`. It must not accept `ReactionTargetTypeEnumVO` at its public boundary after this replacement.
- **Post action domain boundary:** Create `IPostActionService` and `PostActionService`. Delete `IReactionLikeService` and `ReactionLikeService` in Task 6 after the new action callers compile. Do not keep a renamed wrapper that still accepts generic reaction target/type input.
- **Counter events:** `CounterDeltaEvent` and local `CounterEvent` use string target type `post`, `targetId`, `metric`, `slot`, `actorUserId`, `delta`, and event timestamp fields. They must not import or expose `ReactionTargetTypeEnumVO`.
- **Unsupported counter API input:** Unsupported action target types and unsupported counter metrics return `ResponseCode.ILLEGAL_PARAMETER`. Do not silently ignore unsupported metrics.
- **Bitmap shard discovery:** Rebuild discovers bitmap shards by Redis `SCAN` over `bm:{metric}:post:{postId}:*`. Do not maintain `bm:*:idx` shard-index sets.
- **Public user counter names:** `UserCounterType` enum identifiers and codes must align to `FOLLOWINGS("followings")`, `FOLLOWERS("followers")`, `POSTS("posts")`, `LIKES_RECEIVED("likesReceived")`, and `FAVS_RECEIVED("favsReceived")`. Remove old public concepts `FOLLOWING`, `FOLLOWER`, `POST`, `LIKE_RECEIVED`, `FAVORITE_RECEIVED`, and `likedPosts` from the counter boundary.
- **Notification cleanup:** `COMMENT_LIKED` is deleted as a business notification type. `LIKE_ADDED` remains only for post likes and must derive `POST_LIKED` only. Favoriting creates no notification in this replacement.
- **Comment storage cleanup:** Public comment APIs and repository models remove `likeCount`, `replyCount`, and `liked`. Final schemas and migrations drop `interaction_comment.like_count` and `interaction_comment.reply_count`.
- **Reaction event-log cleanup:** `interaction_reaction_event_log` and Java/MQ components named around `ReactionEventLog` are deleted from active counter implementation. Reliable MQ replay infrastructure remains only for non-counter MQ reliability and must not replay counter deltas.
- **Search current-user state:** Search continues to expose `liked/faved` current-user state by reading bitmap truth at query time, but it must not store or update count fields in search documents or mappings.

## File Responsibility Map

Counter contracts:

- Modify `nexus-domain/src/main/java/cn/nexus/domain/counter/adapter/service/IObjectCounterService.java` to expose concrete post `like/unlike/fav/unfav/isLiked/isFaved/getCounts/getCountsBatch` behavior.
- Modify `nexus-domain/src/main/java/cn/nexus/domain/counter/adapter/service/IUserCounterService.java` to expose `incrementFavsReceived` and public five-slot read behavior.
- Modify `nexus-domain/src/main/java/cn/nexus/domain/counter/model/valobj/ObjectCounterType.java` to contain active metrics `LIKE("like")` and `FAV("fav")`.
- Modify `nexus-domain/src/main/java/cn/nexus/domain/counter/model/valobj/UserCounterType.java` to use the five locked enum identifiers and public codes from Locked Implementation Decisions.
- Modify `nexus-domain/src/main/java/cn/nexus/domain/counter/model/event/CounterDeltaEvent.java` and `CounterEvent.java` to remove `ReactionTargetTypeEnumVO` imports and carry only post `like/fav` deltas.
- Create `nexus-domain/src/main/java/cn/nexus/domain/social/model/valobj/PostActionResultVO.java`.
- Create `nexus-domain/src/main/java/cn/nexus/domain/social/service/IPostActionService.java`.
- Create `nexus-domain/src/main/java/cn/nexus/domain/social/service/PostActionService.java`.
- Delete `nexus-domain/src/main/java/cn/nexus/domain/social/service/IReactionLikeService.java`.
- Delete `nexus-domain/src/main/java/cn/nexus/domain/social/service/ReactionLikeService.java`.

Redis counter infrastructure:

- Modify `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/counter/support/CountRedisSchema.java` for one object schema and one user schema.
- Modify `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/counter/support/CountRedisKeys.java` for exact zhiguang key families.
- Modify `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/counter/support/CountRedisOperations.java` and `CountRedisCodec.java` for fixed SDS reads/writes and bounded increments.
- Modify `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/counter/service/ObjectCounterService.java` for bitmap truth, object reads, single-object rebuild, and event emission.
- Modify `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/counter/service/UserCounterService.java` for five-slot reads, normal increments, and full rebuild.
- Modify `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/counter/kafka/CounterEventProducer.java` only to keep Kafka event emission aligned with the revised event model.

Aggregation:

- Modify `nexus-trigger/src/main/java/cn/nexus/trigger/counter/CounterAggregationConsumer.java` to accept only post `LIKE/FAV`, write single `agg:v1:post:{postId}`, and flush into `cnt:v1:post:{postId}`.

HTTP/API:

- Create `nexus-api/src/main/java/cn/nexus/api/social/action/dto/ActionRequestDTO.java`.
- Create `nexus-api/src/main/java/cn/nexus/api/social/action/dto/ActionResponseDTO.java`.
- Create `nexus-api/src/main/java/cn/nexus/api/social/counter/dto/PostCounterResponseDTO.java`.
- Create `nexus-trigger/src/main/java/cn/nexus/trigger/http/social/ActionController.java`.
- Create `nexus-trigger/src/main/java/cn/nexus/trigger/http/social/CounterController.java`.
- Delete reaction DTO/API types that remain solely for the removed counter endpoint after Task 5 route removal.
- Modify `nexus-trigger/src/main/java/cn/nexus/trigger/http/social/InteractionController.java` to remove `/api/v1/interact/reaction` and `/api/v1/interact/reaction/state`; keep comment and notification APIs.

Post display:

- Modify `nexus-api/src/main/java/cn/nexus/api/social/content/dto/ContentDetailResponseDTO.java` to include `favoriteCount`, `liked`, and `faved`.
- Modify feed/list/detail DTOs that already expose post cards so every post card can carry `likeCount`, `favoriteCount`, `liked`, and `faved`.
- Modify `nexus-trigger/src/main/java/cn/nexus/trigger/http/social/support/ContentDetailQueryService.java` to read both metrics and current-user states from `IObjectCounterService`.
- Modify feed/card assembly services that currently read only like count to read `LIKE/FAV` in one batch call.

User display:

- Modify `nexus-domain/src/main/java/cn/nexus/domain/counter/model/valobj/UserRelationCounterVO.java` to expose `likesReceived` and `favsReceived`, not `likedPosts` as a counter concept.
- Modify `nexus-api/src/main/java/cn/nexus/api/social/relation/dto/RelationCounterResponseDTO.java` to expose `followings`, `followers`, `posts`, `likesReceived`, `favsReceived`.
- Modify relation/profile controllers and query services that map user counters.

Business side effects:

- Modify `nexus-domain/src/main/java/cn/nexus/domain/social/service/KnowpostCounterSideEffectListener.java` to handle post `LIKE` and post `FAV` local events and increment the matching user received counter.
- Modify `nexus-domain/src/main/java/cn/nexus/domain/social/service/ContentService.java` so post-count deltas are emitted only on real published-state edges without `post_counter_projection`.
- Modify `nexus-domain/src/main/java/cn/nexus/domain/social/service/RelationCounterProjectionProcessor.java` only to keep relation-to-`ucnt` updates; remove repair-outbox assumptions.

Delete from active counter system:

- Delete `count-redis-module/`.
- Delete `nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/CommentLikeChangedConsumer.java`.
- Delete `nexus-domain/src/main/java/cn/nexus/domain/social/adapter/port/IReactionCommentLikeChangedMqPort.java`.
- Delete `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/ReactionCommentLikeChangedMqPort.java`.
- Delete `nexus-domain/src/main/java/cn/nexus/domain/social/adapter/repository/IPostCounterProjectionRepository.java`.
- Delete `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/PostCounterProjectionRepository.java`.
- Delete `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/dao/social/po/PostCounterProjectionPO.java`.
- Delete `nexus-infrastructure/src/main/resources/mapper/social/PostCounterProjectionMapper.xml`.
- Delete active `ReactionEventLog` models, ports, repositories, mapper XML, MQ config, and recovery runner files discovered by the boundary test.
- Delete `docs/migrations/20260428_01_add_post_counter_projection.sql` after adding a replacement drop migration.
- Delete `docs/migrations/20260410_01_user_counter_repair_outbox.sql` after adding a replacement drop migration.

Schema/docs:

- Modify `docs/nexus_final_mysql_schema.sql` and `docs/social_schema.sql` so final schema does not create removed counter tables.
- Create a new migration under `docs/migrations/` that explicitly drops removed counter tables or fields where they exist.
- Modify `docs/frontend-api.md` only after controllers/DTOs settle, removing old reaction counter endpoints and documenting `/api/v1/action/*` plus `/api/v1/counter/post/{postId}`.
- Do not treat historical OpenSpec archives, old superpowers plans, analytics examples, interview notes, or the pre-existing untracked `docs/counter-system/` walkthrough as active implementation contracts.

Tests:

- Add a static boundary test package under `nexus-app/src/test/java/cn/nexus/contract/counter/` for removed references and routes.
- Update focused unit tests under `nexus-domain`, `nexus-infrastructure`, and `nexus-trigger`.
- Update real integration tests only after unit/contract tests lock behavior.

## Boundary Invariants

These invariants are not optional and must be encoded in tests before implementation:

- No active Java/XML/schema/config code references `post_counter_projection`.
- No active Java/XML/schema/config code references `user_counter_repair_outbox`.
- No active Java code accepts `COMMENT` in object counter writes, reads, rebuild, aggregation, or counter HTTP actions.
- No controller maps `/api/v1/interact/reaction` or `/api/v1/interact/reaction/state`.
- No counter code builds aggregation keys shaped like `agg:v1:post:{postId}:{shard}`.
- No counter code writes `cnt:v1:comment:*` or `bm:*:comment:*`.
- No counter code consults MySQL reaction rows/logs to rebuild post object counters.
- No counter code references `interaction_reaction_event_log`, `ReactionEventLog`, `bm:like:post:*:idx`, or `likeBitmapShardIndex`.
- No active notification code derives or stores `COMMENT_LIKED`.
- No public comment DTO/model/mapper exposes `like_count`, `reply_count`, `likeCount`, `replyCount`, or `liked`.
- No search upsert or search document assembly receives count deltas from the counter system.
- No build, Docker, or runtime config loads `count-redis-module`.

## Task 1: Add Static Boundary Tests First

**Files:**
- Create: `nexus-app/src/test/java/cn/nexus/contract/counter/StrictZhiguangCounterBoundaryTest.java`
- Modify after test failure: none in this task

- [x] **Step 1: Write the failing boundary test**

The test must recursively scan these active paths:

- `nexus-api/src/main/java`
- `nexus-domain/src/main/java`
- `nexus-infrastructure/src/main/java`
- `nexus-infrastructure/src/main/resources/mapper`
- `nexus-trigger/src/main/java`
- `nexus-app/src/main/java`
- `nexus-app/src/main/resources`
- `docs/nexus_final_mysql_schema.sql`
- `docs/social_schema.sql`
- `docs/frontend-api.md`
- `Dockerfile`
- all `pom.xml` files

The test must fail on these forbidden patterns:

- `post_counter_projection`
- `user_counter_repair_outbox`
- `/interact/reaction`
- `CommentLikeChangedConsumer`
- `IReactionCommentLikeChangedMqPort`
- `ReactionCommentLikeChangedMqPort`
- `count-redis-module`
- `interaction_reaction_event_log`
- `ReactionEventLog`
- `likeBitmapShardIndex`
- `bm:like:post:[^\\s\"']+:idx`
- `agg:v1:post:[^\\s\"']+:[0-9]+` in active code
- `cnt:v1:comment`
- `bm:like:comment`
- `bm:fav:comment`
- `COMMENT_LIKED`
- `like_count`
- `reply_count`
- `ReactionTargetTypeEnumVO.COMMENT` when the line also contains `counter`, `Counter`, `like`, `Like`, `fav`, `Fav`, `aggregation`, or `Aggregation`

Allowed files:

- `docs/superpowers/specs/**`
- `docs/superpowers/plans/**`
- historical analytics docs under `docs/analytics/**`
- historical/reference docs outside `docs/frontend-api.md`, `docs/nexus_final_mysql_schema.sql`, `docs/social_schema.sql`, and `docs/migrations/**`
- newly created drop migrations that mention removed table names

- [x] **Step 2: Run the boundary test and confirm it fails**

Run:

```bash
mvn -pl nexus-app -Dtest=StrictZhiguangCounterBoundaryTest test
```

Expected: FAIL with existing references to old reaction routes, comment counter paths, sharded aggregation, projection/repair tables, and Count Redis module.

- [x] **Step 3: Commit the failing boundary test**

Run:

```bash
git add nexus-app/src/test/java/cn/nexus/contract/counter/StrictZhiguangCounterBoundaryTest.java
git commit -m "test: lock strict zhiguang counter boundaries"
```

## Task 2: Rewrite Counter Types, Slots, And Keys

**Files:**
- Modify: `nexus-domain/src/main/java/cn/nexus/domain/counter/model/valobj/ObjectCounterType.java`
- Modify: `nexus-domain/src/main/java/cn/nexus/domain/counter/model/valobj/UserCounterType.java`
- Modify: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/counter/support/CountRedisSchema.java`
- Modify: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/counter/support/CountRedisKeys.java`
- Modify: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/counter/support/CountRedisOperations.java`
- Modify: `nexus-infrastructure/src/test/java/cn/nexus/infrastructure/adapter/counter/support/CountRedisSchemaSupportTest.java`
- Modify: `nexus-infrastructure/src/test/java/cn/nexus/infrastructure/adapter/counter/support/CountRedisOperationsTest.java`

- [x] **Step 1: Replace schema tests with strict zhiguang assertions**

Required assertions:

- object schema exists only for `POST`
- object ordered fields are exactly `read`, `like`, `fav`, `comment`, `repost`
- object active slots are `LIKE -> 1` and `FAV -> 2`
- object reserved slots `0`, `3`, and `4` have field names but no active business enum
- `CountRedisSchema.forObject(COMMENT)` returns `null`
- user logical indexes are `FOLLOWINGS -> 1`, `FOLLOWERS -> 2`, `POSTS -> 3`, `LIKES_RECEIVED -> 4`, `FAVS_RECEIVED -> 5`
- user public codes are exactly `followings`, `followers`, `posts`, `likesReceived`, `favsReceived`
- user key is `ucnt:{userId}`
- object snapshot key is `cnt:v1:post:{postId}`
- object aggregation key is exactly `agg:v1:post:{postId}`
- bitmap keys are `bm:like:post:{postId}:{chunk}` and `bm:fav:post:{postId}:{chunk}`
- no helper returns `agg:v1:post:{postId}:{shard}`

- [x] **Step 2: Run schema tests and confirm failure**

Run:

```bash
mvn -pl nexus-infrastructure -Dtest=CountRedisSchemaSupportTest,CountRedisOperationsTest test
```

Expected: FAIL because current code has only `LIKE`, still exposes comment object schema, and still has sharded aggregation helpers.

- [x] **Step 3: Implement the schema/key rewrite**

Rules:

- `ObjectCounterType` contains only `LIKE("like")` and `FAV("fav")`.
- `UserCounterType` identifiers and codes use exactly the locked names. All call sites must be changed in this task.
- `CountRedisSchema.forObject` must not provide active comment counter schema.
- `CountRedisKeys.objectAggregationBucket(POST, postId)` returns `agg:v1:post:{postId}` and there is no public overload that accepts a shard.
- `CountRedisKeys.bitmapShard(metric, POST, postId, chunk)` is the general helper for `LIKE` and `FAV`; do not keep like-only helpers as the only path.
- Remove `likeBitmapShardIndex`; rebuild must use `SCAN` and must not maintain bitmap shard-index sets.
- Relation cache helpers remain `uf:flws:{userId}` and `uf:fans:{userId}`.

- [x] **Step 4: Run schema tests until they pass**

Run:

```bash
mvn -pl nexus-infrastructure -Dtest=CountRedisSchemaSupportTest,CountRedisOperationsTest test
```

Expected: PASS.

- [x] **Step 5: Commit**

Run:

```bash
git add nexus-domain/src/main/java/cn/nexus/domain/counter/model/valobj nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/counter/support nexus-infrastructure/src/test/java/cn/nexus/infrastructure/adapter/counter/support
git commit -m "refactor: align counter schema with strict zhiguang slots"
```

## Task 3: Implement Post Like/Fav Object Counter Service

**Files:**
- Modify: `nexus-domain/src/main/java/cn/nexus/domain/counter/adapter/service/IObjectCounterService.java`
- Create: `nexus-domain/src/main/java/cn/nexus/domain/social/model/valobj/PostActionResultVO.java`
- Modify: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/counter/service/ObjectCounterService.java`
- Modify: `nexus-infrastructure/src/test/java/cn/nexus/infrastructure/adapter/counter/service/ObjectCounterServiceTest.java`
- Modify: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/counter/kafka/CounterEventProducer.java`
- Modify: `nexus-infrastructure/src/test/java/cn/nexus/infrastructure/adapter/counter/kafka/CounterEventProducerTest.java`

- [x] **Step 1: Replace object service tests with like/fav behavior**

Required test cases:

- `like(POST, postId, userId)` sets `bm:like:post:{postId}:{chunk}`, emits one Kafka delta and one local event when state changes.
- duplicate `like` returns `changed=false` and emits no event.
- `unlike` clears the same bitmap and emits `delta=-1` only when state changes.
- `fav` and `unfav` mirror like behavior using `bm:fav:post:{postId}:{chunk}` and slot `2`.
- `isLiked` and `isFaved` read only bitmap state.
- service methods are named `likePost/unlikePost/favPost/unfavPost/isPostLiked/isPostFaved/getPostCounts/getPostCountsBatch`.
- public object counter service methods do not accept `ReactionTargetTypeEnumVO`.
- null IDs or target type other than `POST` fail before touching Redis or emitting events.
- single-object `getCounts(POST, postId, [LIKE,FAV])` rebuilds malformed/missing SDS from bitmap shards.
- batch `getCountsBatch` returns zero for malformed/missing SDS and does not rebuild.
- rebuild writes all five object slots and clears only fields `1` and/or `2` in `agg:v1:post:{postId}`.

- [x] **Step 2: Run object service tests and confirm failure**

Run:

```bash
mvn -pl nexus-infrastructure -Dtest=ObjectCounterServiceTest,CounterEventProducerTest test
```

Expected: FAIL because fav is missing, comment is still accepted, and aggregation cleanup still assumes shards.

- [x] **Step 3: Implement concrete service methods**

Rules:

- Toggle methods return `PostActionResultVO`; read methods return post metric maps keyed only by `like` and `fav`.
- The only accepted object target is `POST`.
- Changed bitmap transitions emit Kafka `CounterDeltaEvent` and local Spring `CounterEvent` with target type string `post`, metric `like/fav`, and slot `1/2`.
- Counter event classes must not import `ReactionTargetTypeEnumVO`.
- No-op transitions emit neither Kafka nor local events.
- Object rebuild scans `bm:{metric}:post:{postId}:*` with `SCAN` semantics, not `KEYS` and not a MySQL/log source.
- Rebuild guard/backoff semantics remain: failed/contended rebuild returns zero rather than failing feed APIs.
- Do not preserve like-only helpers as the controller path for fav.

- [x] **Step 4: Run object service tests until they pass**

Run:

```bash
mvn -pl nexus-infrastructure -Dtest=ObjectCounterServiceTest,CounterEventProducerTest test
```

Expected: PASS.

- [x] **Step 5: Commit**

Run:

```bash
git add nexus-domain/src/main/java/cn/nexus/domain/counter/adapter/service/IObjectCounterService.java nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/counter/service/ObjectCounterService.java nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/counter/kafka/CounterEventProducer.java nexus-infrastructure/src/test/java/cn/nexus/infrastructure/adapter/counter/service/ObjectCounterServiceTest.java nexus-infrastructure/src/test/java/cn/nexus/infrastructure/adapter/counter/kafka/CounterEventProducerTest.java
git commit -m "feat: implement strict post like fav counters"
```

## Task 4: Replace Aggregation With Single Zhiguang Agg Key

**Files:**
- Modify: `nexus-trigger/src/main/java/cn/nexus/trigger/counter/CounterAggregationConsumer.java`
- Modify: `nexus-trigger/src/test/java/cn/nexus/trigger/counter/CounterAggregationConsumerTest.java`

- [x] **Step 1: Write aggregation tests**

Required cases:

- POST `LIKE` event increments hash field `1` on `agg:v1:post:{postId}`.
- POST `FAV` event increments hash field `2` on `agg:v1:post:{postId}`.
- COMMENT event is ignored.
- unknown metric is ignored.
- flush drains fields from `agg:v1:post:{postId}` and increments slots in `cnt:v1:post:{postId}`.
- failed slot increment leaves remaining hash value retryable.
- bucket parser rejects keys with a shard suffix.

- [x] **Step 2: Run aggregation tests and confirm failure**

Run:

```bash
mvn -pl nexus-trigger -Dtest=CounterAggregationConsumerTest test
```

Expected: FAIL because current consumer writes sharded buckets and accepts comment like events.

- [x] **Step 3: Implement single-key aggregation**

Rules:

- Remove `AGG_SHARDS` and user-id shard selection.
- Active index stores exact bucket names `agg:v1:post:{postId}`.
- Event validation allows only `entityType=POST`, `metric=LIKE/FAV`, `idx=1/2`, and nonzero delta.
- Event validation uses the new string target type field and accepts only `post`.
- Flush validates field names against active object slots only.
- No RabbitMQ object aggregation path may remain as a fallback.

- [x] **Step 4: Run aggregation tests until they pass**

Run:

```bash
mvn -pl nexus-trigger -Dtest=CounterAggregationConsumerTest test
```

Expected: PASS.

- [x] **Step 5: Commit**

Run:

```bash
git add nexus-trigger/src/main/java/cn/nexus/trigger/counter/CounterAggregationConsumer.java nexus-trigger/src/test/java/cn/nexus/trigger/counter/CounterAggregationConsumerTest.java
git commit -m "refactor: use single zhiguang object aggregation key"
```

## Task 5: Add Zhiguang Action And Counter APIs, Remove Reaction Counter Routes

**Files:**
- Create: `nexus-api/src/main/java/cn/nexus/api/social/action/dto/ActionRequestDTO.java`
- Create: `nexus-api/src/main/java/cn/nexus/api/social/action/dto/ActionResponseDTO.java`
- Create: `nexus-api/src/main/java/cn/nexus/api/social/counter/dto/PostCounterResponseDTO.java`
- Create: `nexus-trigger/src/main/java/cn/nexus/trigger/http/social/ActionController.java`
- Create: `nexus-trigger/src/main/java/cn/nexus/trigger/http/social/CounterController.java`
- Modify: `nexus-trigger/src/main/java/cn/nexus/trigger/http/social/InteractionController.java`
- Modify: `nexus-api/src/main/java/cn/nexus/api/social/IInteractionApi.java`
- Modify: `nexus-trigger/src/test/java/cn/nexus/trigger/http/social/InteractionControllerTest.java`
- Create: `nexus-trigger/src/test/java/cn/nexus/trigger/http/social/ActionControllerTest.java`
- Create: `nexus-trigger/src/test/java/cn/nexus/trigger/http/social/CounterControllerTest.java`

- [x] **Step 1: Write API tests**

Required cases:

- `POST /api/v1/action/like` accepts only target type `post`.
- `POST /api/v1/action/unlike` returns current `liked=false`.
- `POST /api/v1/action/fav` returns current `faved=true`.
- `POST /api/v1/action/unfav` returns current `faved=false`.
- duplicate action returns `changed=false` and success.
- unsupported target type returns parameter error before service call.
- `GET /api/v1/counter/post/{postId}?metrics=like,fav` returns only requested active metrics.
- unsupported metric on `GET /api/v1/counter/post/{postId}` returns `ResponseCode.ILLEGAL_PARAMETER`.
- `/api/v1/interact/reaction` and `/api/v1/interact/reaction/state` have no controller mappings.

- [x] **Step 2: Run API tests and confirm failure**

Run:

```bash
mvn -pl nexus-trigger -Dtest=ActionControllerTest,CounterControllerTest,InteractionControllerTest test
```

Expected: FAIL because new controllers do not exist and old reaction routes still exist.

- [x] **Step 3: Implement action/counter controllers**

Rules:

- Request target type is a string and only `post` is accepted.
- Do not expose `ReactionTypeEnumVO` or generic reaction model in new DTOs.
- Controller maps action verbs directly to service methods.
- `ActionRequestDTO` fields are exactly `targetType`, `targetId`, and optional `requestId`.
- `ActionResponseDTO` fields are exactly `changed`, `liked`, `faved`, `likeCount`, and `favoriteCount`.
- `PostCounterResponseDTO` contains `postId` plus a `counts` map whose keys can only be `like` and `fav`.
- Counter read endpoint accepts only `like` and `fav`; unsupported metrics return `ResponseCode.ILLEGAL_PARAMETER`.

- [x] **Step 4: Remove old reaction counter routes**

Rules:

- Delete the route methods from `InteractionController`.
- Remove `IInteractionApi` method declarations if they only describe the deleted routes.
- Delete `ReactionRequestDTO`, `ReactionResponseDTO`, `ReactionStateRequestDTO`, and `ReactionStateResponseDTO` when their only callers are removed. If compilation reveals a non-counter caller, move that caller to the new action DTOs in this task.

- [x] **Step 5: Run API tests until they pass**

Run:

```bash
mvn -pl nexus-trigger -Dtest=ActionControllerTest,CounterControllerTest,InteractionControllerTest test
```

Expected: PASS.

- [x] **Step 6: Commit**

Run:

```bash
git add nexus-api/src/main/java/cn/nexus/api/social nexus-trigger/src/main/java/cn/nexus/trigger/http/social nexus-trigger/src/test/java/cn/nexus/trigger/http/social
git commit -m "feat: add zhiguang action and post counter APIs"
```

## Task 6: Replace ReactionLikeService With Post Action Domain Path

**Files:**
- Delete: `nexus-domain/src/main/java/cn/nexus/domain/social/service/ReactionLikeService.java`
- Delete: `nexus-domain/src/main/java/cn/nexus/domain/social/service/IReactionLikeService.java`
- Create: `nexus-domain/src/main/java/cn/nexus/domain/social/service/PostActionService.java`
- Create: `nexus-domain/src/main/java/cn/nexus/domain/social/service/IPostActionService.java`
- Modify: `nexus-domain/src/main/java/cn/nexus/domain/social/service/InteractionService.java`
- Delete: `nexus-domain/src/test/java/cn/nexus/domain/social/service/ReactionLikeServiceTest.java`
- Create: `nexus-domain/src/test/java/cn/nexus/domain/social/service/PostActionServiceTest.java`
- Modify: `nexus-domain/src/test/java/cn/nexus/domain/social/service/InteractionServiceTest.java`

- [x] **Step 1: Write domain tests for removed generic reaction behavior**

Required cases:

- comment like is rejected or no longer has an entry point.
- post like/unlike delegates to `IObjectCounterService` only.
- post fav/unfav delegates to `IObjectCounterService` only.
- no comment-like MQ port is called.
- no `COMMENT_LIKED` notification event is produced.
- post like notification and recommend unlike side effects remain only for post `LIKE`, not for `FAV`.

- [x] **Step 2: Run domain tests and confirm failure**

Run:

```bash
mvn -pl nexus-domain -Dtest=PostActionServiceTest,InteractionServiceTest test
```

Expected: FAIL because current service accepts comment likes and has comment-like side effects.

- [x] **Step 3: Rewrite the domain path**

Rules:

- Create `PostActionService`; do not keep `ReactionLikeService`.
- New domain boundary must know only `like/unlike/fav/unfav` for posts.
- Delete dependencies on `ICommentRepository` and `IReactionCommentLikeChangedMqPort` from the action path.
- Remove `COMMENT_LIKED` title/content mapping and `InteractionNotifyConsumer` derivation.
- Do not keep a disabled branch for comment likes.
- Keep comment creation/listing/mention/reply notifications in `InteractionService`; they are not counter behavior.

- [x] **Step 4: Run domain tests until they pass**

Run:

```bash
mvn -pl nexus-domain -Dtest=PostActionServiceTest,InteractionServiceTest test
```

Expected: PASS.

- [x] **Step 5: Commit**

Run:

```bash
git add nexus-domain/src/main/java/cn/nexus/domain/social/service nexus-domain/src/test/java/cn/nexus/domain/social/service
git commit -m "refactor: remove generic reaction counter domain path"
```

## Task 7: Add Favorite Counts And State To Post Display

**Files:**
- Modify: `nexus-api/src/main/java/cn/nexus/api/social/content/dto/ContentDetailResponseDTO.java`
- Modify: `nexus-api/src/main/java/cn/nexus/api/social/feed/dto/FeedItemDTO.java`
- Modify: `nexus-api/src/main/java/cn/nexus/api/social/search/dto/SearchItemDTO.java` only for current-user `liked/faved` state; do not add count fields to search DTOs.
- Modify: `nexus-domain/src/main/java/cn/nexus/domain/social/model/valobj/FeedItemVO.java`
- Modify: `nexus-domain/src/main/java/cn/nexus/domain/social/model/valobj/SearchResultVO.java` only for current-user `liked/faved` state; do not add count fields to search VOs.
- Modify: `nexus-trigger/src/main/java/cn/nexus/trigger/http/social/support/ContentDetailQueryService.java`
- Modify: `nexus-domain/src/main/java/cn/nexus/domain/social/service/FeedCardAssembleService.java`
- Modify: `nexus-domain/src/main/java/cn/nexus/domain/social/service/SearchService.java` only for `faved` state lookup if search results keep current-user state.
- Modify: `nexus-trigger/src/test/java/cn/nexus/trigger/http/social/support/ContentDetailQueryServiceTest.java`
- Modify: `nexus-domain/src/test/java/cn/nexus/domain/social/service/FeedCardAssembleServiceTest.java`
- Modify: `nexus-domain/src/test/java/cn/nexus/domain/social/service/SearchServiceTest.java`.

- [x] **Step 1: Write display tests**

Required cases:

- content detail includes `likeCount`, `favoriteCount`, `liked`, and `faved`.
- feed cards include the same fields where post interaction state is currently assembled.
- counters are read with metrics `[LIKE,FAV]`, not two independent single-metric calls when a batch is already available.
- current-user states use bitmap methods `isLiked/isFaved`.
- search result `liked/faved` state is read from bitmap truth at query time and never from indexed document fields.
- unauthenticated or missing current-user context returns `liked=false` and `faved=false` without failing public reads.

- [x] **Step 2: Run display tests and confirm failure**

Run:

```bash
mvn -pl nexus-trigger -Dtest=ContentDetailQueryServiceTest test
mvn -pl nexus-domain -Dtest=FeedCardAssembleServiceTest,SearchServiceTest test
```

Expected: FAIL because fav count/state is not assembled.

- [x] **Step 3: Implement DTO and assembler changes**

Rules:

- Use public names `favoriteCount` and `faved` at post DTO boundary.
- Map `favoriteCount` to object metric `fav`.
- Do not add `favCount` as a third synonym.
- Do not add comment count fields while editing display DTOs.

- [x] **Step 4: Run display tests until they pass**

Run:

```bash
mvn -pl nexus-trigger -Dtest=ContentDetailQueryServiceTest test
mvn -pl nexus-domain -Dtest=FeedCardAssembleServiceTest,SearchServiceTest test
```

Expected: PASS.

- [x] **Step 5: Commit**

Run:

```bash
git add nexus-api/src/main/java/cn/nexus/api/social nexus-domain/src/main/java/cn/nexus/domain/social/service nexus-trigger/src/main/java/cn/nexus/trigger/http/social/support nexus-domain/src/test/java/cn/nexus/domain/social/service nexus-trigger/src/test/java/cn/nexus/trigger/http/social/support
git commit -m "feat: expose post favorite count and state"
```

## Task 8: Implement Five-Slot User Counters And Rebuild

**Files:**
- Modify: `nexus-domain/src/main/java/cn/nexus/domain/counter/adapter/service/IUserCounterService.java`
- Modify: `nexus-domain/src/main/java/cn/nexus/domain/counter/model/valobj/UserRelationCounterVO.java`
- Modify: `nexus-api/src/main/java/cn/nexus/api/social/relation/dto/RelationCounterResponseDTO.java`
- Modify: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/counter/service/UserCounterService.java`
- Modify: `nexus-infrastructure/src/test/java/cn/nexus/infrastructure/adapter/counter/service/UserCounterServiceTest.java`
- Modify: user/relation/profile controller tests that assert counter fields

- [x] **Step 1: Rewrite user counter tests**

Required cases:

- normal increments update exactly slots `1..5`.
- `incrementFavsReceived` exists and updates slot `5`.
- public counter response exposes `likesReceived` and `favsReceived`.
- `UserRelationCounterVO` has no `likedPosts` field.
- `RelationCounterResponseDTO` has no `likedPosts` field.
- malformed `ucnt` rebuilds all five values.
- rebuild recomputes `likesReceived` by summing owned published posts' `LIKE` object counts.
- rebuild recomputes `favsReceived` by summing owned published posts' `FAV` object counts.
- rebuild does not preserve stale previous received-counter values.
- child object counter read failure contributes zero and does not fail the user profile API.

- [x] **Step 2: Run user counter tests and confirm failure**

Run:

```bash
mvn -pl nexus-infrastructure -Dtest=UserCounterServiceTest test
```

Expected: FAIL because favorite received is currently zeroed and like received can be preserved.

- [x] **Step 3: Implement user counter service changes**

Rules:

- Public names are `followings`, `followers`, `posts`, `likesReceived`, `favsReceived`.
- Remove `likedPosts` from VO/DTO/controller mappings.
- The rebuild method writes all five slots in one SDS payload.
- Rebuild uses relation repository for follow slots, content repository for published posts, and object counter service for post `LIKE/FAV`.
- Do not use `user_counter_repair_outbox` or any replacement repair-outbox table.
- Do not directly manipulate Redis strings from relation/content services; use `IUserCounterService`.

- [x] **Step 4: Run user counter tests until they pass**

Run:

```bash
mvn -pl nexus-infrastructure -Dtest=UserCounterServiceTest test
```

Expected: PASS.

- [x] **Step 5: Commit**

Run:

```bash
git add nexus-domain/src/main/java/cn/nexus/domain/counter nexus-api/src/main/java/cn/nexus/api/social/relation/dto/RelationCounterResponseDTO.java nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/counter/service/UserCounterService.java nexus-infrastructure/src/test/java/cn/nexus/infrastructure/adapter/counter/service/UserCounterServiceTest.java
git commit -m "feat: implement zhiguang five slot user counters"
```

## Task 9: Add Fav Received And Feed Side Effects

**Files:**
- Modify: `nexus-domain/src/main/java/cn/nexus/domain/social/service/KnowpostCounterSideEffectListener.java`
- Modify: `nexus-domain/src/test/java/cn/nexus/domain/social/service/KnowpostCounterSideEffectListenerTest.java`
- Modify: `nexus-domain/src/main/java/cn/nexus/domain/social/adapter/port/IFeedCounterSideEffectPort.java`
- Modify: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/FeedCounterSideEffectPort.java`
- Modify: `nexus-infrastructure/src/test/java/cn/nexus/infrastructure/adapter/social/port/FeedCounterSideEffectPortTest.java`

- [x] **Step 1: Write side-effect tests**

Required cases:

- local post `LIKE` delta increments/decrements `likesReceived`.
- local post `FAV` delta increments/decrements `favsReceived`.
- non-post events are ignored.
- zero delta is ignored.
- feed side-effect cache update is best effort and non-authoritative.
- failure in feed side effects does not roll back or fail user counter update.

- [x] **Step 2: Run side-effect tests and confirm failure**

Run:

```bash
mvn -pl nexus-domain -Dtest=KnowpostCounterSideEffectListenerTest test
mvn -pl nexus-infrastructure -Dtest=FeedCounterSideEffectPortTest test
```

Expected: FAIL because fav side effects are missing.

- [x] **Step 3: Implement side effects**

Rules:

- `KnowpostCounterSideEffectListener` branches on `ObjectCounterType.LIKE` and `ObjectCounterType.FAV` only.
- Feed counter cache side effects update like/fav display caches when existing cache surfaces need them, and remain non-authoritative.
- Do not reintroduce search index updates here.

- [x] **Step 4: Run side-effect tests until they pass**

Run:

```bash
mvn -pl nexus-domain -Dtest=KnowpostCounterSideEffectListenerTest test
mvn -pl nexus-infrastructure -Dtest=FeedCounterSideEffectPortTest test
```

Expected: PASS.

- [x] **Step 5: Commit**

Run:

```bash
git add nexus-domain/src/main/java/cn/nexus/domain/social/service/KnowpostCounterSideEffectListener.java nexus-domain/src/main/java/cn/nexus/domain/social/adapter/port/IFeedCounterSideEffectPort.java nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/FeedCounterSideEffectPort.java nexus-domain/src/test/java/cn/nexus/domain/social/service/KnowpostCounterSideEffectListenerTest.java nexus-infrastructure/src/test/java/cn/nexus/infrastructure/adapter/social/port/FeedCounterSideEffectPortTest.java
git commit -m "feat: apply favorite received side effects"
```

## Task 10: Remove Comment Counter Capabilities

**Files:**
- Delete: `nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/CommentLikeChangedConsumer.java`
- Delete: `nexus-trigger/src/test/java/cn/nexus/trigger/mq/consumer/CommentLikeChangedConsumerTest.java`
- Delete: `nexus-domain/src/main/java/cn/nexus/domain/social/adapter/port/IReactionCommentLikeChangedMqPort.java`
- Delete: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/ReactionCommentLikeChangedMqPort.java`
- Modify: `nexus-infrastructure/src/main/resources/mapper/social/CommentMapper.xml`
- Modify: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/dao/social/po/CommentPO.java`
- Modify: comment DTOs under `nexus-api/src/main/java/cn/nexus/api/social/interaction/dto`
- Modify: comment services/repositories/tests under `nexus-domain` and `nexus-infrastructure`

- [x] **Step 1: Write comment removal tests**

Required cases:

- comment creation does not increment reply counters.
- comment listing DTOs do not present `like_count`, `reply_count`, or `liked`.
- comment repository insert/update paths do not update `like_count` or `reply_count`.
- `COMMENT_LIKED` notification is absent.
- comment creation, reply notification, mention notification, listing, and deletion still work.
- final schema no longer creates `interaction_comment.like_count` or `interaction_comment.reply_count`.

- [x] **Step 2: Run comment tests and confirm failure**

Run:

```bash
mvn -pl nexus-domain -Dtest=CommentQueryServiceTest,InteractionServiceTest test
mvn -pl nexus-infrastructure -Dtest=CommentRepositoryTest test
mvn -pl nexus-trigger -Dtest=CommentHttpRealIntegrationTest test
```

Expected: FAIL for fields/paths that still carry comment counters. If the real integration test requires unavailable middleware, record that and keep focused unit tests mandatory.

- [x] **Step 3: Delete comment-like counter path**

Rules:

- Remove the consumer, MQ port, and domain dependency entirely.
- Remove comment counter DTO fields from public API responses touched by this replacement.
- Remove comment counter fields from `CommentPO`, `CommentViewVO`, `RootCommentViewVO`, `CommentBriefVO`, and corresponding DTOs/mappers.
- Do not remove comment business capabilities unrelated to counters.
- Do not create replacement comment counter fields backed by zero constants.

- [x] **Step 4: Run focused comment tests until they pass**

Run the focused unit tests from Step 2. Real integration is deferred only when the exact missing middleware dependency is recorded in the implementation notes.

- [x] **Step 5: Commit**

Run:

```bash
git add nexus-api/src/main/java/cn/nexus/api/social/interaction/dto nexus-domain/src/main/java/cn/nexus/domain/social nexus-infrastructure/src/main/java/cn/nexus/infrastructure nexus-infrastructure/src/main/resources/mapper/social/CommentMapper.xml nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer nexus-domain/src/test/java/cn/nexus/domain/social nexus-infrastructure/src/test/java/cn/nexus/infrastructure nexus-trigger/src/test/java/cn/nexus/trigger/mq/consumer
git commit -m "refactor: remove comment counter capabilities"
```

## Task 11: Remove Post Projection And Repair Outbox Mechanics

**Files:**
- Delete: `nexus-domain/src/main/java/cn/nexus/domain/social/adapter/repository/IPostCounterProjectionRepository.java`
- Delete: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/PostCounterProjectionRepository.java`
- Delete: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/dao/social/po/PostCounterProjectionPO.java`
- Delete: `nexus-infrastructure/src/main/resources/mapper/social/PostCounterProjectionMapper.xml`
- Delete or replace: `docs/migrations/20260428_01_add_post_counter_projection.sql`
- Delete or replace: `docs/migrations/20260410_01_user_counter_repair_outbox.sql`
- Delete: active `ReactionEventLog` counter replay files discovered by the boundary test.
- Modify: `nexus-domain/src/main/java/cn/nexus/domain/social/service/ContentService.java`
- Modify: `nexus-domain/src/test/java/cn/nexus/domain/social/service/ContentServiceTest.java`
- Modify: `nexus-domain/src/main/java/cn/nexus/domain/social/service/RelationCounterProjectionProcessor.java`
- Modify: `nexus-domain/src/test/java/cn/nexus/domain/social/service/RelationCounterProjectionProcessorTest.java`

- [x] **Step 1: Write post-count edge tests**

Required cases:

- transition non-published to published increments author `posts` once.
- retrying publish on already published content does not increment again.
- transition published to deleted/non-published decrements author `posts` once.
- retrying delete does not decrement again.
- edge detection uses content state/update result, not a counter projection table.

- [x] **Step 2: Write relation cleanup tests**

Required cases:

- effective follow increments source `followings` and target `followers`.
- duplicate follow does not double count.
- effective unfollow decrements exactly once.
- relation code updates user counters through `IUserCounterService`.
- no relation code writes to `user_counter_repair_outbox`.
- no active code writes or reads `interaction_reaction_event_log`.

- [x] **Step 3: Run tests and confirm failure**

Run:

```bash
mvn -pl nexus-domain -Dtest=ContentServiceTest,RelationCounterProjectionProcessorTest test
```

Expected: FAIL where current code depends on projection/repair concepts.

- [x] **Step 4: Implement content and relation rewrites**

Rules:

- Delete post projection repository/DAO/XML/PO.
- Do not create another counter-only projection table.
- Use the content table's own status/version/update result for idempotent edge detection.
- Relation list cache remains allowed, but it is not counter truth.

- [x] **Step 5: Add drop migration and final schema cleanup**

Rules:

- Create a new migration with explicit `DROP TABLE IF EXISTS post_counter_projection`.
- Include `DROP TABLE IF EXISTS user_counter_repair_outbox`.
- Include `DROP TABLE IF EXISTS interaction_reaction_event_log`.
- Include `ALTER TABLE interaction_comment DROP COLUMN like_count` and `DROP COLUMN reply_count` if those columns exist.
- Remove both tables from final schema docs.
- Do not leave old creation migrations active as the final setup path.

- [x] **Step 6: Run domain tests until they pass**

Run:

```bash
mvn -pl nexus-domain -Dtest=ContentServiceTest,RelationCounterProjectionProcessorTest test
```

Expected: PASS.

- [x] **Step 7: Commit**

Run:

```bash
git add nexus-domain/src/main/java/cn/nexus/domain/social nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository nexus-infrastructure/src/main/java/cn/nexus/infrastructure/dao/social nexus-infrastructure/src/main/resources/mapper/social docs/migrations docs/nexus_final_mysql_schema.sql docs/social_schema.sql nexus-domain/src/test/java/cn/nexus/domain/social/service
git commit -m "refactor: remove nexus counter projection repair mechanics"
```

## Task 12: Remove Search Count Propagation

**Files:**
- Modify: `nexus-trigger/src/main/java/cn/nexus/trigger/search/support/SearchDocumentAssembler.java`
- Modify: `nexus-trigger/src/main/java/cn/nexus/trigger/search/support/SearchIndexUpsertService.java`
- Modify: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/SearchEnginePort.java`
- Modify: `nexus-domain/src/main/java/cn/nexus/domain/social/adapter/port/ISearchEnginePort.java`
- Modify: `nexus-app/src/main/java/cn/nexus/config/SearchIndexInitializer.java`
- Modify: `nexus-app/src/main/java/cn/nexus/config/SearchIndexBackfillRunner.java`
- Modify: `nexus-trigger/src/test/java/cn/nexus/trigger/search/support/SearchIndexUpsertServiceTest.java`
- Create: `nexus-trigger/src/test/java/cn/nexus/trigger/search/support/SearchDocumentAssemblerTest.java`.
- Modify: `nexus-infrastructure/src/test/java/cn/nexus/infrastructure/adapter/social/port/SearchEnginePortCountFieldContractTest.java`
- Modify: `nexus-app/src/test/java/cn/nexus/config/SearchIndexMappingCountFieldContractTest.java`

- [x] **Step 1: Write search count-removal tests**

Required cases:

- search document assembly does not include `likeCount`, `favoriteCount`, `commentCount`, `replyCount`, or equivalent counter fields.
- search upsert service is not subscribed to counter events.
- index initializer mapping does not create counter fields populated by the counter system.
- historical index fields, if still present for compatibility, are not updated by counter events.

- [x] **Step 2: Run search tests and confirm failure**

Run:

```bash
mvn -pl nexus-trigger -Dtest=SearchIndexUpsertServiceTest,SearchDocumentAssemblerTest test
mvn -pl nexus-infrastructure -Dtest=SearchEnginePortCountFieldContractTest test
mvn -pl nexus-app -Dtest=SearchIndexMappingCountFieldContractTest test
```

Expected: FAIL for any current count propagation.

- [x] **Step 3: Remove counter-to-search wiring**

Rules:

- Counter local/Kafka events must not trigger search upserts for counts.
- Search documents should remain focused on searchable content fields.
- Do not replace count propagation with delayed or sampled propagation.

- [x] **Step 4: Run search tests until they pass**

Run the commands from Step 2.

Expected: PASS.

- [x] **Step 5: Commit**

Run:

```bash
git add nexus-trigger/src/main/java/cn/nexus/trigger/search nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/SearchEnginePort.java nexus-domain/src/main/java/cn/nexus/domain/social/adapter/port/ISearchEnginePort.java nexus-app/src/main/java/cn/nexus/config/SearchIndexInitializer.java nexus-app/src/main/java/cn/nexus/config/SearchIndexBackfillRunner.java nexus-trigger/src/test nexus-infrastructure/src/test nexus-app/src/test
git commit -m "refactor: remove search counter propagation"
```

## Task 13: Remove Count Redis Module And Runtime References

**Files:**
- Delete: `count-redis-module/`
- Modify: `pom.xml`
- Modify: `Dockerfile`
- Modify: `nexus-app/src/main/resources/application.yml`
- Modify: `nexus-app/src/main/resources/application-docker.yml`
- Modify: root-level Docker or Maven files reported by Step 1 as active runtime/build references.

- [x] **Step 1: Run reference search before deletion**

Run:

```bash
rg -n "count-redis-module|loadmodule|count_int|Roaring|COUNT\\." count-redis-module Dockerfile pom.xml nexus-app/src/main/resources nexus-api nexus-domain nexus-infrastructure nexus-trigger nexus-app/src/main/java
```

Expected: references identify the module directory and any runtime/config hooks.

- [x] **Step 2: Delete module and runtime hooks**

Rules:

- Remove the `count-redis-module/` source directory.
- Remove Docker/build/runtime instructions that load it.
- Keep normal Redis bitmap/string/hash operations.
- Do not replace the module with another custom Redis command dependency.

- [x] **Step 3: Run boundary test**

Run:

```bash
mvn -pl nexus-app -Dtest=StrictZhiguangCounterBoundaryTest test
```

Expected: no failure related to Count Redis module.

- [x] **Step 4: Commit**

Run:

```bash
git add pom.xml Dockerfile nexus-app/src/main/resources/application.yml nexus-app/src/main/resources/application-docker.yml
git add -u count-redis-module
git commit -m "refactor: remove count redis module runtime"
```

## Task 14: Remove Remaining Old Counter Code, Tests, And Docs Drift

**Files:**
- Delete: `nexus-api/src/main/java/cn/nexus/api/social/interaction/dto/ReactionRequestDTO.java`
- Delete: `nexus-api/src/main/java/cn/nexus/api/social/interaction/dto/ReactionResponseDTO.java`
- Delete: `nexus-api/src/main/java/cn/nexus/api/social/interaction/dto/ReactionStateRequestDTO.java`
- Delete: `nexus-api/src/main/java/cn/nexus/api/social/interaction/dto/ReactionStateResponseDTO.java`
- Rewrite old reaction tests such as `nexus-app/src/test/java/cn/nexus/integration/interaction/ReactionHttpRealIntegrationTest.java` around `/api/v1/action/*`, or delete tests whose only purpose was removed behavior.
- Modify: `docs/frontend-api.md`
- Modify: active source/config/schema files reported by `StrictZhiguangCounterBoundaryTest`. Do not edit unrelated historical/reference docs to satisfy this task.

- [x] **Step 1: Run full forbidden reference scan**

Run:

```bash
rg -n "post_counter_projection|user_counter_repair_outbox|interaction_reaction_event_log|ReactionEventLog|/interact/reaction|CommentLikeChangedConsumer|IReactionCommentLikeChangedMqPort|ReactionCommentLikeChangedMqPort|count-redis-module|cnt:v1:comment|bm:like:comment|bm:fav:comment|bm:like:post:[^[:space:]\"']+:idx|likeBitmapShardIndex|COMMENT_LIKED|like_count|reply_count|likedPosts" nexus-api/src/main/java nexus-domain/src/main/java nexus-infrastructure/src/main/java nexus-infrastructure/src/main/resources/mapper nexus-trigger/src/main/java nexus-app/src/main/java nexus-app/src/main/resources Dockerfile pom.xml docs/frontend-api.md docs/nexus_final_mysql_schema.sql docs/social_schema.sql --glob '!**/target/**'
```

Expected: initial run lists active cleanup targets; final run after this task has no main-source, mapper, final-schema, frontend API, build, or runtime-config hits. Test files are verified by `StrictZhiguangCounterBoundaryTest`, not by this raw grep.

- [x] **Step 2: Remove or rewrite stale tests**

Rules:

- Do not keep tests that assert removed behavior.
- Replace reaction HTTP integration with action HTTP integration.
- If old DTOs are retained only because frontend docs still mention them, update docs and delete DTOs.

- [x] **Step 3: Update frontend API docs**

Rules:

- Document:
  - `POST /api/v1/action/like`
  - `POST /api/v1/action/unlike`
  - `POST /api/v1/action/fav`
  - `POST /api/v1/action/unfav`
  - `GET /api/v1/counter/post/{postId}?metrics=like,fav`
- Remove `/api/v1/interact/reaction` and `/api/v1/interact/reaction/state`.
- Keep comment APIs documented without counter fields.

- [x] **Step 4: Run boundary test until it passes**

Run:

```bash
mvn -pl nexus-app -Dtest=StrictZhiguangCounterBoundaryTest test
```

Expected: PASS.

- [x] **Step 5: Commit**

Run:

```bash
git add nexus-api nexus-domain nexus-infrastructure nexus-trigger nexus-app docs/frontend-api.md
git commit -m "refactor: remove legacy counter API artifacts"
```

## Task 15: End-To-End Integration Tests

**Files:**
- Create: `nexus-app/src/test/java/cn/nexus/integration/action/PostActionHttpRealIntegrationTest.java`
- Modify: `nexus-app/src/test/java/cn/nexus/integration/content/ContentHttpRealIntegrationTest.java`
- Modify: relation/profile integration tests that assert user counters

- [x] **Step 1: Write post action integration tests**

Required flows:

- like -> counter read shows `like=1`, detail shows `liked=true`.
- duplicate like -> `changed=false`, count remains `1`.
- unlike -> count eventually reaches `0`, state `liked=false`.
- fav -> counter read shows `fav=1`, detail shows `faved=true`.
- duplicate fav -> `changed=false`, count remains `1`.
- unfav -> count eventually reaches `0`, state `faved=false`.
- comment target on action API returns parameter error and creates no keys/events.

- [x] **Step 2: Write user received counter integration tests**

Required flows:

- author `likesReceived` increments after another user likes a published post.
- author `favsReceived` increments after another user favs a published post.
- duplicate actions do not double count.
- rebuild of malformed `ucnt` recomputes both received counters from post object counters.

- [x] **Step 3: Run focused integration tests**

Run:

```bash
mvn -pl nexus-app -Dtest=PostActionHttpRealIntegrationTest,ContentHttpRealIntegrationTest test
```

Expected: PASS if local middleware is available. If middleware is unavailable, record exact missing dependency and keep all unit/contract tests mandatory.

- [x] **Step 4: Commit**

Run:

```bash
git add nexus-app/src/test/java/cn/nexus/integration
git commit -m "test: cover strict zhiguang counter integration flows"
```

## Task 16: Final Verification

**Files:**
- Modify only concrete files required to fix verification failures.

- [x] **Step 1: Run focused module suites**

Run:

```bash
mvn -pl nexus-domain -Dtest=PostActionServiceTest,InteractionServiceTest,ContentServiceTest,RelationCounterProjectionProcessorTest,KnowpostCounterSideEffectListenerTest test
mvn -pl nexus-infrastructure -Dtest=CountRedisSchemaSupportTest,CountRedisOperationsTest,ObjectCounterServiceTest,UserCounterServiceTest,CounterEventProducerTest,FeedCounterSideEffectPortTest test
mvn -pl nexus-trigger -Dtest=CounterAggregationConsumerTest,ActionControllerTest,CounterControllerTest,InteractionControllerTest,ContentDetailQueryServiceTest test
mvn -pl nexus-app -Dtest=StrictZhiguangCounterBoundaryTest test
```

Expected: PASS.

- [x] **Step 2: Run forbidden reference scan**

Run:

```bash
rg -n "post_counter_projection|user_counter_repair_outbox|interaction_reaction_event_log|ReactionEventLog|/interact/reaction|CommentLikeChangedConsumer|IReactionCommentLikeChangedMqPort|ReactionCommentLikeChangedMqPort|count-redis-module|cnt:v1:comment|bm:like:comment|bm:fav:comment|bm:like:post:[^[:space:]\"']+:idx|likeBitmapShardIndex|COMMENT_LIKED|like_count|reply_count|likedPosts|agg:v1:post:[^[:space:]\"']+:[0-9]+" nexus-api/src/main/java nexus-domain/src/main/java nexus-infrastructure/src/main/java nexus-infrastructure/src/main/resources/mapper nexus-trigger/src/main/java nexus-app/src/main/java nexus-app/src/main/resources Dockerfile pom.xml docs/frontend-api.md docs/nexus_final_mysql_schema.sql docs/social_schema.sql --glob '!**/target/**'
```

Expected: no main-source, mapper, final-schema, frontend API, build, or runtime-config hits. Drop migrations are the only allowed hits for removed table names, and they are intentionally outside this raw grep scope.

- [x] **Step 3: Run compile or broader tests**

Run:

```bash
mvn -pl nexus-api,nexus-domain,nexus-infrastructure,nexus-trigger,nexus-app test
```

Expected: PASS, or record exact external middleware blocker for integration-only failures.

- [x] **Step 4: Verify git state**

Run:

```bash
git status --short
git log --oneline -5
```

Expected:

- only intentional implementation changes are staged/committed or visible.
- pre-existing unrelated changes such as `../../zhiguang_be` and `docs/counter-system/` remain untouched unless the user explicitly asked otherwise.

- [x] **Step 5: Final commit if any verification fixes were needed**

Run:

If verification exposes fixes, stage only those concrete files and commit:

```bash
git status --short
git add path/to/fixed-file-1 path/to/fixed-file-2
git commit -m "test: verify strict zhiguang counter replacement"
```

## Self-Review Notes

Spec coverage:

- Object `POST.like` and `POST.fav`: Tasks 2, 3, 4, 5, 7, 15.
- User five-slot counters: Tasks 2, 8, 9, 11, 15.
- Favorite feature end to end: Tasks 3, 4, 5, 7, 8, 9, 15.
- Comment counter removal: Tasks 1, 5, 6, 10, 14, 16.
- Count Redis module removal: Tasks 1, 13, 16.
- RabbitMQ object aggregation removal: Tasks 1, 4, 14, 16.
- Search count propagation removal: Tasks 1, 12, 16.
- Post projection and repair outbox deletion: Tasks 1, 11, 14, 16.
- Rebuild semantics: Tasks 3 and 8.
- API breakage and replacement: Tasks 5 and 14.
- Final schema cleanup: Task 11.

Ambiguity removals:

- The replacement API is concrete `/api/v1/action/*`, not a generic reaction wrapper.
- The only object aggregation key is `agg:v1:post:{postId}`.
- `COMMENT` is not an object counter target, including rebuild and read paths.
- User received counters are recomputed, not preserved.
- Search index count fields are not a fallback display sink.
- Relation/content MySQL facts are rebuild sources for user counters only, never object `like/fav` truth.

Residual risks to watch during execution:

- Existing tests that use reaction DTOs for non-counter assumptions are rewritten around action APIs instead of preserving the old route.
- Real integration tests depend on Redis/Kafka/Cassandra/MySQL availability. If unavailable, record the exact missing service and still complete all unit/static contract verification.
- DTO renames from `likedPosts` to `likesReceived` can affect frontend docs and profile response tests; keep the public zhiguang names.
