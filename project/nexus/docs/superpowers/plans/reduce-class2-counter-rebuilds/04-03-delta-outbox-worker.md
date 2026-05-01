# Task 4.3 Delta Outbox Worker Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a bounded worker that applies durable `user_counter_delta_outbox` rows to Redis snapshots and safely handles stale `PROCESSING` rows without double-applying increments.

**Architecture:** Normal Class 2 projection commits MySQL state plus delta rows first. This worker is the only normal path that applies pending/failed deltas to Redis through existing `IUserCounterService` increment methods. Stale `PROCESSING` rows are unknown-result rows; they must not be replayed as increments and instead schedule Class 2 repair for the affected user.

**Tech Stack:** Java, MyBatis XML, MySQL, Redis service methods, Spring `@Scheduled`, JUnit 5, Mockito, Maven.

---

## File Structure

- Create: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/dao/counter/IUserCounterDeltaOutboxDao.java`
- Create: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/dao/counter/po/UserCounterDeltaOutboxPO.java`
- Create: `nexus-infrastructure/src/main/resources/mapper/counter/UserCounterDeltaOutboxMapper.xml`
- Create: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/counter/repository/UserCounterDeltaOutboxRepository.java`
- Create: `nexus-trigger/src/main/java/cn/nexus/trigger/job/counter/UserCounterDeltaOutboxJob.java`
- Create: `nexus-trigger/src/test/java/cn/nexus/trigger/job/counter/UserCounterDeltaOutboxJobTest.java`
- Modify: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/counter/service/UserCounterService.java`
- Modify: `docs/migrations/20260427_01_class2_counter_projection_tables.sql`
- Modify: `docs/nexus_final_mysql_schema.sql`

## DDL

```sql
CREATE TABLE IF NOT EXISTS user_counter_delta_outbox (
  id BIGINT NOT NULL AUTO_INCREMENT,
  source_event_id BIGINT NOT NULL,
  counter_user_id BIGINT NOT NULL,
  counter_type VARCHAR(32) NOT NULL,
  delta_value BIGINT NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  retry_count INT NOT NULL DEFAULT 0,
  next_retry_time DATETIME NULL,
  processing_time DATETIME NULL,
  last_error VARCHAR(512) NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_event_user_type (source_event_id, counter_user_id, counter_type),
  KEY idx_status_retry_time (status, next_retry_time, processing_time, update_time)
);
```

Allowed statuses: `PENDING`, `PROCESSING`, `DONE`, `FAIL`, `UNKNOWN_APPLIED`.

### Task 1: Add Storage With Unknown-Result Recovery

- [ ] **Step 1: Create DAO**

Methods:

```java
int insertIgnore(UserCounterDeltaOutboxPO po);
List<UserCounterDeltaOutboxPO> fetchRetryable(@Param("now") Date now, @Param("limit") int limit);
List<UserCounterDeltaOutboxPO> fetchStaleProcessing(@Param("staleBefore") Date staleBefore, @Param("limit") int limit);
int markProcessing(@Param("id") Long id);
int markDone(@Param("id") Long id);
int markFail(@Param("id") Long id, @Param("lastError") String lastError, @Param("nextRetryTime") Date nextRetryTime);
int markUnknownApplied(@Param("id") Long id, @Param("lastError") String lastError);
```

- [ ] **Step 2: Create mapper fetch query**

`fetchRetryable` fetches only rows where:

```sql
(status = 'PENDING')
OR (status = 'FAIL' AND (next_retry_time IS NULL OR next_retry_time <= #{now}))
```

Order by `update_time ASC`, limit by `#{limit}`.

`fetchStaleProcessing` fetches rows where:

```sql
status = 'PROCESSING' AND processing_time <= #{staleBefore}
```

This separate query is required because stale rows are not retryable deltas.

### Task 2: Implement Repository

- [ ] **Step 1: Implement idempotent enqueue**

Use `INSERT IGNORE` or equivalent. Null `sourceEventId`, `counterUserId`, `counterType`, or zero `delta` is a no-op.

- [ ] **Step 2: Implement status transitions**

`markProcessing` sets status `PROCESSING`, `processing_time = NOW()`, and only updates rows currently in `PENDING` or retryable `FAIL`.

- [ ] **Step 3: Implement `markUnknownApplied`**

This sets `status = 'UNKNOWN_APPLIED'`, stores `last_error`, and must not modify `retry_count`. Unknown rows are terminal for delta replay.

### Task 3: Implement Service Drain

- [ ] **Step 1: Inject `IUserCounterDeltaOutboxRepository`**

Update `UserCounterService` constructor and tests.

- [ ] **Step 2: Implement apply switch**

```java
switch (row.getCounterType()) {
    case FOLLOWING -> incrementFollowings(row.getCounterUserId(), row.getDelta());
    case FOLLOWER -> incrementFollowers(row.getCounterUserId(), row.getDelta());
    case POST -> incrementPosts(row.getCounterUserId(), row.getDelta());
    default -> throw new IllegalArgumentException("unsupported counter type");
}
```

- [ ] **Step 3: Implement `drainCounterDeltas(limit)`**

Algorithm:

```text
staleRows = deltaOutboxRepository.fetchStaleProcessing(staleBefore, limit)
for each stale row:
  deltaOutboxRepository.markUnknownApplied(row.id, "stale processing; scheduled class2 repair")
  requestRebuild(row.counterUserId, "DELTA_UNKNOWN_RESULT")

remainingLimit = limit - staleRows.size
retryableRows = deltaOutboxRepository.fetchRetryable(now, remainingLimit)
for each retryable row:
  if markProcessing(row.id) is false: continue
  try:
    apply one Redis increment by counter type
    markDone(row.id)
  catch:
    markFail(row.id, error, backoff)
```

Return the number of rows marked `DONE` plus rows marked `UNKNOWN_APPLIED`. Do not call increment methods for stale `PROCESSING` rows.

- [ ] **Step 4: Add service tests for unknown-result recovery**

```java
@Test
void drainCounterDeltas_shouldNotReplayStaleProcessingRowsAndShouldRequestRepair() {
    UserCounterDeltaOutboxVO stale = UserCounterDeltaOutboxVO.builder()
            .id(9L)
            .counterUserId(42L)
            .counterType(UserCounterType.FOLLOWING)
            .delta(1L)
            .status("PROCESSING")
            .build();
    when(deltaOutboxRepository.fetchStaleProcessing(any(), eq(200))).thenReturn(List.of(stale));
    when(deltaOutboxRepository.fetchRetryable(any(), anyInt())).thenReturn(List.of());

    int drained = service.drainCounterDeltas(200);

    assertThat(drained).isEqualTo(1);
    verify(deltaOutboxRepository).markUnknownApplied(9L, "stale processing; scheduled class2 repair");
    verify(rebuildRequestRepository).request(42L, "DELTA_UNKNOWN_RESULT");
    verify(redisTemplate, never()).opsForValue();
}
```

### Task 4: Add Scheduled Job

- [ ] **Step 1: Create job**

Defaults:

```java
@Value("${counter.user-delta.batch-size:200}")
private int batchSize;

@Scheduled(fixedDelayString = "${counter.user-delta.fixed-delay-ms:1000}")
public void drain() {
    userCounterService.drainCounterDeltas(batchSize);
}
```

- [ ] **Step 2: Add job test**

Verify configured batch size delegates to service.

### Task 5: Verify

- [ ] **Step 1: Run tests**

```powershell
& 'C:\Users\Administrator\Desktop\文档\.codex\tools\apache-maven-3.9.6\bin\mvn.cmd' -pl nexus-infrastructure,nexus-trigger -am -Dtest=UserCounterDeltaOutboxRepositoryTest,UserCounterServiceTest,UserCounterDeltaOutboxJobTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: `BUILD SUCCESS`. The tests must include a stale `PROCESSING` case that proves no Redis increment method is called.

- [ ] **Step 2: Commit**

```powershell
git add nexus-infrastructure/src/main/java/cn/nexus/infrastructure/dao/counter nexus-infrastructure/src/main/resources/mapper/counter/UserCounterDeltaOutboxMapper.xml nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/counter/repository/UserCounterDeltaOutboxRepository.java nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/counter/service/UserCounterService.java nexus-trigger/src/main/java/cn/nexus/trigger/job/counter/UserCounterDeltaOutboxJob.java nexus-trigger/src/test/java/cn/nexus/trigger/job/counter/UserCounterDeltaOutboxJobTest.java docs/migrations/20260427_01_class2_counter_projection_tables.sql docs/nexus_final_mysql_schema.sql
git commit -m "feat: apply durable user counter deltas"
```

## Ambiguity Review

No architecture choice remains. Pending/failed delta apply is async, durable, and per counter slot; stale `PROCESSING` rows are terminalized and repaired, not replayed.
