# Nexus Feed Follow Simplification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Simplify the FOLLOW feed push index to InboxTimeline plus AuthorTimeline, remove rebuild/pool/latest ownership from FOLLOW, and keep recommendation, relation, counter, card, and reliable MQ capabilities intact.

**Architecture:** Keep the current layered architecture: trigger consumes MQ and calls domain services, domain services orchestrate, repository interfaces express business storage semantics, infrastructure repositories own Redis details. AuthorTimeline is a new author-publish ZSET abstraction using `feed:timeline:{authorId}`; InboxTimeline remains the reader inbox using `feed:inbox:{userId}`. FOLLOW owns only those two ZSETs; recommendation latest fallback, card cache, relation adjacency, reliable MQ outbox, and consumer records stay outside this plan.

**Tech Stack:** Java 17, Spring Boot, Redis ZSET via `StringRedisTemplate`, RabbitMQ, MyBatis-Plus, Maven, JUnit 5, Mockito.

---

## Path Correction

The user-requested path `project/nexus/docs/superpowers/plans/2026-05-04-nexus-feed-simplify-design.md` does not exist. The actual implementation plan is this file:

`project/nexus/docs/superpowers/plans/2026-05-04-nexus-feed-simplify-plan.md`

Do not create a duplicate `*-design.md` file under `plans/`; doing so would create two competing implementation plans.

## Source Of Truth

Implement against:

`project/nexus/docs/superpowers/specs/2026-05-04-nexus-feed-simplify-design.md`

If this plan and the spec conflict, stop and update the plan before implementing. Do not silently choose either side.

## Hard Boundaries

1. Only change FOLLOW push-index behavior.
2. Do not change `RECOMMEND`, `POPULAR`, `NEIGHBORS`, or `PROFILE` API behavior.
3. Do not delete recommendation latest fallback storage or repository just because FOLLOW no longer writes it.
4. Do not modify recommendation item upsert/delete queues, recommendation feedback queues, Gorse contracts, or recommendation session keys.
5. Do not modify Count, card cache, relation truth source, relation adjacency cache, reliable MQ outbox, or consumer record infrastructure.
6. Do not write Feed Redis inside the relation write transaction.
7. Do not introduce new infrastructure: no Redis Stream, CDC, new DB table, distributed transaction, or new exchange.
8. Do not scan Redis keyspace to migrate or delete old feed keys.
9. Do not keep or add Lua rebuild, rebuild locks, merge windows, or `__NOMORE__` sentinel logic.

## Naming Rules

Use these names consistently:

- `InboxTimeline`: reader inbox, key `feed:inbox:{userId}`, existing `IFeedTimelineRepository`.
- `AuthorTimeline`: author publish index, key `feed:timeline:{authorId}`, new `IFeedAuthorTimelineRepository`.
- `HTTP timeline`: returned feed page from `FeedService.timeline`, not a Redis key.
- `Outbox`: only reliable MQ outbox or legacy Redis author index. Do not use it as the new FOLLOW business name.

## File Responsibility Map

Create:

- `nexus-domain/src/main/java/cn/nexus/domain/social/adapter/repository/IFeedAuthorTimelineRepository.java`
  Owns the AuthorTimeline contract only.
- `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/config/FeedAuthorTimelineProperties.java`
  Owns `feed.timeline.*` configuration.
- `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/FeedAuthorTimelineRepository.java`
  Owns Redis ZSET implementation for `feed:timeline:{authorId}`.
- `nexus-trigger/src/main/java/cn/nexus/trigger/mq/config/FeedFollowCompensationMqConfig.java`
  Declares an extra relation FOLLOW binding for Feed compensation. It reuses the existing relation exchange and existing FOLLOW routing key.
- `nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FollowFeedCompensationConsumer.java`
  Consumes relation FOLLOW events and delegates to `IFeedFollowCompensationService`.

Rename:

- `IFeedInboxRebuildService` to `IFeedInboxActivationService`.
- `FeedInboxRebuildService` to `FeedInboxActivationService`.
- Matching tests to activation names.

Modify:

- `FeedFanoutConfig`: merge feed cleanup updated/deleted queues into one queue.
- `FeedFanoutDispatcherConsumer`: write AuthorTimeline, stop writing latest/pool/author inbox.
- `FeedIndexCleanupConsumer`: clean only AuthorTimeline.
- `FeedFollowCompensationService`: read AuthorTimeline, write online follower inbox only.
- `FeedService`: FOLLOW read path uses InboxTimeline + BIGV AuthorTimeline + self AuthorTimeline and activation.
- `FeedTimelineRepository` and `IFeedTimelineRepository`: remove replace/rebuild APIs and implementation.
- `FeedAuthorCategoryStateMachine`: category only, no outbox rebuild.
- `FeedDistributionService`: remove unused outbox/pool injections.
- Tests and architecture contract inventory touched by the above.

Delete:

- `IFeedOutboxRebuildService`
- `FeedOutboxRebuildService`

Do not delete in this plan:

- `IFeedOutboxRepository` / `FeedOutboxRepository` unless a later dedicated compatibility cleanup proves no remaining references. This plan stops using them from FOLLOW but does not require deleting them.
- `IFeedGlobalLatestRepository` / `FeedGlobalLatestRepository`.
- `IFeedBigVPoolRepository` / `FeedBigVPoolRepository`.

## Global Implementation Rules

1. Use TDD per task: write or update the focused test first, verify it fails for the intended reason, implement, verify it passes.
2. Do not paste large classes from this plan. Edit the existing files surgically using the rules here.
3. Keep Max-ID order stable everywhere: `publishTimeMs DESC`, tie-break `postId DESC`.
4. Redis ZSET members are `postId` strings; scores are `publishTimeMs`.
5. Trim ZSETs by actual `zCard`: if `size > maxSize`, remove rank `0..size-maxSize-1`. Do not use negative stop indexes as a shortcut.
6. Use `StringRedisTemplate.getExpire(key)` as the current code does. In tests, mock it as `Long`, not `Duration`.
7. Page methods must over-fetch by a small fixed cushion and then apply the `(cursorTimeMs, cursorPostId)` predicate in Java to handle equal-millisecond ordering.
8. Null or malformed Redis members are skipped, not fatal.
9. Empty activation results do not create an inbox key and do not write a sentinel.
10. Cleanup never scans timelines and never uses operatorId as authorId.
11. Relation follow compensation must consume the existing `RelationCounterRouting.RK_FOLLOW`; do not create a new routing key that the publisher never emits.

---

## Task 1: AuthorTimeline Repository

**Files:**
- Create: `nexus-domain/src/main/java/cn/nexus/domain/social/adapter/repository/IFeedAuthorTimelineRepository.java`
- Create: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/config/FeedAuthorTimelineProperties.java`
- Create: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/FeedAuthorTimelineRepository.java`
- Create: `nexus-infrastructure/src/test/java/cn/nexus/infrastructure/adapter/social/repository/FeedAuthorTimelineRepositoryTest.java`
- Modify: `nexus-app/src/main/resources/application-dev.yml`
- Modify: `nexus-app/src/main/resources/application-docker.yml`
- Modify: `nexus-app/src/main/resources/application-test.yml`
- Modify: `nexus-app/src/main/resources/application-prod.yml`

- [ ] **Step 1: Write failing repository tests**

Cover these exact behaviors in `FeedAuthorTimelineRepositoryTest`:

- `addToTimeline` ZADDs `feed:timeline:{authorId}` with member `postId`.
- `addToTimeline` sets TTL when Redis returns no TTL.
- `addToTimeline` trims only when `zCard > maxSize`.
- `removeFromTimeline` ZREMs the member.
- `pageTimeline` returns entries ordered by `publishTimeMs DESC, postId DESC`.
- `pageTimeline` excludes the cursor item and later/equal items according to Max-ID.
- `timelineExists` delegates to `hasKey`.
- Null arguments are no-ops or empty results.

Run: `mvn -pl nexus-infrastructure test -Dtest=FeedAuthorTimelineRepositoryTest`

Expected before implementation: compile failure because repository/types do not exist.

- [ ] **Step 2: Add properties**

Create `FeedAuthorTimelineProperties` with `@ConfigurationProperties(prefix = "feed.timeline")`, not `feed.author.timeline`.

Fields:

- `maxSize`, default `1000`
- `ttlDays`, default `30`

Add `feed.timeline.maxSize` and `feed.timeline.ttlDays` to app YAML files. Keep `feed.outbox.*` for compatibility; do not repurpose it for new code.

- [ ] **Step 3: Add repository interface**

Create `IFeedAuthorTimelineRepository` with only:

- `void addToTimeline(Long authorId, Long postId, Long publishTimeMs)`
- `void removeFromTimeline(Long authorId, Long postId)`
- `List<FeedInboxEntryVO> pageTimeline(Long authorId, Long cursorTimeMs, Long cursorPostId, int limit)`
- `boolean timelineExists(Long authorId)`

No replace, rebuild, lock, Lua, sentinel, or scan methods.

- [ ] **Step 4: Implement Redis repository**

Implement `FeedAuthorTimelineRepository` with these constraints:

- Key prefix exactly `feed:timeline:`.
- `addToTimeline` uses `opsForZSet().add`, then TTL, then trim.
- TTL follows existing `FeedTimelineRepository.expireIfNeeded` semantics.
- Trim follows actual-cardinality rule from Global Implementation Rules.
- `pageTimeline` mirrors current Max-ID logic in `FeedOutboxRepository.pageOutbox` but uses `feed:timeline:*` and no rebuild helpers.
- Do not copy `replaceOutbox`, tmp keys, lock keys, or Lua script.

- [ ] **Step 5: Verify**

Run: `mvn -pl nexus-infrastructure test -Dtest=FeedAuthorTimelineRepositoryTest`

Expected: PASS.

- [ ] **Step 6: Commit**

Run:

```bash
git add nexus-domain/src/main/java/cn/nexus/domain/social/adapter/repository/IFeedAuthorTimelineRepository.java nexus-infrastructure/src/main/java/cn/nexus/infrastructure/config/FeedAuthorTimelineProperties.java nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/FeedAuthorTimelineRepository.java nexus-infrastructure/src/test/java/cn/nexus/infrastructure/adapter/social/repository/FeedAuthorTimelineRepositoryTest.java nexus-app/src/main/resources/application-dev.yml nexus-app/src/main/resources/application-docker.yml nexus-app/src/main/resources/application-test.yml nexus-app/src/main/resources/application-prod.yml
git commit -m "feat: add feed author timeline repository"
```

---

## Task 2: Follow Compensation MQ Topology

**Files:**
- Create: `nexus-trigger/src/main/java/cn/nexus/trigger/mq/config/FeedFollowCompensationMqConfig.java`
- Modify: `nexus-domain/src/main/java/cn/nexus/domain/social/model/valobj/RelationCounterRouting.java`
- Create: `nexus-trigger/src/test/java/cn/nexus/trigger/mq/config/FeedFollowCompensationMqConfigTest.java`

- [ ] **Step 1: Write failing topology test**

Create or update a focused test that asserts:

- Queue name is `relation.counter.follow.feed.compensate.queue`.
- DLQ name is `relation.counter.follow.feed.compensate.dlq.queue`.
- The queue binds to existing `RelationCounterRouting.EXCHANGE`.
- The binding routing key is existing `RelationCounterRouting.RK_FOLLOW`.
- The DLQ binding uses `RelationCounterRouting.DLX_EXCHANGE`.

Run: `mvn -pl nexus-trigger test -Dtest=FeedFollowCompensationMqConfigTest`

Expected before implementation: compile failure because config/constants do not exist.

- [ ] **Step 2: Add queue constants only**

In `RelationCounterRouting`, add:

- `Q_FOLLOW_FEED_COMPENSATE`
- `DLQ_FOLLOW_FEED_COMPENSATE`
- `RK_FOLLOW_FEED_COMPENSATE_DLX`

Do not add a new normal routing key. The publisher emits `RK_FOLLOW`; a new normal routing key would never receive messages.

- [ ] **Step 3: Add topology config**

Create `FeedFollowCompensationMqConfig`.

Rules:

- Reuse existing `relationExchange` and `relationDlxExchange` beans.
- Bind compensation queue to `RelationCounterRouting.RK_FOLLOW`.
- Configure dead-letter routing to `RK_FOLLOW_FEED_COMPENSATE_DLX`.
- This queue is not part of `social.feed` and must not change `FeedFanoutConfig`.

- [ ] **Step 4: Verify**

Run: `mvn -pl nexus-trigger test -Dtest=FeedFollowCompensationMqConfigTest`

Expected: PASS.

- [ ] **Step 5: Commit**

Run:

```bash
git add nexus-domain/src/main/java/cn/nexus/domain/social/model/valobj/RelationCounterRouting.java nexus-trigger/src/main/java/cn/nexus/trigger/mq/config/FeedFollowCompensationMqConfig.java nexus-trigger/src/test/java/cn/nexus/trigger/mq/config/FeedFollowCompensationMqConfigTest.java
git commit -m "feat: add feed follow compensation queue"
```

---

## Task 3: Follow Compensation Consumer

**Files:**
- Create: `nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FollowFeedCompensationConsumer.java`
- Create: `nexus-trigger/src/test/java/cn/nexus/trigger/mq/consumer/FollowFeedCompensationConsumerTest.java`

- [ ] **Step 1: Write failing consumer tests**

Cover:

- `ACTIVE` calls `IFeedFollowCompensationService.onFollow(sourceId, targetId)`.
- `UNFOLLOW` calls `onUnfollow(sourceId, targetId)`.
- Null event, sourceId, or targetId skips.
- Blank or unknown status skips.
- Consumer uses compensation queue constant, not relation counter queue.

Run: `mvn -pl nexus-trigger test -Dtest=FollowFeedCompensationConsumerTest`

Expected before implementation: compile failure because consumer does not exist.

- [ ] **Step 2: Implement consumer**

Rules:

- `@RabbitListener` queue is `RelationCounterRouting.Q_FOLLOW_FEED_COMPENSATE`.
- Use `reliableMqListenerContainerFactory`.
- Use `@ReliableMqConsume` with stable consumer name `FollowFeedCompensationConsumer`.
- Event id expression must prefer `event.eventId` if available; do not use only `relationEventId` because it can be null in fallback paths.
- Do not call relation repository, counter service, or adjacency cache here.
- Do not catch and swallow compensation exceptions; retry/DLQ should work.

- [ ] **Step 3: Verify**

Run: `mvn -pl nexus-trigger test -Dtest=FollowFeedCompensationConsumerTest`

Expected: PASS.

- [ ] **Step 4: Commit**

Run:

```bash
git add nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FollowFeedCompensationConsumer.java nexus-trigger/src/test/java/cn/nexus/trigger/mq/consumer/FollowFeedCompensationConsumerTest.java
git commit -m "feat: consume follow events for feed compensation"
```

---

## Task 4: Merge Feed Cleanup Queues

**Files:**
- Modify: `nexus-trigger/src/main/java/cn/nexus/trigger/mq/config/FeedFanoutConfig.java`
- Modify: `nexus-trigger/src/test/java/cn/nexus/trigger/mq/config/FeedFanoutConfigTest.java`

- [ ] **Step 1: Update failing config test**

Assert:

- One business queue constant: `Q_FEED_INDEX_CLEANUP = "feed.index.cleanup.queue"`.
- One DLQ constant: `DLQ_FEED_INDEX_CLEANUP = "feed.index.cleanup.dlq.queue"`.
- `post.updated` and `post.deleted` both bind to the same queue bean.
- One cleanup DLQ binding exists.
- Old updated/deleted cleanup queue constants are gone.

Run: `mvn -pl nexus-trigger test -Dtest=FeedFanoutConfigTest`

Expected before implementation: FAIL or compile failure due to old constants.

- [ ] **Step 2: Modify `FeedFanoutConfig`**

Rules:

- Replace updated/deleted cleanup queue and DLQ constants with one cleanup queue and one cleanup DLQ.
- Keep `RK_POST_UPDATED` and `RK_POST_DELETED`.
- Bind the single cleanup queue to both routing keys.
- Keep distinct method names for the two business bindings.
- Remove old cleanup queue beans and old cleanup DLQ beans.
- Do not touch recommendation item queues.

- [ ] **Step 3: Verify**

Run: `mvn -pl nexus-trigger test -Dtest=FeedFanoutConfigTest`

Expected: PASS.

- [ ] **Step 4: Commit**

Run:

```bash
git add nexus-trigger/src/main/java/cn/nexus/trigger/mq/config/FeedFanoutConfig.java nexus-trigger/src/test/java/cn/nexus/trigger/mq/config/FeedFanoutConfigTest.java
git commit -m "refactor: merge feed index cleanup queues"
```

---

## Task 5: Simplify Cleanup Consumer

**Files:**
- Modify: `nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FeedIndexCleanupConsumer.java`
- Modify: `nexus-trigger/src/test/java/cn/nexus/trigger/mq/consumer/FeedIndexCleanupConsumerTest.java`

- [ ] **Step 1: Update failing tests**

Cover:

- Both update and delete listeners use `FeedFanoutConfig.Q_FEED_INDEX_CLEANUP`.
- Invalid payload throws `ReliableMqPermanentFailureException`.
- DB null logs/skips and does not remove timeline.
- PUBLISHED post does not remove timeline.
- Non-PUBLISHED post removes `feedAuthorTimelineRepository.removeFromTimeline(post.userId, postId)`.
- Lookup exception propagates for retry.
- No interactions with BigV pool or global latest repositories.

Run: `mvn -pl nexus-trigger test -Dtest=FeedIndexCleanupConsumerTest`

Expected before implementation: FAIL due to old queue/dependencies.

- [ ] **Step 2: Modify consumer**

Rules:

- Inject `IFeedAuthorTimelineRepository`.
- Remove `IFeedOutboxRepository`, `IFeedBigVPoolRepository`, and `IFeedGlobalLatestRepository`.
- Update both listener queue constants to the single cleanup queue.
- Keep separate `@ReliableMqConsume.consumerName` values for updated and deleted methods.
- If `findPostBypassCache(postId)` returns null, log and return.
- Never scan all timelines and never use operatorId.
- Do not remove inbox entries here.

- [ ] **Step 3: Verify**

Run: `mvn -pl nexus-trigger test -Dtest=FeedIndexCleanupConsumerTest`

Expected: PASS.

- [ ] **Step 4: Commit**

Run:

```bash
git add nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FeedIndexCleanupConsumer.java nexus-trigger/src/test/java/cn/nexus/trigger/mq/consumer/FeedIndexCleanupConsumerTest.java
git commit -m "refactor: cleanup only author timeline"
```

---

## Task 6: Simplify Fanout Dispatcher

**Files:**
- Modify: `nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FeedFanoutDispatcherConsumer.java`
- Modify: `nexus-trigger/src/test/java/cn/nexus/trigger/mq/consumer/FeedFanoutDispatcherConsumerTest.java`

- [ ] **Step 1: Update failing tests**

Cover:

- Every valid published event writes AuthorTimeline.
- Dispatcher never writes author inbox.
- Dispatcher never writes latest.
- Dispatcher never writes BigV pool.
- BigV author writes AuthorTimeline and does not publish fanout tasks.
- NORMAL author writes AuthorTimeline and publishes expected fanout tasks.
- Invalid event still throws permanent failure.

Run: `mvn -pl nexus-trigger test -Dtest=FeedFanoutDispatcherConsumerTest`

Expected before implementation: FAIL due to old behavior.

- [ ] **Step 2: Modify dispatcher**

Rules:

- Replace `IFeedOutboxRepository` with `IFeedAuthorTimelineRepository`.
- Remove `IFeedBigVPoolRepository` and `IFeedGlobalLatestRepository`.
- Keep `IFeedTimelineRepository` only if the class still needs online/fanout helpers; otherwise remove it.
- First valid write is `addToTimeline(authorId, postId, publishTimeMs)`.
- Do not call `addToInbox(authorId, ...)`.
- Do not call `addToLatest`.
- Do not call `addToPool`.
- Category miss may call `FeedAuthorCategoryStateMachine.onFollowerCountChanged`, but that state machine must not rebuild timeline.

- [ ] **Step 3: Verify**

Run: `mvn -pl nexus-trigger test -Dtest=FeedFanoutDispatcherConsumerTest`

Expected: PASS.

- [ ] **Step 4: Commit**

Run:

```bash
git add nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FeedFanoutDispatcherConsumer.java nexus-trigger/src/test/java/cn/nexus/trigger/mq/consumer/FeedFanoutDispatcherConsumerTest.java
git commit -m "refactor: write author timeline from feed dispatcher"
```

---

## Task 7: Replace Inbox Rebuild With Activation

**Files:**
- Rename: `nexus-domain/src/main/java/cn/nexus/domain/social/service/IFeedInboxRebuildService.java` to `nexus-domain/src/main/java/cn/nexus/domain/social/service/IFeedInboxActivationService.java`
- Rename: `nexus-domain/src/main/java/cn/nexus/domain/social/service/FeedInboxRebuildService.java` to `nexus-domain/src/main/java/cn/nexus/domain/social/service/FeedInboxActivationService.java`
- Modify: `nexus-domain/src/main/java/cn/nexus/domain/social/adapter/repository/IFeedTimelineRepository.java`
- Modify: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/FeedTimelineRepository.java`
- Rename/modify tests for rebuild and timeline repository.
- Rename: `nexus-domain/src/test/java/cn/nexus/domain/social/service/FeedInboxRebuildServiceTest.java` to `nexus-domain/src/test/java/cn/nexus/domain/social/service/FeedInboxActivationServiceTest.java`

- [ ] **Step 1: Update failing activation tests**

Cover:

- `activateIfNeeded` skips when inbox exists.
- On inbox miss, activation reads self plus all followings from AuthorTimeline.
- NORMAL and BIGV followings are both included.
- Activation writes top merged entries to inbox with Max-ID ordering.
- Empty merged result does not write inbox and does not create sentinel.
- No DB `listUserPosts` dependency is used.
- No `replaceInbox` call is used.

Run: `mvn -pl nexus-domain test -Dtest=FeedInboxActivationServiceTest`

Expected before implementation: compile failure or failing old behavior.

- [ ] **Step 2: Rename service and interface**

Rules:

- Interface exposes only `boolean activateIfNeeded(Long userId)`.
- Remove `forceRebuild`.
- Class name and bean name become activation, not rebuild.
- Keep package location unchanged.
- Update all imports in later tasks.

- [ ] **Step 3: Implement activation behavior**

Rules:

- Dependencies: `IRelationAdjacencyCachePort`, `IFeedAuthorTimelineRepository`, `IFeedTimelineRepository`.
- Do not depend on `IContentRepository`.
- Config keys: use `feed.activation.perFollowingLimit`, `feed.activation.maxFollowings`, `feed.activation.inboxSize` with sane defaults.
- Include self AuthorTimeline.
- Pull each target with first-page Max-ID cursor.
- Deduplicate by `postId` before writing inbox.
- Sort with `publishTimeMs DESC, postId DESC`.
- Write via `feedTimelineRepository.addToInbox`.
- If no entries, do nothing.

- [ ] **Step 4: Remove rebuild API from InboxTimeline repository**

Rules:

- Remove `replaceInbox` from `IFeedTimelineRepository`.
- Remove Lua script, tmp key, rebuild lock key, lock acquisition, merge window config, and `__NOMORE__` from `FeedTimelineRepository`.
- Keep `addToInbox`, `inboxExists`, `filterOnlineUsers`, `pageInbox`, `pageInboxEntries`, and `removeFromInbox`.
- Remove sentinel filtering from page methods because sentinel must no longer exist.

- [ ] **Step 5: Update repository tests**

Remove old tests that assert Lua/lock/sentinel behavior.

Add/keep tests for:

- `addToInbox` trims by cardinality.
- `pageInboxEntries` respects Max-ID cursor and skips malformed members.
- `filterOnlineUsers` still uses key existence.
- `removeFromInbox` remains idempotent.

- [ ] **Step 6: Verify**

Run:

```bash
mvn -pl nexus-domain test -Dtest=FeedInboxActivationServiceTest
mvn -pl nexus-infrastructure test -Dtest=FeedTimelineRepositoryTest
```

Expected: PASS.

- [ ] **Step 7: Commit**

Run:

```bash
git add nexus-domain/src/main/java/cn/nexus/domain/social/service/IFeedInboxActivationService.java nexus-domain/src/main/java/cn/nexus/domain/social/service/FeedInboxActivationService.java nexus-domain/src/main/java/cn/nexus/domain/social/adapter/repository/IFeedTimelineRepository.java nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/FeedTimelineRepository.java nexus-domain/src/test/java/cn/nexus/domain/social/service/FeedInboxActivationServiceTest.java nexus-infrastructure/src/test/java/cn/nexus/infrastructure/adapter/social/repository/FeedTimelineRepositoryTest.java
git rm nexus-domain/src/main/java/cn/nexus/domain/social/service/IFeedInboxRebuildService.java nexus-domain/src/main/java/cn/nexus/domain/social/service/FeedInboxRebuildService.java
git commit -m "refactor: replace inbox rebuild with activation"
```

---

## Task 8: FOLLOW Read Path

**Files:**
- Modify: `nexus-domain/src/main/java/cn/nexus/domain/social/service/FeedService.java`
- Modify: `nexus-domain/src/test/java/cn/nexus/domain/social/service/FeedServiceTest.java`

- [ ] **Step 1: Update failing tests**

Cover:

- Refresh calls `activateIfNeeded`.
- FOLLOW merge sources are inbox, BIGV AuthorTimeline, and self AuthorTimeline.
- NORMAL followee AuthorTimeline is not scanned during normal read.
- Same post in inbox and AuthorTimeline returns once.
- Non-PUBLISHED/missing/unfollowed/blocked candidates do not reach assembly.
- Dirty post cleanup removes only current user inbox.
- RECOMMEND/POPULAR/NEIGHBORS/PROFILE behavior remains unchanged.

Run: `mvn -pl nexus-domain test -Dtest=FeedServiceTest`

Expected before implementation: FAIL due to old outbox/rebuild behavior.

- [ ] **Step 2: Modify dependencies**

Rules:

- Replace `IFeedOutboxRepository` with `IFeedAuthorTimelineRepository`.
- Replace `IFeedInboxRebuildService` with `IFeedInboxActivationService`.
- Remove BigV pool dependency from FOLLOW read path.
- Do not remove global latest dependency if recommendation methods still use it.

- [ ] **Step 3: Modify `followTimeline`**

Rules:

- On refresh, call `activateIfNeeded(userId)`.
- Page inbox with existing `pageInboxEntries`.
- Page each BIGV followee via `pageTimeline`.
- Always page self via `pageTimeline(userId, ...)`.
- Use one Max-ID cursor for all sources.
- Merge/dedupe before assembly.
- Keep scan budget bounded when filtering reduces the result.

- [ ] **Step 4: Verify**

Run: `mvn -pl nexus-domain test -Dtest=FeedServiceTest`

Expected: PASS.

- [ ] **Step 5: Commit**

Run:

```bash
git add nexus-domain/src/main/java/cn/nexus/domain/social/service/FeedService.java nexus-domain/src/test/java/cn/nexus/domain/social/service/FeedServiceTest.java
git commit -m "refactor: read follow feed from inbox and author timelines"
```

---

## Task 9: Follow Compensation Service

**Files:**
- Modify: `nexus-domain/src/main/java/cn/nexus/domain/social/service/FeedFollowCompensationService.java`
- Create: `nexus-domain/src/test/java/cn/nexus/domain/social/service/FeedFollowCompensationServiceTest.java`

- [ ] **Step 1: Write or update failing tests**

Cover:

- Online follower reads followee AuthorTimeline and writes entries to inbox.
- Offline follower does not create inbox.
- Null or self-follow inputs skip.
- Empty AuthorTimeline skips.
- Unfollow is no-op.
- No DB content repository is used.

Run: `mvn -pl nexus-domain test -Dtest=FeedFollowCompensationServiceTest`

Expected before implementation: FAIL due to DB-based implementation or missing tests.

- [ ] **Step 2: Modify service**

Rules:

- Replace `IContentRepository` dependency with `IFeedAuthorTimelineRepository`.
- Keep `IFeedTimelineRepository.inboxExists(followerId)` gate.
- Read `pageTimeline(followeeId, null, null, recentPosts)`.
- Write each result via `addToInbox(followerId, postId, publishTimeMs)`.
- Do not activate offline inbox here.

- [ ] **Step 3: Verify**

Run: `mvn -pl nexus-domain test -Dtest=FeedFollowCompensationServiceTest`

Expected: PASS.

- [ ] **Step 4: Commit**

Run:

```bash
git add nexus-domain/src/main/java/cn/nexus/domain/social/service/FeedFollowCompensationService.java nexus-domain/src/test/java/cn/nexus/domain/social/service/FeedFollowCompensationServiceTest.java
git commit -m "refactor: compensate follow from author timeline"
```

---

## Task 10: Remove Rebuild Coupling And Dead FOLLOW Dependencies

**Files:**
- Delete: `nexus-domain/src/main/java/cn/nexus/domain/social/service/IFeedOutboxRebuildService.java`
- Delete: `nexus-domain/src/main/java/cn/nexus/domain/social/service/FeedOutboxRebuildService.java`
- Modify: `nexus-domain/src/main/java/cn/nexus/domain/social/service/FeedAuthorCategoryStateMachine.java`
- Modify: `nexus-domain/src/main/java/cn/nexus/domain/social/service/FeedDistributionService.java`
- Create: `nexus-domain/src/test/java/cn/nexus/domain/social/service/FeedAuthorCategoryStateMachineTest.java`
- Create: `nexus-domain/src/test/java/cn/nexus/domain/social/service/FeedDistributionServiceTest.java`

- [ ] **Step 1: Update failing tests**

Cover:

- Category change updates category hash but does not call any rebuild service.
- `FeedDistributionService` still fanouts to online followers.
- No outbox/pool repository dependency remains in `FeedDistributionService`.

Run: `mvn -pl nexus-domain test -Dtest=FeedAuthorCategoryStateMachineTest,FeedDistributionServiceTest`

Expected before implementation: compile failure because these focused tests do not exist yet, or failing old rebuild expectation if equivalent tests already exist.

- [ ] **Step 2: Remove outbox rebuild service**

Rules:

- Delete interface and implementation.
- Remove dependency from state machine.
- Category miss/change only updates category.
- Keep follower threshold logic unchanged.

- [ ] **Step 3: Clean FeedDistributionService**

Rules:

- Remove `IFeedOutboxRepository` and `IFeedBigVPoolRepository` fields if unused.
- Do not change fanout online filtering behavior.

- [ ] **Step 4: Verify**

Run: `mvn -pl nexus-domain test -Dtest=FeedAuthorCategoryStateMachineTest,FeedDistributionServiceTest`

Expected: PASS.

- [ ] **Step 5: Commit**

Run:

```bash
git rm nexus-domain/src/main/java/cn/nexus/domain/social/service/IFeedOutboxRebuildService.java nexus-domain/src/main/java/cn/nexus/domain/social/service/FeedOutboxRebuildService.java
git add nexus-domain/src/main/java/cn/nexus/domain/social/service/FeedAuthorCategoryStateMachine.java nexus-domain/src/main/java/cn/nexus/domain/social/service/FeedDistributionService.java
git commit -m "chore: remove feed outbox rebuild coupling"
```

---

## Task 11: Compatibility And Reference Cleanup

**Files:** Search-driven.

- [ ] **Step 1: Run reference scan**

Run:

```bash
rg -n "IFeedOutboxRepository|FeedOutboxRepository|pageOutbox|addToOutbox|removeFromOutbox|IFeedInboxRebuildService|FeedInboxRebuildService|replaceInbox|IFeedOutboxRebuildService|FeedOutboxRebuildService|feed:outbox|feed:bigv:pool|addToLatest|addToPool" nexus-*
```

Expected: Only allowed references remain:

- legacy repository classes/tests not used by FOLLOW,
- recommendation code that still needs global latest,
- docs or compatibility comments,
- no production FOLLOW path references.

- [ ] **Step 2: Remove or update disallowed references**

Rules:

- Production FOLLOW path must not reference old outbox repository.
- Dispatcher must not reference latest or pool.
- Cleanup must not reference latest or pool.
- Activation must not reference rebuild service.
- Do not delete recommendation latest references.

- [ ] **Step 3: Verify compile**

Run: `mvn compile`

Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

Run:

```bash
git diff --name-only
git add <only the disallowed-reference cleanup files shown by the diff>
git commit -m "chore: clean feed simplification references"
```

Never use `git add .` in this repository for this task. The worktree may contain unrelated user changes.

---

## Task 12: Architecture Contract And Integration Tests

**Files:**
- Modify: `nexus-app/src/test/java/cn/nexus/contract/mq/ReliableMqArchitectureContractTest.java`
- Modify: `nexus-app/src/test/java/cn/nexus/integration/FeedFanoutRealIntegrationTest.java`
- Modify: `nexus-app/src/test/java/cn/nexus/integration/feed/FeedHttpRealIntegrationTest.java`

- [ ] **Step 1: Update MQ architecture contract**

Rules:

- Add `FollowFeedCompensationConsumer` to expected listener inventory.
- Its queue is the feed compensation queue bound to relation FOLLOW.
- Feed cleanup updated/deleted listeners both use `Q_FEED_INDEX_CLEANUP`.
- Do not classify compensation consumer as relation counter projection.

Run: `mvn -pl nexus-app test -Dtest=ReliableMqArchitectureContractTest`

Expected before implementation: FAIL until inventory is updated.

- [ ] **Step 2: Update fanout integration test**

Rules:

- Assert author post appears in `feed:timeline:{authorId}`.
- Assert NORMAL follower inbox receives fanout.
- Assert BigV author does not fanout but has AuthorTimeline entry.
- Remove old assertions that FOLLOW writes global latest or BigV pool.

- [ ] **Step 3: Update HTTP integration test**

Rules:

- FOLLOW response includes self AuthorTimeline post.
- FOLLOW response includes BigV AuthorTimeline post.
- FOLLOW response still filters unfollowed/blocked/invalid posts.
- Recommendation or profile assertions remain unchanged.

- [ ] **Step 4: Verify**

Run:

```bash
mvn -pl nexus-app test -Dtest=ReliableMqArchitectureContractTest
mvn -pl nexus-app test -Dtest=FeedFanoutRealIntegrationTest,FeedHttpRealIntegrationTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

Run:

```bash
git add nexus-app/src/test/java/cn/nexus/contract/mq/ReliableMqArchitectureContractTest.java nexus-app/src/test/java/cn/nexus/integration/FeedFanoutRealIntegrationTest.java nexus-app/src/test/java/cn/nexus/integration/feed/FeedHttpRealIntegrationTest.java
git commit -m "test: update feed simplification contracts"
```

---

## Task 13: Final Verification

**Files:** No production edits.

- [ ] **Step 1: Compile all modules**

Run: `mvn compile`

Expected: BUILD SUCCESS.

- [ ] **Step 2: Run focused test suites**

Run:

```bash
mvn -pl nexus-infrastructure test -Dtest=FeedAuthorTimelineRepositoryTest,FeedTimelineRepositoryTest
mvn -pl nexus-domain test -Dtest=FeedInboxActivationServiceTest,FeedServiceTest,FeedFollowCompensationServiceTest,FeedAuthorCategoryStateMachineTest,FeedDistributionServiceTest
mvn -pl nexus-trigger test -Dtest=FeedFollowCompensationMqConfigTest,FollowFeedCompensationConsumerTest,FeedFanoutConfigTest,FeedIndexCleanupConsumerTest,FeedFanoutDispatcherConsumerTest
mvn -pl nexus-app test -Dtest=ReliableMqArchitectureContractTest,FeedFanoutRealIntegrationTest,FeedHttpRealIntegrationTest
```

Expected: all PASS.

- [ ] **Step 3: Drift scan**

Run:

```bash
rg -n "REPLACE_INBOX_SCRIPT|__NOMORE__|feed:inbox:tmp|feed:inbox:rebuild:lock|replaceInbox" nexus-domain nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/FeedTimelineRepository.java nexus-trigger nexus-app
rg -n "addToLatest|addToPool|IFeedBigVPoolRepository|IFeedGlobalLatestRepository" nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FeedFanoutDispatcherConsumer.java nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FeedIndexCleanupConsumer.java
rg -n "IFeedOutboxRebuildService|FeedOutboxRebuildService|forceRebuild" nexus-domain nexus-infrastructure nexus-trigger nexus-app
rg -n "feed.author.timeline|relation.counter.follow.compensate" nexus-domain nexus-infrastructure nexus-trigger nexus-app
```

Expected:

- First three commands return no production matches.
- Fourth command returns no matches; config must use `feed.timeline.*`, and compensation must bind to existing `relation.counter.follow`.
- Legacy `FeedOutboxRepository` may still contain its old Lua script during compatibility; that is allowed only if no FOLLOW production path references it.

- [ ] **Step 4: Git status review**

Run: `git status --short`

Expected: only files from this plan are changed.

---

## Execution Order

Fixed order:

1. AuthorTimeline repository
2. Follow compensation MQ topology
3. Follow compensation consumer
4. Merge feed cleanup queues
5. Simplify cleanup consumer
6. Simplify fanout dispatcher
7. Replace inbox rebuild with activation
8. FOLLOW read path
9. Follow compensation service
10. Remove rebuild coupling and dead FOLLOW dependencies
11. Compatibility and reference cleanup
12. Architecture contract and integration tests
13. Final verification

Do not parallelize tasks that edit the same files. Safe parallel candidates after Task 1 are limited to test-only exploration; implementation should remain sequential unless using separate agents with disjoint write sets.

## Per-Task Drift Checklist

After every task, check:

1. Did this task add a Redis key outside `feed:inbox:*` or `feed:timeline:*` for FOLLOW?
2. Did this task add Lua, locks, tmp keys, merge windows, or sentinels?
3. Did dispatcher write latest, pool, or author inbox?
4. Did cleanup remove inbox, latest, pool, or scan timelines?
5. Did relation write transaction gain Feed Redis side effects?
6. Did recommendation, popular, neighbors, or profile behavior change?
7. Did code delete or repurpose recommendation latest fallback?
8. Did config use `feed.timeline.*` rather than `feed.outbox.*` or `feed.author.timeline.*`?
9. Did any test assert old outbox/pool/latest FOLLOW behavior?
10. Did any commit stage unrelated dirty files?

## Self-Review Notes

This plan closes the prior ambiguity points:

- Uses the actual existing plan filename and records that the requested `*-design.md` plan path does not exist.
- Reuses relation FOLLOW routing for compensation instead of inventing an unroutable normal key.
- Keeps recommendation latest fallback out of FOLLOW ownership without deleting recommendation storage.
- Uses `feed.timeline.*` config to match the spec.
- Replaces large implementation code blocks with executable rules and test requirements.
- Fixes Redis trim, TTL mock, Max-ID pagination, and activation semantics before implementation.
- Prevents deleting legacy outbox/global/pool repositories prematurely.

## Handoff

Plan complete and saved to `docs/superpowers/plans/2026-05-04-nexus-feed-simplify-plan.md`.

Two execution options:

1. **Subagent-Driven (recommended)** - dispatch a fresh subagent per task, review between tasks, fast iteration
2. **Inline Execution** - execute tasks in this session using executing-plans, batch execution with checkpoints

Which approach?
