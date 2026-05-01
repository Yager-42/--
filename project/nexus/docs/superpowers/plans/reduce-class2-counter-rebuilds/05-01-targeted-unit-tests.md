# Task 5.1 Targeted Unit Tests Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Run and stabilize targeted unit tests for the Class 2 counter projection rewrite.

**Architecture:** Verification must cover processor edge detection, projection-state repository behavior, rebuild request storage, delta outbox storage, and counter service repair/apply behavior. This task does not add new architecture; it proves the previous tasks work together at unit level.

**Tech Stack:** Maven, JUnit 5, Mockito, MyBatis repository tests.

---

## File Structure

- Verify: `nexus-domain/src/test/java/cn/nexus/domain/social/service/RelationCounterProjectionProcessorTest.java`
- Verify: `nexus-infrastructure/src/test/java/cn/nexus/infrastructure/adapter/counter/service/UserCounterServiceTest.java`
- Verify: `nexus-infrastructure/src/test/java/cn/nexus/infrastructure/adapter/social/repository/PostCounterProjectionRepositoryTest.java`
- Verify: `nexus-infrastructure/src/test/java/cn/nexus/infrastructure/adapter/counter/repository/UserCounterRebuildRequestRepositoryTest.java`
- Verify: `nexus-infrastructure/src/test/java/cn/nexus/infrastructure/adapter/counter/repository/UserCounterDeltaOutboxRepositoryTest.java`

## Exact Verification Command

```powershell
& 'C:\Users\Administrator\Desktop\文档\.codex\tools\apache-maven-3.9.6\bin\mvn.cmd' -pl nexus-domain,nexus-infrastructure -am -Dtest=RelationCounterProjectionProcessorTest,UserCounterServiceTest,PostCounterProjectionRepositoryTest,UserCounterRebuildRequestRepositoryTest,UserCounterDeltaOutboxRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false test
```

### Task 1: Run Targeted Tests

- [ ] **Step 1: Execute command**

Expected: all listed tests run and pass. If Maven reports "No tests matching pattern", add or rename the missing test before proceeding.

- [ ] **Step 2: Fix failures with TDD discipline**

For each failure, identify the smallest owning task plan. Fix implementation or test only within that task's scope.

- [ ] **Step 3: Re-run exact command**

Expected: `BUILD SUCCESS`.

### Task 2: Record Evidence

- [ ] **Step 1: Capture command and result in implementation notes**

Add a short note to the final implementation handoff or PR description:

```text
Targeted unit tests:
<command>
Result: BUILD SUCCESS
```

- [ ] **Step 2: Commit only if test-only fixes were needed**

```powershell
git add nexus-domain/src/test/java nexus-infrastructure/src/test/java
git commit -m "test: stabilize class2 counter targeted tests"
```

## Ambiguity Review

No architecture choice remains. This task is verification-only.
