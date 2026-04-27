# Task 4.4 Sampled Verification Repair Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Update sampled verification to request durable coalesced repair instead of synchronously amplifying rebuilds.

**Architecture:** Read-time verification should not create unbounded DB rebuild load. Valid snapshots with sampled mismatch schedule repair and return current readable values. Missing/malformed snapshots attempt short synchronous Class 2 repair and fall back to durable async repair plus zeros if repair cannot finish quickly.

**Tech Stack:** Java, Redis test helpers, Mockito, Maven.

---

## File Structure

- Modify: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/counter/service/UserCounterService.java`
- Modify: `nexus-infrastructure/src/test/java/cn/nexus/infrastructure/adapter/counter/service/UserCounterServiceTest.java`

## Exact Behavior

- `maybeVerifyRelationSlots` mismatch calls `requestRebuild(userId, "SAMPLE_MISMATCH")`.
- It must not call `rebuildAllCounters`.
- Malformed snapshot:
  - Call `tryRepairClass2Within(userId, 100L)`.
  - If repair succeeds, return repaired counters.
  - If not, call `requestRebuild(userId, "MALFORMED_SNAPSHOT")` and return zeros.

### Task 1: Write Failing Tests

- [ ] **Step 1: Sample mismatch schedules repair**

Set snapshot values different from DB truth, call `readRelationCountersWithVerification`, verify `rebuildRequestRepository.request(51L, "SAMPLE_MISMATCH")` and current snapshot returned.

- [ ] **Step 2: Malformed snapshot fallback schedules repair**

Write malformed Redis payload, configure guarded repair to fail through lock/rate limit, call read, expect zeros and `request(..., "MALFORMED_SNAPSHOT")`.

### Task 2: Implement Methods

- [ ] **Step 1: Implement `requestRebuild`**

Delegate to `IUserCounterRebuildRequestRepository.request`.

- [ ] **Step 2: Implement `tryRepairClass2Within`**

Use elapsed time checks around guarded lock and DB reads. Do not spawn an executor. If timeout or lock/rate limit fails, return `zeros()`.

- [ ] **Step 3: Update sampled verification**

Replace `rebuildAllCounters(userId)` with `requestRebuild(userId, "SAMPLE_MISMATCH")`.

### Task 3: Verify

- [ ] **Step 1: Run focused tests**

```powershell
& 'C:\Users\Administrator\Desktop\文档\.codex\tools\apache-maven-3.9.6\bin\mvn.cmd' -pl nexus-infrastructure -Dtest=UserCounterServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 2: Search synchronous rebuild in sampled verification**

```powershell
rg -n "maybeVerifyRelationSlots|SAMPLE_MISMATCH|rebuildAllCounters" nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/counter/service/UserCounterService.java
```

Expected: `maybeVerifyRelationSlots` references `requestRebuild`, not `rebuildAllCounters`.

- [ ] **Step 3: Commit**

```powershell
git add nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/counter/service/UserCounterService.java nexus-infrastructure/src/test/java/cn/nexus/infrastructure/adapter/counter/service/UserCounterServiceTest.java
git commit -m "feat: schedule sampled counter verification repair"
```

## Ambiguity Review

No architecture choice remains. Valid-but-drifted reads return current snapshot and schedule durable repair; malformed reads get one bounded repair attempt.
