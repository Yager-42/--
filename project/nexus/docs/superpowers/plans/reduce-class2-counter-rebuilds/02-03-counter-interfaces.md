# Task 2.3 Counter Interface Expansion Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend counter domain interfaces with repair APIs and durable delta-outbox APIs while preserving existing public count reads.

**Architecture:** Keep `IUserCounterService` responsible for read/repair and Redis apply operations. Add separate repository contracts for durable MySQL queues: `IUserCounterRebuildRequestRepository` and `IUserCounterDeltaOutboxRepository`. Processors enqueue delta rows via the repository; workers apply retryable rows via `IUserCounterService`; stale `PROCESSING` delta rows are terminalized and converted into repair requests.

**Tech Stack:** Java, Lombok value objects, JUnit 5, Mockito, Maven.

---

## File Structure

- Modify: `nexus-domain/src/main/java/cn/nexus/domain/counter/adapter/service/IUserCounterService.java`
- Create: `nexus-domain/src/main/java/cn/nexus/domain/counter/adapter/repository/IUserCounterRebuildRequestRepository.java`
- Create: `nexus-domain/src/main/java/cn/nexus/domain/counter/adapter/repository/IUserCounterDeltaOutboxRepository.java`
- Create: `nexus-domain/src/main/java/cn/nexus/domain/counter/model/valobj/UserCounterRebuildRequestVO.java`
- Create: `nexus-domain/src/main/java/cn/nexus/domain/counter/model/valobj/UserCounterDeltaOutboxVO.java`
- Create: `nexus-domain/src/test/java/cn/nexus/domain/counter/adapter/repository/UserCounterContractCompilationTest.java`

## Interface Contracts

`IUserCounterService` additions:

```java
void requestRebuild(Long userId, String reason);
UserRelationCounterVO tryRepairClass2Within(Long userId, long timeoutMillis);
void forceRebuildClass2Counters(Long userId);
int drainRequestedRebuilds(int limit);
int drainCounterDeltas(int limit);
```

`IUserCounterRebuildRequestRepository`:

```java
void request(Long userId, String reason);
List<UserCounterRebuildRequestVO> fetchDue(int limit);
boolean markProcessing(Long userId);
void markDone(Long userId);
void markFail(Long userId, String error, Date nextRetryTime);
void deferForCoalescingWindow(Long userId, Date nextRetryTime);
```

`IUserCounterDeltaOutboxRepository`:

```java
void enqueue(Long sourceEventId, Long counterUserId, UserCounterType counterType, long delta);
List<UserCounterDeltaOutboxVO> fetchRetryable(Date now, int limit);
List<UserCounterDeltaOutboxVO> fetchStaleProcessing(Date staleBefore, int limit);
boolean markProcessing(Long id);
void markDone(Long id);
void markFail(Long id, String error, Date nextRetryTime);
void markUnknownApplied(Long id, String error);
```

### Task 1: Write Failing Contract Compilation Test

- [ ] **Step 1: Add test that compiles against intended interfaces**

```java
@Test
void counterRepositoryContracts_shouldExposeRepairAndDeltaMethods() {
    IUserCounterRebuildRequestRepository rebuildRepository = mock(IUserCounterRebuildRequestRepository.class);
    IUserCounterDeltaOutboxRepository deltaRepository = mock(IUserCounterDeltaOutboxRepository.class);

    rebuildRepository.request(42L, "DELTA_UNKNOWN_RESULT");
    rebuildRepository.fetchDue(50);
    rebuildRepository.markProcessing(42L);
    rebuildRepository.markDone(42L);
    rebuildRepository.markFail(eq(42L), eq("boom"), any(Date.class));
    rebuildRepository.deferForCoalescingWindow(eq(42L), any(Date.class));

    deltaRepository.enqueue(1001L, 42L, UserCounterType.FOLLOWING, 1L);
    deltaRepository.fetchRetryable(new Date(), 200);
    deltaRepository.fetchStaleProcessing(new Date(), 200);
    deltaRepository.markProcessing(1L);
    deltaRepository.markDone(1L);
    deltaRepository.markFail(eq(1L), eq("boom"), any(Date.class));
    deltaRepository.markUnknownApplied(1L, "stale processing; scheduled class2 repair");
}
```

- [ ] **Step 2: Run test and confirm failure**

```powershell
& 'C:\Users\Administrator\Desktop\文档\.codex\tools\apache-maven-3.9.6\bin\mvn.cmd' -pl nexus-domain -Dtest=UserCounterContractCompilationTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected before implementation: compilation fails because repository interfaces and VOs do not exist.

### Task 2: Add Value Objects

- [ ] **Step 1: Create `UserCounterRebuildRequestVO`**

Fields: `userId`, `reason`, `status`, `requestCount`, `nextRetryTime`, `processingTime`, `lastRebuildTime`, `lastError`, `createTime`, `updateTime`.

- [ ] **Step 2: Create `UserCounterDeltaOutboxVO`**

Fields: `id`, `sourceEventId`, `counterUserId`, `counterType`, `delta`, `status`, `retryCount`, `nextRetryTime`, `processingTime`, `lastError`, `createTime`, `updateTime`. Allowed statuses are `PENDING`, `PROCESSING`, `DONE`, `FAIL`, and `UNKNOWN_APPLIED`.

### Task 3: Add Repository Interfaces

- [ ] **Step 1: Create rebuild request repository interface**

Use the exact method signatures from the interface contract section. `fetchDue` may include stale `PROCESSING` repair requests because Class 2 repair is idempotent.

- [ ] **Step 2: Create delta outbox repository interface**

Use one row per counter slot and require idempotent enqueue by `(sourceEventId, counterUserId, counterType)`. `fetchRetryable` must not include stale `PROCESSING` rows; `fetchStaleProcessing` is handled separately so callers can schedule Class 2 repair instead of replaying the delta.

### Task 4: Extend Service Interface

- [ ] **Step 1: Modify `IUserCounterService`**

Add methods without removing existing methods. `rebuildAllCounters(Long userId)` stays for compatibility but new normal processor code must not call it.

- [ ] **Step 2: Run contract test**

```powershell
& 'C:\Users\Administrator\Desktop\文档\.codex\tools\apache-maven-3.9.6\bin\mvn.cmd' -pl nexus-domain -Dtest=UserCounterContractCompilationTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Commit**

```powershell
git add nexus-domain/src/main/java/cn/nexus/domain/counter nexus-domain/src/test/java/cn/nexus/domain/counter/adapter/repository/UserCounterContractCompilationTest.java
git commit -m "feat: add counter repair and delta outbox contracts"
```

## Ambiguity Review

No architecture choice remains. Processors write delta outbox; workers apply retryable rows; stale delta processing schedules repair.
