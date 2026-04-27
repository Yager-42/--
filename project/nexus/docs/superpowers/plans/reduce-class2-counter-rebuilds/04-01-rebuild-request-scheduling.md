# Task 4.1 Rebuild Request Scheduling Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement MySQL-backed dirty-user rebuild scheduling with per-user coalescing and retry metadata.

**Architecture:** `user_counter_rebuild_request` is the durable repair queue. It is keyed by `user_id`, so repeated requests coalesce into one row with incremented `request_count`. The row includes `processing_time` for worker-crash recovery and `last_rebuild_time` for the configured coalescing window. This queue is for repair only; normal Class 2 projection uses `user_counter_delta_outbox`.

**Tech Stack:** Java, MyBatis XML, MySQL, Spring repository, Maven.

---

## File Structure

- Create: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/dao/counter/IUserCounterRebuildRequestDao.java`
- Create: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/dao/counter/po/UserCounterRebuildRequestPO.java`
- Create: `nexus-infrastructure/src/main/resources/mapper/counter/UserCounterRebuildRequestMapper.xml`
- Create: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/counter/repository/UserCounterRebuildRequestRepository.java`
- Modify: `docs/migrations/20260427_01_class2_counter_projection_tables.sql`
- Modify: `docs/nexus_final_mysql_schema.sql`

## DDL

```sql
CREATE TABLE IF NOT EXISTS user_counter_rebuild_request (
  user_id BIGINT NOT NULL,
  reason VARCHAR(64) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  request_count INT NOT NULL DEFAULT 1,
  next_retry_time DATETIME NULL,
  processing_time DATETIME NULL,
  last_rebuild_time DATETIME NULL,
  last_error VARCHAR(512) NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (user_id),
  KEY idx_status_retry_time (status, next_retry_time, processing_time, update_time),
  KEY idx_last_rebuild_time (last_rebuild_time)
);
```

Allowed statuses: `PENDING`, `PROCESSING`, `DONE`, `FAIL`.

### Task 1: Add MyBatis Storage

- [ ] **Step 1: Create PO**

Fields match the DDL exactly using Java names: `userId`, `reason`, `status`, `requestCount`, `nextRetryTime`, `processingTime`, `lastRebuildTime`, `lastError`, `createTime`, `updateTime`.

- [ ] **Step 2: Create DAO**

```java
int upsertRequest(@Param("userId") Long userId, @Param("reason") String reason);
List<UserCounterRebuildRequestPO> fetchDue(@Param("now") Date now,
                                           @Param("staleBefore") Date staleBefore,
                                           @Param("limit") int limit);
int markProcessing(@Param("userId") Long userId);
int markDone(@Param("userId") Long userId);
int markFail(@Param("userId") Long userId, @Param("lastError") String lastError, @Param("nextRetryTime") Date nextRetryTime);
int deferForCoalescingWindow(@Param("userId") Long userId, @Param("nextRetryTime") Date nextRetryTime);
```

- [ ] **Step 3: Mapper upsert**

```sql
INSERT INTO user_counter_rebuild_request(user_id, reason, status, request_count, create_time, update_time)
VALUES(#{userId}, #{reason}, 'PENDING', 1, NOW(), NOW())
ON DUPLICATE KEY UPDATE
  reason = VALUES(reason),
  status = IF(status = 'PROCESSING', status, 'PENDING'),
  request_count = request_count + 1,
  next_retry_time = IF(status = 'PROCESSING', next_retry_time, NULL),
  last_error = NULL,
  update_time = NOW()
```

Do not overwrite `processing_time` or `last_rebuild_time` during upsert.

### Task 2: Add Repository

- [ ] **Step 1: Implement `request`**

Validate null `userId`; truncate reason to 64 characters; call `dao.upsertRequest`.

- [ ] **Step 2: Implement `fetchDue`**

Limit must be clamped: minimum `1`, maximum `500`. Query:

```sql
WHERE
  status = 'PENDING'
  OR (status = 'FAIL' AND (next_retry_time IS NULL OR next_retry_time <= #{now}))
  OR (status = 'PROCESSING' AND processing_time <= #{staleBefore})
ORDER BY update_time ASC
LIMIT #{limit}
```

Stale `PROCESSING` rows are eligible for repair retry because Class 2 repair is idempotent from MySQL truth.

- [ ] **Step 3: Implement status mutations**

`markProcessing` returns true only when DAO updated one row from eligible status to `PROCESSING`; it must set `processing_time = NOW()`.

- [ ] **Step 4: Implement coalescing deferral**

`deferForCoalescingWindow(userId, nextRetryTime)` sets `status='PENDING'`, `next_retry_time=#{nextRetryTime}`, and clears `processing_time`. The worker uses this when `last_rebuild_time` is inside the configured coalescing window.

### Task 3: Verify

- [ ] **Step 1: Run repository tests**

```powershell
& 'C:\Users\Administrator\Desktop\文档\.codex\tools\apache-maven-3.9.6\bin\mvn.cmd' -pl nexus-infrastructure -Dtest=UserCounterRebuildRequestRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 2: Commit**

```powershell
git add nexus-infrastructure/src/main/java/cn/nexus/infrastructure/dao/counter nexus-infrastructure/src/main/resources/mapper/counter/UserCounterRebuildRequestMapper.xml nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/counter/repository/UserCounterRebuildRequestRepository.java docs/migrations/20260427_01_class2_counter_projection_tables.sql docs/nexus_final_mysql_schema.sql
git commit -m "feat: add durable user counter rebuild requests"
```

## Ambiguity Review

No architecture choice remains. This table is repair-only and per-user coalesced.
