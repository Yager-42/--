# Task 4.2 Rebuild Worker Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a bounded scheduled worker to drain durable dirty-user rebuild requests.

**Architecture:** The worker is a trigger-layer scheduled job that delegates to `IUserCounterService.drainRequestedRebuilds(limit)`. The service owns row status transitions, stale `PROCESSING` recovery, coalescing-window deferral, and repair execution so tests can run without Spring scheduling.

**Tech Stack:** Java, Spring `@Scheduled`, Spring properties, JUnit 5, Mockito, Maven.

---

## File Structure

- Create: `nexus-trigger/src/main/java/cn/nexus/trigger/job/counter/UserCounterRebuildRequestJob.java`
- Create: `nexus-trigger/src/test/java/cn/nexus/trigger/job/counter/UserCounterRebuildRequestJobTest.java`
- Modify: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/counter/service/UserCounterService.java`
- Modify: `nexus-infrastructure/src/test/java/cn/nexus/infrastructure/adapter/counter/service/UserCounterServiceTest.java`

## Exact Behavior

- Default batch size: `50`.
- Default fixed delay: `5000ms`.
- Default coalescing window: `300s`.
- Default stale processing timeout: `300s`.
- Each request:
  - If `lastRebuildTime` is inside the coalescing window -> call `deferForCoalescingWindow(userId, windowEnd)` and skip actual repair.
  - `markProcessing(userId)` false -> skip.
  - true -> `forceRebuildClass2Counters(userId)`.
  - success -> `markDone(userId)`.
  - failure -> `markFail(userId, error, nextRetryTime)`.
- Backoff: `min(30 minutes, max(60 seconds, requestCount * 60 seconds))`.

### Task 1: Service Drain Tests

- [ ] **Step 1: Add success drain test**

Mock repository fetch returning one request. Verify `markProcessing`, `forceRebuildClass2Counters`, and `markDone`.

- [ ] **Step 2: Add failure drain test**

Force repair throws. Verify `markFail` called with non-null `nextRetryTime`.

- [ ] **Step 3: Add coalescing-window deferral test**

```java
@Test
void drainRequestedRebuilds_shouldDeferWhenLastRebuildInsideWindow() {
    UserCounterRebuildRequestVO request = UserCounterRebuildRequestVO.builder()
            .userId(42L)
            .status("PENDING")
            .requestCount(3)
            .lastRebuildTime(new Date(System.currentTimeMillis() - 60_000L))
            .build();
    when(rebuildRequestRepository.fetchDue(50)).thenReturn(List.of(request));

    int drained = service.drainRequestedRebuilds(50);

    assertThat(drained).isZero();
    verify(rebuildRequestRepository).deferForCoalescingWindow(eq(42L), any(Date.class));
    verify(rebuildRequestRepository, never()).markProcessing(any());
}
```

- [ ] **Step 4: Add stale processing retry test**

Repository `fetchDue` already returns stale `PROCESSING` rows. Verify the service attempts `markProcessing` and repair for a request with status `PROCESSING`; Class 2 repair is idempotent, so retry is safe.

### Task 2: Implement Service Drain

- [ ] **Step 1: Inject `IUserCounterRebuildRequestRepository`**

Update constructor and existing tests to pass a mock.

- [ ] **Step 2: Implement `drainRequestedRebuilds`**

Algorithm:

```text
for request in rebuildRequestRepository.fetchDue(limit):
  if request.lastRebuildTime is within 300s coalescing window:
    rebuildRequestRepository.deferForCoalescingWindow(request.userId, lastRebuildTime + 300s)
    continue
  if markProcessing(request.userId) is false:
    continue
  try:
    forceRebuildClass2Counters(request.userId)
    markDone(request.userId)
    done++
  catch:
    markFail(request.userId, error, now + backoff)
return done
```

Return number of successfully marked `DONE` rows, not fetched rows.

### Task 3: Add Scheduled Job

- [ ] **Step 1: Create job class**

```java
@Component
@RequiredArgsConstructor
public class UserCounterRebuildRequestJob {
    private final IUserCounterService userCounterService;

    @Value("${counter.user-rebuild.batch-size:50}")
    private int batchSize;

    @Scheduled(fixedDelayString = "${counter.user-rebuild.fixed-delay-ms:5000}")
    public void drain() {
        userCounterService.drainRequestedRebuilds(batchSize);
    }
}
```

- [ ] **Step 2: Add job test**

Instantiate job, set `batchSize` with ReflectionTestUtils, call `drain`, verify service call.

### Task 4: Verify

- [ ] **Step 1: Run tests**

```powershell
& 'C:\Users\Administrator\Desktop\文档\.codex\tools\apache-maven-3.9.6\bin\mvn.cmd' -pl nexus-infrastructure,nexus-trigger -am -Dtest=UserCounterServiceTest,UserCounterRebuildRequestJobTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 2: Commit**

```powershell
git add nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/counter/service/UserCounterService.java nexus-infrastructure/src/test/java/cn/nexus/infrastructure/adapter/counter/service/UserCounterServiceTest.java nexus-trigger/src/main/java/cn/nexus/trigger/job/counter/UserCounterRebuildRequestJob.java nexus-trigger/src/test/java/cn/nexus/trigger/job/counter/UserCounterRebuildRequestJobTest.java
git commit -m "feat: drain user counter rebuild requests"
```

## Ambiguity Review

No architecture choice remains. Worker is bounded, retryable, and repair-only.
