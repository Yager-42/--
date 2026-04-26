# Class 2 Counter High-Throughput Eventual Consistency Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the current Class 2 `rebuildAllCounters(...)`-driven normal projection path with O(1) incremental projection plus durable repair so `USER.following`, `USER.follower`, and `USER.post` stay eventually consistent under high concurrency.

**Architecture:** Keep MySQL as business truth, RabbitMQ as delivery, and Redis `ucnt:{userId}` as the read snapshot. Normal projection becomes state-transition based and version-ordered, backed by durable projection watermark state and a DB-backed Class 2 repair task table; expensive counting moves out of the main consumer path into a coalesced repair worker.

**Tech Stack:** Java 17, Spring Boot, Spring AMQP, MyBatis, MySQL, Redis, RabbitMQ, Maven, JUnit 5, Mockito.

---

## Execution Context

All paths and commands are relative to the Nexus repo root:

- repo root: `project/nexus`
- absolute repo root: `/Users/rr/Desktop/revive/--/project/nexus`

This plan only changes Class 2 counters:

- `USER.following`
- `USER.follower`
- `USER.post`

This plan explicitly does not redesign:

- Class 1 `POST.like`, `COMMENT.like`, `USER.like_received`
- Kafka `counter-events`
- Redis bitmap truth
- `COMMENT.reply`
- any removed legacy recovery artifacts asserted by `nexus-trigger/src/test/java/cn/nexus/trigger/counter/CounterReplacementContractTest.java`

## File Structure

### Event contract and write-side payload

- Modify: `nexus-types/src/main/java/cn/nexus/types/event/relation/RelationCounterProjectEvent.java`
- Modify: `nexus-domain/src/main/java/cn/nexus/domain/social/adapter/port/IRelationEventPort.java`
- Modify: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/RelationEventPort.java`
- Modify: `nexus-trigger/src/main/java/cn/nexus/trigger/job/social/RelationEventOutboxPublishJob.java`
- Modify: `nexus-infrastructure/src/test/java/cn/nexus/infrastructure/adapter/social/port/RelationEventPortTest.java`
- Modify: `nexus-trigger/src/test/java/cn/nexus/trigger/job/social/RelationEventOutboxPublishJobTest.java`

Responsibility:

- put durable ordering metadata on every Class 2 projection event
- keep RabbitMQ publishing confirm-based
- make publisher payload parsing explicit and testable

### Follow truth lifecycle and outbox creation

- Modify: `nexus-domain/src/main/java/cn/nexus/domain/social/service/RelationService.java`
- Modify: `nexus-domain/src/test/java/cn/nexus/domain/social/service/RelationServiceTest.java`
- Modify: `nexus-domain/src/main/java/cn/nexus/domain/social/adapter/repository/IRelationRepository.java`
- Modify: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/RelationRepository.java`
- Modify: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/dao/social/IRelationDao.java`
- Modify: `nexus-infrastructure/src/main/resources/mapper/social/RelationMapper.xml`

Responsibility:

- stop physically deleting follow truth rows
- preserve one durable row identity per `(sourceId, targetId, relationType=FOLLOW)`
- make relation `version` monotonic across follow, unfollow, and re-follow cycles

### Projection watermark state

- Create: `nexus-domain/src/main/java/cn/nexus/domain/social/adapter/repository/IClass2CounterProjectionStateRepository.java`
- Create: `nexus-domain/src/main/java/cn/nexus/domain/social/model/valobj/Class2ProjectionAdvanceResult.java`
- Create: `nexus-domain/src/main/java/cn/nexus/domain/social/model/valobj/Class2CounterProjectionStateVO.java`
- Create: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/Class2CounterProjectionStateRepository.java`
- Create: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/dao/social/IClass2CounterProjectionStateDao.java`
- Create: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/dao/social/po/Class2CounterProjectionStatePO.java`
- Create: `nexus-infrastructure/src/main/resources/mapper/social/Class2CounterProjectionStateMapper.xml`

Responsibility:

- persist last projected version per projection key
- make `projection_key` the only durable ordering identity; `projection_type` is audit metadata only
- reject old events with an explicit `ADVANCED` / `STALE` repository result, not JDBC affected-row guessing

### Durable Class 2 repair task

- Create: `nexus-domain/src/main/java/cn/nexus/domain/social/adapter/repository/IClass2UserCounterRepairTaskRepository.java`
- Create: `nexus-domain/src/main/java/cn/nexus/domain/social/model/valobj/Class2UserCounterRepairTaskVO.java`
- Create: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/Class2UserCounterRepairTaskRepository.java`
- Create: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/dao/social/IClass2UserCounterRepairTaskDao.java`
- Create: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/dao/social/po/Class2UserCounterRepairTaskPO.java`
- Create: `nexus-infrastructure/src/main/resources/mapper/social/Class2UserCounterRepairTaskMapper.xml`

Responsibility:

- durably record repair work per user
- coalesce repeated repair requests
- claim work with `owner + claimed_at + lease_until` semantics
- support retry, abandoned-worker reclaim, and visibility without reusing forbidden legacy names

### User counter service and projection processor

- Modify: `nexus-domain/src/main/java/cn/nexus/domain/counter/adapter/service/IUserCounterService.java`
- Modify: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/counter/service/UserCounterService.java`
- Modify: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/counter/support/CountRedisOperations.java`
- Modify: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/counter/support/CountRedisKeys.java`
- Modify: `nexus-infrastructure/src/test/java/cn/nexus/infrastructure/adapter/counter/service/UserCounterServiceTest.java`
- Modify: `nexus-infrastructure/src/test/java/cn/nexus/infrastructure/adapter/counter/support/CountRedisOperationsTest.java`
- Modify: `nexus-domain/src/main/java/cn/nexus/domain/social/service/RelationCounterProjectionProcessor.java`
- Modify: `nexus-domain/src/test/java/cn/nexus/domain/social/service/RelationCounterProjectionProcessorTest.java`
- Modify: `nexus-trigger/src/main/java/cn/nexus/trigger/listener/social/RelationCounterProjectConsumer.java`
- Modify: `nexus-trigger/src/test/java/cn/nexus/trigger/listener/social/RelationCounterProjectConsumerTest.java`

Responsibility:

- add narrow `repairClass2Counters(userId)`
- add per-event idempotent Redis slot apply for Class 2 deltas
- keep `rebuildAllCounters(userId)` only for full mixed-source recovery
- use `user_relation` and `content_post` as sampled verification truth sources
- remove synchronous full rebuild from the successful RabbitMQ normal path

### Content post projection and tests

- Modify: `nexus-domain/src/main/java/cn/nexus/domain/social/service/ContentService.java`
- Modify: `nexus-domain/src/test/java/cn/nexus/domain/social/service/ContentServiceTest.java`

Responsibility:

- emit post outbox events with `projectionKey=post:{postId}` and `projectionVersion=versionNum`
- only emit when published-state transition is real

### Repair job, integration tests, and docs

- Create: `nexus-trigger/src/main/java/cn/nexus/trigger/job/social/Class2UserCounterRepairJob.java`
- Create: `nexus-trigger/src/test/java/cn/nexus/trigger/job/social/Class2UserCounterRepairJobTest.java`
- Create: `nexus-app/src/test/java/cn/nexus/integration/Class2CounterProjectionRealIntegrationTest.java`
- Modify: `nexus-app/src/test/java/cn/nexus/integration/support/RealBusinessIntegrationTestSupport.java`
- Modify: `nexus-app/src/test/java/cn/nexus/integration/ReliableJobRealIntegrationTest.java`
- Create: `docs/migrations/20260426_01_class2_counter_projection_state_and_repair_task.sql`
- Modify: `docs/nexus_final_mysql_schema.sql`

Responsibility:

- process repair tasks outside the main queue
- prove redelivery/idempotency/follow-race convergence with real middleware
- document required schema changes

## Task 1: Lock the new behavior in tests first

**Files:**
- Modify: `nexus-domain/src/test/java/cn/nexus/domain/social/service/RelationServiceTest.java`
- Modify: `nexus-domain/src/test/java/cn/nexus/domain/social/service/ContentServiceTest.java`
- Modify: `nexus-domain/src/test/java/cn/nexus/domain/social/service/RelationCounterProjectionProcessorTest.java`
- Modify: `nexus-infrastructure/src/test/java/cn/nexus/infrastructure/adapter/counter/service/UserCounterServiceTest.java`

- [ ] **Step 1: Add write-side payload assertions for follow and post events**

Update the tests so they assert the new payload fields instead of only checking `status`:

```java
assertTrue(payload.contains("\"projectionKey\":\"follow:1:2\""));
assertTrue(payload.contains("\"projectionVersion\":0"));
assertTrue(payload.contains("\"status\":\"ACTIVE\""));
```

```java
assertTrue(payload.contains("\"projectionKey\":\"post:101\""));
assertTrue(payload.contains("\"projectionVersion\":3"));
assertTrue(payload.contains("\"status\":\"PUBLISHED\""));
```

- [ ] **Step 2: Invert the projection processor unit tests away from inline rebuild**

Replace assertions like:

```java
verify(userCounterService).rebuildAllCounters(1L);
verify(userCounterService).rebuildAllCounters(2L);
```

with:

```java
verify(userCounterService).incrementFollowings(1L, 1L);
verify(userCounterService).incrementFollowers(2L, 1L);
verify(userCounterService, never()).rebuildAllCounters(any());
```

and for post:

```java
verify(userCounterService).incrementPosts(11L, 1L);
verify(userCounterService, never()).rebuildAllCounters(any());
```

- [ ] **Step 3: Add narrow repair assertions in `UserCounterServiceTest`**

Add a dedicated test for `repairClass2Counters(...)`:

```java
service.repairClass2Counters(31L);

verify(relationRepository).countActiveRelationsBySource(31L, 1);
verify(relationRepository).countActiveRelationsByTarget(31L, 1);
verify(contentRepository).countPublishedPostsByUser(31L);
verify(objectCounterService, never()).getCounts(any(), anyLong(), any());
```

- [ ] **Step 4: Run the focused tests and verify they fail for the expected reasons**

Run:

```bash
mvn -pl nexus-domain -Dtest=RelationServiceTest,ContentServiceTest,RelationCounterProjectionProcessorTest test
mvn -pl nexus-infrastructure -Dtest=UserCounterServiceTest test
```

Expected:

- `RelationServiceTest` and `ContentServiceTest` fail because payloads do not yet contain `projectionKey` / `projectionVersion`
- `RelationCounterProjectionProcessorTest` fails because production code still calls `rebuildAllCounters(...)`
- `UserCounterServiceTest` fails because `repairClass2Counters(...)` does not exist yet

- [ ] **Step 5: Commit the failing tests**

```bash
git add nexus-domain/src/test/java/cn/nexus/domain/social/service/RelationServiceTest.java nexus-domain/src/test/java/cn/nexus/domain/social/service/ContentServiceTest.java nexus-domain/src/test/java/cn/nexus/domain/social/service/RelationCounterProjectionProcessorTest.java nexus-infrastructure/src/test/java/cn/nexus/infrastructure/adapter/counter/service/UserCounterServiceTest.java
git commit -m "test: lock class2 incremental projection contract"
```

## Task 2: Extend the Class 2 event contract with durable ordering metadata

**Files:**
- Modify: `nexus-types/src/main/java/cn/nexus/types/event/relation/RelationCounterProjectEvent.java`
- Modify: `nexus-domain/src/main/java/cn/nexus/domain/social/adapter/port/IRelationEventPort.java`
- Modify: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/RelationEventPort.java`
- Modify: `nexus-trigger/src/main/java/cn/nexus/trigger/job/social/RelationEventOutboxPublishJob.java`
- Modify: `nexus-infrastructure/src/test/java/cn/nexus/infrastructure/adapter/social/port/RelationEventPortTest.java`
- Modify: `nexus-trigger/src/test/java/cn/nexus/trigger/job/social/RelationEventOutboxPublishJobTest.java`

- [ ] **Step 1: Add the missing fields to the transport event**

Patch `RelationCounterProjectEvent` to carry ordering metadata:

```java
private String projectionKey;
private Long projectionVersion;
```

The canonical keys in this change are:

```text
follow:{sourceId}:{targetId}
post:{postId}
```

- [ ] **Step 2: Change the publish port signature so callers must provide ordering metadata**

Update `IRelationEventPort` to:

```java
boolean publishCounterProjection(Long eventId,
                                 String eventType,
                                 Long sourceId,
                                 Long targetId,
                                 String status,
                                 String projectionKey,
                                 Long projectionVersion);
```

Keep the convenience methods aligned:

```java
default boolean onPost(Long eventId, Long authorId, Long postId, String status, String projectionKey, Long projectionVersion) {
    return publishCounterProjection(eventId, "POST", authorId, postId, status, projectionKey, projectionVersion);
}
```

- [ ] **Step 3: Pass the new fields through publisher and outbox job**

In `RelationEventPort`:

```java
event.setProjectionKey(projectionKey);
event.setProjectionVersion(projectionVersion);
```

In `RelationEventOutboxPublishJob.RelationOutboxPayload`:

```java
public String projectionKey;
public Long projectionVersion;
```

and publish with:

```java
relationEventPort.publishCounterProjection(
        event.eventId,
        "FOLLOW",
        event.sourceId,
        event.targetId,
        event.status,
        event.projectionKey,
        event.projectionVersion);
```

- [ ] **Step 4: Run the focused publisher tests and verify they pass**

Run:

```bash
mvn -pl nexus-infrastructure -Dtest=RelationEventPortTest test
mvn -pl nexus-trigger -Dtest=RelationEventOutboxPublishJobTest test
```

Expected: PASS

- [ ] **Step 5: Commit the event contract change**

```bash
git add nexus-types/src/main/java/cn/nexus/types/event/relation/RelationCounterProjectEvent.java nexus-domain/src/main/java/cn/nexus/domain/social/adapter/port/IRelationEventPort.java nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/RelationEventPort.java nexus-trigger/src/main/java/cn/nexus/trigger/job/social/RelationEventOutboxPublishJob.java nexus-infrastructure/src/test/java/cn/nexus/infrastructure/adapter/social/port/RelationEventPortTest.java nexus-trigger/src/test/java/cn/nexus/trigger/job/social/RelationEventOutboxPublishJobTest.java
git commit -m "feat: add projection ordering metadata to class2 events"
```

## Task 3: Make the follow truth row monotonic across follow and unfollow cycles

**Files:**
- Modify: `nexus-domain/src/main/java/cn/nexus/domain/social/adapter/repository/IRelationRepository.java`
- Modify: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/RelationRepository.java`
- Modify: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/dao/social/IRelationDao.java`
- Modify: `nexus-infrastructure/src/main/resources/mapper/social/RelationMapper.xml`
- Modify: `nexus-domain/src/main/java/cn/nexus/domain/social/service/RelationService.java`
- Modify: `nexus-domain/src/test/java/cn/nexus/domain/social/service/RelationServiceTest.java`

- [ ] **Step 1: Introduce explicit relation deactivation instead of physical delete**

Add repository methods:

```java
RelationEntity findRelationForUpdate(Long sourceId, Long targetId, Integer relationType);

boolean activateRelation(Long sourceId, Long targetId, Integer relationType, Long expectedVersion, Date createTime);

boolean deactivateRelation(Long sourceId, Long targetId, Integer relationType, Long expectedVersion, Integer inactiveStatus);
```

For this change, use:

```java
private static final int STATUS_ACTIVE = 1;
private static final int STATUS_INACTIVE = 0;
```

- [ ] **Step 2: Fix the mapper so `version` is actually read and updated**

Update the `resultMap` and `selectOne` projection:

```xml
<result property="version" column="version"/>
```

```xml
SELECT id, source_id, target_id, relation_type, status, group_id, version, create_time
FROM user_relation
WHERE source_id = #{sourceId} AND target_id = #{targetId} AND relation_type = #{relationType}
```

Add an update for inactive transition:

```xml
<update id="deactivate">
    UPDATE user_relation
    SET status = #{inactiveStatus},
        version = version + 1
    WHERE source_id = #{sourceId}
      AND target_id = #{targetId}
      AND relation_type = #{relationType}
      AND status = #{activeStatus}
      AND version = #{expectedVersion}
</update>

<update id="activate">
    UPDATE user_relation
    SET status = #{activeStatus},
        version = version + 1,
        create_time = #{createTime}
    WHERE source_id = #{sourceId}
      AND target_id = #{targetId}
      AND relation_type = #{relationType}
      AND status = #{inactiveStatus}
      AND version = #{expectedVersion}
</update>

<select id="selectOneForUpdate" resultMap="RelationMap">
    SELECT id, source_id, target_id, relation_type, status, group_id, version, create_time
    FROM user_relation
    WHERE source_id = #{sourceId} AND target_id = #{targetId} AND relation_type = #{relationType}
    FOR UPDATE
</select>
```

- [ ] **Step 3: Change `RelationService` to reuse the durable follow row and emit its version**

On re-follow, lock first, reactivate with CAS, then reread the committed version:

```java
RelationEntity locked = relationRepository.findRelationForUpdate(sourceId, targetId, RELATION_FOLLOW);
if (locked == null) {
    relationRepository.saveRelation(RelationEntity.builder()
            .id(relationId)
            .sourceId(sourceId)
            .targetId(targetId)
            .relationType(RELATION_FOLLOW)
            .status(STATUS_ACTIVE)
            .groupId(0L)
            .version(0L)
            .createTime(followTime)
            .build());
    projectionVersion = 0L;
} else if (Integer.valueOf(STATUS_ACTIVE).equals(locked.getStatus())) {
    return FollowResultVO.builder().status("ACTIVE").build();
} else {
    boolean updated = relationRepository.activateRelation(
            sourceId,
            targetId,
            RELATION_FOLLOW,
            locked.getVersion(),
            followTime);
    if (!updated) {
        throw new IllegalStateException("follow relation CAS conflict");
    }
    projectionVersion = relationRepository.findRelation(sourceId, targetId, RELATION_FOLLOW).getVersion();
}
```

On unfollow and block cleanup:

```java
RelationEntity locked = relationRepository.findRelationForUpdate(sourceId, targetId, RELATION_FOLLOW);
boolean updated = relationRepository.deactivateRelation(
        sourceId,
        targetId,
        RELATION_FOLLOW,
        locked.getVersion(),
        STATUS_INACTIVE);
if (!updated) {
    throw new IllegalStateException("unfollow relation CAS conflict");
}
long projectionVersion = relationRepository.findRelation(sourceId, targetId, RELATION_FOLLOW).getVersion();
```

Emit payloads with:

```java
buildFollowPayload(eventId, sourceId, targetId, "UNFOLLOW", "follow:" + sourceId + ":" + targetId, projectionVersion)
```

- [ ] **Step 4: Run the relation-domain tests and verify they pass**

Run:

```bash
mvn -pl nexus-domain -Dtest=RelationServiceTest test
```

Expected: PASS

- [ ] **Step 5: Commit the monotonic follow truth change**

```bash
git add nexus-domain/src/main/java/cn/nexus/domain/social/adapter/repository/IRelationRepository.java nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/RelationRepository.java nexus-infrastructure/src/main/java/cn/nexus/infrastructure/dao/social/IRelationDao.java nexus-infrastructure/src/main/resources/mapper/social/RelationMapper.xml nexus-domain/src/main/java/cn/nexus/domain/social/service/RelationService.java nexus-domain/src/test/java/cn/nexus/domain/social/service/RelationServiceTest.java
git commit -m "feat: keep follow relation versions monotonic"
```

## Task 4: Emit versioned post projection events only on real published-state transitions

**Files:**
- Modify: `nexus-domain/src/main/java/cn/nexus/domain/social/service/ContentService.java`
- Modify: `nexus-domain/src/test/java/cn/nexus/domain/social/service/ContentServiceTest.java`

- [ ] **Step 1: Add tests for `projectionKey` and `projectionVersion` on post outbox payloads**

Extend the existing post publish assertions:

```java
assertTrue(payload.contains("\"projectionKey\":\"post:101\""));
assertTrue(payload.contains("\"projectionVersion\":1"));
assertTrue(payload.contains("\"status\":\"PUBLISHED\""));
```

Add an unpublish/delete assertion with a non-zero version:

```java
assertTrue(payload.contains("\"projectionVersion\":3"));
assertTrue(payload.contains("\"status\":\"UNPUBLISHED\""));
```

- [ ] **Step 2: Make the payload builder carry post version and key**

When `ContentService` decides the transition is real, build payloads like:

```java
savePostCounterOutbox(
        postId,
        authorId,
        "PUBLISHED",
        "post:" + postId,
        Long.valueOf(newVersion));
```

The JSON written into relation outbox must now contain:

```json
{"eventId":502,"sourceId":11,"targetId":101,"status":"PUBLISHED","projectionKey":"post:101","projectionVersion":3}
```

- [ ] **Step 3: Keep the no-double-count guard on already-published posts**

The write path must still skip emitting a post counter event when the publish request does not create a real published-state transition:

```java
if (alreadyPublished && remainedPublished) {
    return;
}
```

Use the existing `status` and `versionNum` checks already present in `ContentService`; only the payload shape changes here.

- [ ] **Step 4: Run the content-domain tests and verify they pass**

Run:

```bash
mvn -pl nexus-domain -Dtest=ContentServiceTest test
```

Expected: PASS

- [ ] **Step 5: Commit the post outbox change**

```bash
git add nexus-domain/src/main/java/cn/nexus/domain/social/service/ContentService.java nexus-domain/src/test/java/cn/nexus/domain/social/service/ContentServiceTest.java
git commit -m "feat: emit versioned post counter projection events"
```

## Task 5: Add durable projection watermark state and durable Class 2 repair task storage

**Files:**
- Create: `nexus-domain/src/main/java/cn/nexus/domain/social/adapter/repository/IClass2CounterProjectionStateRepository.java`
- Create: `nexus-domain/src/main/java/cn/nexus/domain/social/model/valobj/Class2ProjectionAdvanceResult.java`
- Create: `nexus-domain/src/main/java/cn/nexus/domain/social/model/valobj/Class2CounterProjectionStateVO.java`
- Create: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/Class2CounterProjectionStateRepository.java`
- Create: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/dao/social/IClass2CounterProjectionStateDao.java`
- Create: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/dao/social/po/Class2CounterProjectionStatePO.java`
- Create: `nexus-infrastructure/src/main/resources/mapper/social/Class2CounterProjectionStateMapper.xml`
- Create: `nexus-domain/src/main/java/cn/nexus/domain/social/adapter/repository/IClass2UserCounterRepairTaskRepository.java`
- Create: `nexus-domain/src/main/java/cn/nexus/domain/social/model/valobj/Class2UserCounterRepairTaskVO.java`
- Create: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/Class2UserCounterRepairTaskRepository.java`
- Create: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/dao/social/IClass2UserCounterRepairTaskDao.java`
- Create: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/dao/social/po/Class2UserCounterRepairTaskPO.java`
- Create: `nexus-infrastructure/src/main/resources/mapper/social/Class2UserCounterRepairTaskMapper.xml`

- [ ] **Step 1: Define the repository contracts in the domain layer**

Projection state:

```java
public enum Class2ProjectionAdvanceResult {
    ADVANCED,
    STALE
}
```

```java
public interface IClass2CounterProjectionStateRepository {
    Class2ProjectionAdvanceResult advanceIfNewer(String projectionKey, String projectionType, long projectionVersion);
}
```

Repair task:

```java
public interface IClass2UserCounterRepairTaskRepository {
    void enqueue(String repairType, Long userId, String reason, String dedupeKey);
    List<Class2UserCounterRepairTaskVO> claimBatch(String owner, int limit, Date now, Date leaseUntil);
    void markDone(Long taskId, String owner);
    void markRetry(Long taskId, String owner, Date nextRetryTime, String lastError);
    void release(Long taskId, String owner, Date nextRetryTime, String reason);
}
```

Use a single repair type in this change:

```text
USER_CLASS2
```

- [ ] **Step 2: Implement projection-state advance and repair-task claim with explicit semantics**

The projection-state repository must not infer `ADVANCED` from JDBC row counts. Implement it as:

```java
Class2CounterProjectionStatePO state = dao.selectForUpdate(projectionKey);
if (state == null) {
    dao.insert(projectionKey, projectionType, projectionVersion, now);
    return Class2ProjectionAdvanceResult.ADVANCED;
}
if (projectionVersion <= state.getLastVersion()) {
    return Class2ProjectionAdvanceResult.STALE;
}
dao.updateVersion(projectionKey, projectionType, projectionVersion, now);
return Class2ProjectionAdvanceResult.ADVANCED;
```

`projection_key` is the durable uniqueness boundary in this change:

```text
follow:{sourceId}:{targetId}
post:{postId}
```

`projection_type` is stored only for observability and debugging. Repository implementations must reject any attempt to reuse one `projection_key` with a different `projection_type`.

The repair-task claim path must use transaction-scoped `FOR UPDATE SKIP LOCKED` plus lease fields for crash recovery:

```xml
SELECT task_id
FROM class2_user_counter_repair_task
WHERE ((status = 'PENDING' AND next_retry_time <= #{now})
    OR (status = 'RUNNING' AND lease_until < #{now}))
ORDER BY next_retry_time ASC, task_id ASC
LIMIT #{limit}
FOR UPDATE SKIP LOCKED
```

```xml
UPDATE class2_user_counter_repair_task
SET status = 'RUNNING',
    claim_owner = #{owner},
    claimed_at = #{now},
    lease_until = #{leaseUntil},
    update_time = #{now}
WHERE task_id IN (...)
```

- [ ] **Step 3: Add repository tests or focused mapper tests if the module already uses them**

At minimum, add assertions in the repository implementation tests or create them if missing:

```java
assertEquals(Class2ProjectionAdvanceResult.ADVANCED, repository.advanceIfNewer("follow:1:2", "FOLLOW", 3L));
assertEquals(Class2ProjectionAdvanceResult.STALE, repository.advanceIfNewer("follow:1:2", "FOLLOW", 3L));
assertEquals(Class2ProjectionAdvanceResult.STALE, repository.advanceIfNewer("follow:1:2", "FOLLOW", 2L));
```

```java
repository.enqueue("USER_CLASS2", 11L, "redis increment failed", "USER_CLASS2:11");
repository.enqueue("USER_CLASS2", 11L, "repeat", "USER_CLASS2:11");
List<Class2UserCounterRepairTaskVO> claimed = repository.claimBatch("worker-a", 10, new Date(), new Date(System.currentTimeMillis() + 60_000L));
assertEquals(1, claimed.size());
assertEquals("worker-a", claimed.get(0).getClaimOwner());
```

- [ ] **Step 4: Run infrastructure tests for the new repositories**

Run:

```bash
mvn -pl nexus-infrastructure -Dtest=Class2CounterProjectionStateRepositoryTest,Class2UserCounterRepairTaskRepositoryTest test
```

Expected: PASS

- [ ] **Step 5: Commit the durable state storage**

```bash
git add nexus-domain/src/main/java/cn/nexus/domain/social/adapter/repository/IClass2CounterProjectionStateRepository.java nexus-domain/src/main/java/cn/nexus/domain/social/model/valobj/Class2ProjectionAdvanceResult.java nexus-domain/src/main/java/cn/nexus/domain/social/model/valobj/Class2CounterProjectionStateVO.java nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/Class2CounterProjectionStateRepository.java nexus-infrastructure/src/main/java/cn/nexus/infrastructure/dao/social/IClass2CounterProjectionStateDao.java nexus-infrastructure/src/main/java/cn/nexus/infrastructure/dao/social/po/Class2CounterProjectionStatePO.java nexus-infrastructure/src/main/resources/mapper/social/Class2CounterProjectionStateMapper.xml nexus-domain/src/main/java/cn/nexus/domain/social/adapter/repository/IClass2UserCounterRepairTaskRepository.java nexus-domain/src/main/java/cn/nexus/domain/social/model/valobj/Class2UserCounterRepairTaskVO.java nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/Class2UserCounterRepairTaskRepository.java nexus-infrastructure/src/main/java/cn/nexus/infrastructure/dao/social/IClass2UserCounterRepairTaskDao.java nexus-infrastructure/src/main/java/cn/nexus/infrastructure/dao/social/po/Class2UserCounterRepairTaskPO.java nexus-infrastructure/src/main/resources/mapper/social/Class2UserCounterRepairTaskMapper.xml
git commit -m "feat: add class2 projection state and repair task storage"
```

## Task 6: Add narrow Class 2 repair in `UserCounterService`

**Files:**
- Modify: `nexus-domain/src/main/java/cn/nexus/domain/counter/adapter/service/IUserCounterService.java`
- Modify: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/counter/service/UserCounterService.java`
- Modify: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/counter/support/CountRedisOperations.java`
- Modify: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/counter/support/CountRedisKeys.java`
- Modify: `nexus-infrastructure/src/test/java/cn/nexus/infrastructure/adapter/counter/service/UserCounterServiceTest.java`
- Modify: `nexus-infrastructure/src/test/java/cn/nexus/infrastructure/adapter/counter/support/CountRedisOperationsTest.java`

- [ ] **Step 1: Extend the service contract with a narrow repair API**

Add:

```java
void repairClass2Counters(Long userId);
boolean applyClass2DeltaOnce(String eventId, Long userId, UserCounterType counterType, long delta);
```

Do not remove:

```java
void rebuildAllCounters(Long userId);
```

because full mixed-source malformed snapshot recovery still needs it.

- [ ] **Step 2: Implement `repairClass2Counters(...)` without scanning Class 1 truth**

In `UserCounterService`, compute only:

```java
rebuilt.put(UserCounterType.FOLLOWING.getCode(),
        Math.max(0L, relationRepository.countActiveRelationsBySource(userId, 1)));
rebuilt.put(UserCounterType.FOLLOWER.getCode(),
        Math.max(0L, relationRepository.countActiveRelationsByTarget(userId, 1)));
rebuilt.put(UserCounterType.POST.getCode(),
        Math.max(0L, contentRepository.countPublishedPostsByUser(userId)));
```

Preserve the current display-derived slots:

```java
Map<String, Long> current = operations.readUserSnapshot(CountRedisKeys.userSnapshot(userId), CountRedisSchema.user());
rebuilt.put(UserCounterType.LIKE_RECEIVED.getCode(), current.getOrDefault(UserCounterType.LIKE_RECEIVED.getCode(), 0L));
rebuilt.put(UserCounterType.FAVORITE_RECEIVED.getCode(), current.getOrDefault(UserCounterType.FAVORITE_RECEIVED.getCode(), 0L));
```

- [ ] **Step 3: Add per-event idempotent Redis apply and convert sampled mismatch to repair enqueue**

Add a Redis helper API:

```java
boolean incrementSnapshotSlotOnce(String snapshotKey,
                                  int slot,
                                  long delta,
                                  String dedupeKey,
                                  long dedupeTtlSeconds,
                                  CountRedisSchema schema);
```

Use a Lua script that atomically:

```lua
if redis.call('SET', dedupeKey, '1', 'NX', 'EX', ttl) then
  -- apply delta once
  return 1
end
return 0
```

Build dedupe keys like:

```java
CountRedisKeys.userProjectionEventDedup(eventId, userId, counterType)
```

In `maybeVerifyRelationSlots(...)`, compare all three Class 2 slots against business truth:

```java
long truthFollowing = Math.max(0L, relationRepository.countActiveRelationsBySource(userId, 1));
long truthFollower = Math.max(0L, relationRepository.countActiveRelationsByTarget(userId, 1));
long truthPost = Math.max(0L, contentRepository.countPublishedPostsByUser(userId));
```

Replace direct `rebuildAllCounters(userId)` on sampled mismatch with a repository-backed repair enqueue hook:

```java
class2UserCounterRepairTaskRepository.enqueue(
        "USER_CLASS2",
        userId,
        "sampled class2 mismatch",
        "USER_CLASS2:" + userId);
```

Keep synchronous `rebuildAllCounters(userId)` only for missing or malformed snapshot recovery where the current read cannot safely continue. `countFollowerIds(...)` remains projection-only and must not be used by repair or verification.

- [ ] **Step 4: Run the user counter tests and verify they pass**

Run:

```bash
mvn -pl nexus-infrastructure -Dtest=UserCounterServiceTest,CountRedisOperationsTest test
```

Expected: PASS

- [ ] **Step 5: Commit the narrow repair service**

```bash
git add nexus-domain/src/main/java/cn/nexus/domain/counter/adapter/service/IUserCounterService.java nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/counter/service/UserCounterService.java nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/counter/support/CountRedisOperations.java nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/counter/support/CountRedisKeys.java nexus-infrastructure/src/test/java/cn/nexus/infrastructure/adapter/counter/service/UserCounterServiceTest.java nexus-infrastructure/src/test/java/cn/nexus/infrastructure/adapter/counter/support/CountRedisOperationsTest.java
git commit -m "feat: add narrow class2 user counter repair"
```

## Task 7: Rewrite the projection processor to be O(1) and repair-backed

**Files:**
- Modify: `nexus-domain/src/main/java/cn/nexus/domain/social/service/RelationCounterProjectionProcessor.java`
- Modify: `nexus-domain/src/test/java/cn/nexus/domain/social/service/RelationCounterProjectionProcessorTest.java`
- Modify: `nexus-trigger/src/main/java/cn/nexus/trigger/listener/social/RelationCounterProjectConsumer.java`
- Modify: `nexus-trigger/src/test/java/cn/nexus/trigger/listener/social/RelationCounterProjectConsumerTest.java`

- [ ] **Step 1: Inject projection state and repair-task repositories into the processor**

Constructor shape:

```java
public RelationCounterProjectionProcessor(IRelationRepository relationRepository,
                                          IRelationAdjacencyCachePort relationAdjacencyCachePort,
                                          IUserCounterService userCounterService,
                                          IClass2CounterProjectionStateRepository projectionStateRepository,
                                          IClass2UserCounterRepairTaskRepository repairTaskRepository,
                                          IContentRepository contentRepository) {
```

- [ ] **Step 2: Implement version-gated follow projection**

For `FOLLOW ACTIVE`:

```java
if (projectionStateRepository.advanceIfNewer(event.getProjectionKey(), "FOLLOW", event.getProjectionVersion())
        == Class2ProjectionAdvanceResult.STALE) {
    return;
}
if (!isActiveFollow(sourceId, targetId)) {
    enqueueRepairBothUsers(sourceId, targetId, "follow active ordering uncertainty");
    return;
}
boolean changed = relationRepository.saveFollowerIfAbsent(followerRowId, targetId, sourceId, new Date());
if (changed) {
    userCounterService.applyClass2DeltaOnce(event.getEventId(), sourceId, UserCounterType.FOLLOWING, 1L);
    userCounterService.applyClass2DeltaOnce(event.getEventId(), targetId, UserCounterType.FOLLOWER, 1L);
} else {
    enqueueRepairBothUsers(sourceId, targetId, "follow active missing follower transition");
}
relationAdjacencyCachePort.addFollowWithTtl(sourceId, targetId, System.currentTimeMillis(), ADJACENCY_CACHE_TTL_SECONDS);
```

Use a concrete helper in the same class:

```java
private void enqueueRepairBothUsers(Long sourceId, Long targetId, String reason) {
    repairTaskRepository.enqueue("USER_CLASS2", sourceId, reason, "USER_CLASS2:" + sourceId);
    if (targetId != null && !targetId.equals(sourceId)) {
        repairTaskRepository.enqueue("USER_CLASS2", targetId, reason, "USER_CLASS2:" + targetId);
    }
}
```

For `FOLLOW UNFOLLOW`:

```java
if (projectionStateRepository.advanceIfNewer(event.getProjectionKey(), "FOLLOW", event.getProjectionVersion())
        == Class2ProjectionAdvanceResult.STALE) {
    return;
}
if (isActiveFollow(sourceId, targetId)) {
    enqueueRepairBothUsers(sourceId, targetId, "follow unfollow ordering uncertainty");
    return;
}
boolean changed = relationRepository.deleteFollowerIfPresent(targetId, sourceId);
if (changed) {
    userCounterService.applyClass2DeltaOnce(event.getEventId(), sourceId, UserCounterType.FOLLOWING, -1L);
    userCounterService.applyClass2DeltaOnce(event.getEventId(), targetId, UserCounterType.FOLLOWER, -1L);
} else {
    enqueueRepairBothUsers(sourceId, targetId, "follow unfollow missing follower transition");
}
relationAdjacencyCachePort.removeFollowWithTtl(sourceId, targetId, ADJACENCY_CACHE_TTL_SECONDS);
```

- [ ] **Step 3: Implement version-gated post projection**

Use the post truth before mutating:

```java
ContentPostEntity post = contentRepository.findPostBypassCache(event.getTargetId());
if (projectionStateRepository.advanceIfNewer(event.getProjectionKey(), "POST", event.getProjectionVersion())
        == Class2ProjectionAdvanceResult.STALE) {
    return;
}
if ("PUBLISHED".equals(status) && post != null && Integer.valueOf(2).equals(post.getStatus())) {
    userCounterService.applyClass2DeltaOnce(event.getEventId(), authorId, UserCounterType.POST, 1L);
    return;
}
if (("UNPUBLISHED".equals(status) || "DELETED".equals(status)) && (post == null || !Integer.valueOf(2).equals(post.getStatus()))) {
    userCounterService.applyClass2DeltaOnce(event.getEventId(), authorId, UserCounterType.POST, -1L);
    return;
}
repairTaskRepository.enqueue("USER_CLASS2", authorId, "post state uncertainty", "USER_CLASS2:" + authorId);
```

- [ ] **Step 4: Make Redis replay-safe and keep `BLOCK` out of follower/counter mutation**

`BLOCK` queue ordering is independent from `FOLLOW`, so `BLOCK` projection must not delete `user_follower` rows and must not mutate counters. Limit it to cache invalidation:

```java
private void applyBlockProjection(RelationCounterProjectEvent event) {
    relationAdjacencyCachePort.removeFollowWithTtl(event.getSourceId(), event.getTargetId(), ADJACENCY_CACHE_TTL_SECONDS);
    relationAdjacencyCachePort.removeFollowWithTtl(event.getTargetId(), event.getSourceId(), ADJACENCY_CACHE_TTL_SECONDS);
}
```

If adjacency cache update fails after truth transition was already confirmed:

```java
repairTaskRepository.enqueue("USER_CLASS2", sourceId, "follow projection side effect failed", "USER_CLASS2:" + sourceId);
repairTaskRepository.enqueue("USER_CLASS2", targetId, "follow projection side effect failed", "USER_CLASS2:" + targetId);
```

`RelationCounterProjectConsumer` must keep the DB transaction for `startManual -> process -> markDone`, but its exception path must enqueue repair in a separate transaction before dead-lettering. Add a concrete processor helper:

```java
public void registerFailureRepair(RelationCounterProjectEvent event, String reason) {
    if (event == null) {
        return;
    }
    String type = normalize(event.getEventType());
    if ("POST".equals(type) && event.getSourceId() != null) {
        repairTaskRepository.enqueue("USER_CLASS2", event.getSourceId(), reason, "USER_CLASS2:" + event.getSourceId());
        return;
    }
    enqueueRepairBothUsers(event.getSourceId(), event.getTargetId(), reason);
}
```

Consumer catch path:

```java
try {
    transactionTemplate.execute(status -> {
        StartResult startResult = consumerRecordService.startManual(eventId, CONSUMER_NAME, "{}");
        processor.process(event);
        consumerRecordService.markDone(eventId, CONSUMER_NAME);
        return true;
    });
} catch (Exception e) {
    repairTransactionTemplate.executeWithoutResult(status -> processor.registerFailureRepair(event, e.getMessage()));
    consumerRecordService.markFail(eventId, CONSUMER_NAME, e.getMessage());
    channel.basicNack(deliveryTag, false, false);
}
```

The Redis delta path is replay-safe because `applyClass2DeltaOnce(...)` is deduped by event id and counter slot, so redelivery after DB rollback must not double-increment.

- [ ] **Step 5: Run processor and consumer tests and verify they pass**

Run:

```bash
mvn -pl nexus-domain -Dtest=RelationCounterProjectionProcessorTest test
mvn -pl nexus-trigger -Dtest=RelationCounterProjectConsumerTest test
```

Expected: PASS

- [ ] **Step 6: Commit the O(1) processor rewrite**

```bash
git add nexus-domain/src/main/java/cn/nexus/domain/social/service/RelationCounterProjectionProcessor.java nexus-domain/src/test/java/cn/nexus/domain/social/service/RelationCounterProjectionProcessorTest.java nexus-trigger/src/main/java/cn/nexus/trigger/listener/social/RelationCounterProjectConsumer.java nexus-trigger/src/test/java/cn/nexus/trigger/listener/social/RelationCounterProjectConsumerTest.java
git commit -m "feat: make class2 projection incremental and repair-backed"
```

## Task 8: Add the Class 2 repair worker

**Files:**
- Create: `nexus-trigger/src/main/java/cn/nexus/trigger/job/social/Class2UserCounterRepairJob.java`
- Create: `nexus-trigger/src/test/java/cn/nexus/trigger/job/social/Class2UserCounterRepairJobTest.java`

- [ ] **Step 1: Write the worker test first**

Cover success, retry, per-user rate-limit release, and lease-expiry reclaim:

```java
verify(userCounterService).repairClass2Counters(11L);
verify(repairTaskRepository).markDone(eq(taskId), anyString());
```

```java
verify(repairTaskRepository).markRetry(eq(taskId), anyString(), any(Date.class), contains("boom"));
```

```java
verify(userCounterService, never()).repairClass2Counters(11L);
```

```java
List<Class2UserCounterRepairTaskVO> claimed = repository.claimBatch("worker-b", 10, nowAfterLease, leaseUntil);
assertEquals(1, claimed.size());
```

- [ ] **Step 2: Implement the scheduled repair job with Redis lock and rate-limit**

Use the existing `CountRedisOperations` primitives with these keys:

```java
String lockKey = "ucnt:repair:lock:" + userId;
String rateKey = "ucnt:repair:rate:" + userId;
```

Core loop:

```java
String owner = hostName + ":" + UUID.randomUUID();
Date now = new Date();
Date leaseUntil = new Date(now.getTime() + 60_000L);
for (Class2UserCounterRepairTaskVO task : repairTaskRepository.claimBatch(owner, 100, now, leaseUntil)) {
    if (!operations.tryAcquireRateLimit(rateKey, 30L)) {
        repairTaskRepository.release(task.getTaskId(), owner, new Date(System.currentTimeMillis() + 5_000L), "rate limit");
        continue;
    }
    if (!operations.tryAcquireRebuildLock(lockKey, 15L)) {
        repairTaskRepository.release(task.getTaskId(), owner, new Date(System.currentTimeMillis() + 5_000L), "user lock busy");
        continue;
    }
    try {
        userCounterService.repairClass2Counters(task.getUserId());
        repairTaskRepository.markDone(task.getTaskId(), owner);
    } catch (Exception e) {
        repairTaskRepository.markRetry(task.getTaskId(), owner, nextRetryTime(task.getRetryCount()), e.getMessage());
    } finally {
        operations.releaseRebuildLock(lockKey);
    }
}
```

- [ ] **Step 3: Run the repair job tests and verify they pass**

Run:

```bash
mvn -pl nexus-trigger -Dtest=Class2UserCounterRepairJobTest test
```

Expected: PASS

- [ ] **Step 4: Commit the repair job**

```bash
git add nexus-trigger/src/main/java/cn/nexus/trigger/job/social/Class2UserCounterRepairJob.java nexus-trigger/src/test/java/cn/nexus/trigger/job/social/Class2UserCounterRepairJobTest.java
git commit -m "feat: add class2 user counter repair job"
```

## Task 9: Add real integration coverage and schema migration

**Files:**
- Create: `nexus-app/src/test/java/cn/nexus/integration/Class2CounterProjectionRealIntegrationTest.java`
- Modify: `nexus-app/src/test/java/cn/nexus/integration/support/RealBusinessIntegrationTestSupport.java`
- Modify: `nexus-app/src/test/java/cn/nexus/integration/ReliableJobRealIntegrationTest.java`
- Create: `docs/migrations/20260426_01_class2_counter_projection_state_and_repair_task.sql`
- Modify: `docs/nexus_final_mysql_schema.sql`

- [ ] **Step 1: Add real integration tests for convergence and redelivery**

Create tests that exercise:

```java
@Test
void followThenRapidUnfollowThenRefollow_shouldConvergeToSingleFollower() {}

@Test
void blockDeliveredBeforeFollowUnfollow_shouldStillConvergeCounters() {}

@Test
void duplicateFollowProjectionDelivery_shouldNotDoubleIncrementCounters() {}

@Test
void postPublishThenUnpublish_shouldConvergePostCount() {}

@Test
void redisDeltaAppliedThenConsumerTransactionRolledBack_shouldNotDoubleIncrementOnReplay() {}

@Test
void repairTask_shouldCorrectRedisDriftAfterInjectedProjectionFailure() {}

@Test
void repairWorkerCrashAfterClaim_shouldBeReclaimedAfterLeaseExpiry() {}

@Test
void multiWorkerClaim_shouldNotExecuteSameRepairTaskTwice() {}

@Test
void deadLetteredProjection_shouldBeReplayableThroughReliableMqReplayJob() {}
```

Use `RealBusinessIntegrationTestSupport.readUserSnapshotCount(...)` to verify the final `ucnt` slots.

- [ ] **Step 2: Add the SQL migration for new tables**

Create:

```sql
CREATE TABLE class2_counter_projection_state (
  projection_key varchar(128) NOT NULL,
  projection_type varchar(32) NOT NULL,
  last_version bigint NOT NULL,
  update_time datetime NOT NULL,
  PRIMARY KEY (projection_key)
);

-- projection_key is the unique business ordering identity.
-- projection_type is audit metadata and must remain stable for a given projection_key.

CREATE TABLE class2_user_counter_repair_task (
  task_id bigint NOT NULL,
  repair_type varchar(32) NOT NULL,
  user_id bigint NOT NULL,
  dedupe_key varchar(64) NOT NULL,
  status varchar(16) NOT NULL,
  retry_count int NOT NULL,
  claim_owner varchar(64) DEFAULT NULL,
  claimed_at datetime DEFAULT NULL,
  lease_until datetime DEFAULT NULL,
  next_retry_time datetime NOT NULL,
  reason varchar(255) DEFAULT NULL,
  last_error varchar(255) DEFAULT NULL,
  create_time datetime NOT NULL,
  update_time datetime NOT NULL,
  PRIMARY KEY (task_id),
  UNIQUE KEY uk_class2_user_counter_repair_task_dedupe (dedupe_key),
  KEY idx_class2_user_counter_repair_task_claim (status, next_retry_time, lease_until)
);
```

- [ ] **Step 3: Refresh the schema doc and run the relevant integration suites**

Run:

```bash
mvn -pl nexus-app -Dtest=Class2CounterProjectionRealIntegrationTest,ReliableJobRealIntegrationTest test
mvn -pl nexus-trigger -Dtest=CounterReplacementContractTest test
```

Expected:

- the new real integration tests PASS
- `CounterReplacementContractTest` still PASSes because no forbidden legacy file names were reintroduced
- dead-lettered relation projection messages are still replayable through the existing `ReliableMqDlqRecorder` + `ReliableMqReplayJob` path

- [ ] **Step 4: Run the final focused verification sweep**

Run:

```bash
mvn -pl nexus-domain -Dtest=RelationServiceTest,ContentServiceTest,RelationCounterProjectionProcessorTest test
mvn -pl nexus-infrastructure -Dtest=UserCounterServiceTest,RelationEventPortTest,Class2CounterProjectionStateRepositoryTest,Class2UserCounterRepairTaskRepositoryTest test
mvn -pl nexus-trigger -Dtest=RelationEventOutboxPublishJobTest,RelationCounterProjectConsumerTest,Class2UserCounterRepairJobTest,CounterReplacementContractTest test
mvn -pl nexus-app -Dtest=Class2CounterProjectionRealIntegrationTest,ReliableJobRealIntegrationTest test
```

Expected: PASS

- [ ] **Step 5: Commit the integration and migration work**

```bash
git add nexus-app/src/test/java/cn/nexus/integration/Class2CounterProjectionRealIntegrationTest.java nexus-app/src/test/java/cn/nexus/integration/support/RealBusinessIntegrationTestSupport.java nexus-app/src/test/java/cn/nexus/integration/ReliableJobRealIntegrationTest.java docs/migrations/20260426_01_class2_counter_projection_state_and_repair_task.sql docs/nexus_final_mysql_schema.sql
git commit -m "test: verify class2 counters converge under redelivery and repair"
```

## Implementation Notes

- The normal `RelationCounterProjectionProcessor` path must not call `rebuildAllCounters(...)`.
- `rebuildAllCounters(...)` remains valid for malformed snapshot recovery and explicit mixed-source maintenance only.
- Relation ordering must use a monotonic follow row version; physical delete plus reinsert is not acceptable because it resets the local entity lifecycle.
- Follow activate/deactivate must lock the row, apply `(status, version)` CAS, and emit the committed post-update version.
- Projection ordering fallback to outbox event id is not needed in this change because follow and post versions are both available after the write-side fixes above.
- Redis is not the correctness boundary for repair dispatch. Redis is only a lock and rate-limit helper around the DB-backed repair queue.
- `user_follower` is a projection table only. `repairClass2Counters(...)` and sampled verification must use `user_relation` / `content_post` truth, not `countFollowerIds(...)`.
- `BLOCK` events may invalidate adjacency cache, but they must not delete `user_follower` rows and must not mutate counters because `BLOCK` and `FOLLOW/UNFOLLOW` are consumed from different queues.
- This plan gives durable eventual consistency and recovery under concurrency only if the existing DLQ recording plus `ReliableMqReplayJob` continue replaying failed relation projection messages.
- This plan does not provide Redis-outage read availability or Redis-free write degradation. If those are required, that is a separate HA change.

## Verification Checklist

- [ ] follow/unfollow/refollow uses one durable follow row and a monotonic `version`
- [ ] follower repair and sampled verification use `countActiveRelationsByTarget(...)`, not `countFollowerIds(...)`
- [ ] normal RabbitMQ projection mutates counters with per-event idempotent Redis delta apply, never with full rebuild
- [ ] old or duplicate versioned events are acked without mutating counters
- [ ] `BLOCK` arriving before paired `FOLLOW UNFOLLOW` does not suppress the required counter decrement
- [ ] Redis/cache side-effect uncertainty enqueues DB-backed repair before marking the consumer record done
- [ ] sampled read-path mismatch for `following`, `follower`, and `post` enqueues Class 2 repair instead of synchronously rebuilding in ordinary requests
- [ ] repair-task claim uses `FOR UPDATE SKIP LOCKED` plus `lease_until` reclaim and never leaves a lock/rate-limited task stuck in `RUNNING`
- [ ] `projection_key` alone defines the durable watermark identity; `projection_type` is metadata, not a second primary-key dimension
- [ ] dead-lettered relation projection messages can be replayed by the existing reliable replay job
- [ ] `repairClass2Counters(...)` only rebuilds `following`, `follower`, `post` and preserves `like_received` / `favorite_received`
- [ ] `CounterReplacementContractTest` stays green
