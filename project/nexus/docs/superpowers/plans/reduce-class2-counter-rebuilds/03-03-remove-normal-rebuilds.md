# Task 3.3 Remove Normal Rebuild Calls Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove all normal-path `rebuildAllCounters` calls from `RelationCounterProjectionProcessor`.

**Architecture:** Normal Class 2 projection is edge detection plus durable delta outbox enqueue. Full rebuild is repair-only and belongs to `UserCounterService` repair APIs or operator/test paths, not RabbitMQ projection processing.

**Tech Stack:** Java, ripgrep, Maven, JUnit 5.

---

## File Structure

- Modify: `nexus-domain/src/main/java/cn/nexus/domain/social/service/RelationCounterProjectionProcessor.java`
- Modify: `nexus-domain/src/test/java/cn/nexus/domain/social/service/RelationCounterProjectionProcessorTest.java`

## Exact Behavior

- No `rebuildAllCounters` references in `RelationCounterProjectionProcessor`.
- No private helper that indirectly rebuilds relation counters.
- Tests verify `never().rebuildAllCounters(any())` for follow, duplicate, block, and post paths.

### Task 1: Search And Remove

- [ ] **Step 1: Search current forbidden calls**

```powershell
rg -n "rebuildAllCounters|rebuildRelationCounters" nexus-domain/src/main/java/cn/nexus/domain/social/service/RelationCounterProjectionProcessor.java
```

Expected before implementation: matches in post and relation helper paths.

- [ ] **Step 2: Remove helper and call sites**

Replace call sites with delta outbox enqueue logic from tasks 3.1 and 3.2. Do not leave dead private methods.

### Task 2: Verify

- [ ] **Step 1: Run exact search**

```powershell
rg -n "rebuildAllCounters|rebuildRelationCounters|incrementFollowings|incrementFollowers|incrementPosts" nexus-domain/src/main/java/cn/nexus/domain/social/service/RelationCounterProjectionProcessor.java
```

Expected after implementation: no output.

- [ ] **Step 2: Run tests**

```powershell
& 'C:\Users\Administrator\Desktop\文档\.codex\tools\apache-maven-3.9.6\bin\mvn.cmd' -pl nexus-domain -Dtest=RelationCounterProjectionProcessorTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Commit**

```powershell
git add nexus-domain/src/main/java/cn/nexus/domain/social/service/RelationCounterProjectionProcessor.java nexus-domain/src/test/java/cn/nexus/domain/social/service/RelationCounterProjectionProcessorTest.java
git commit -m "refactor: remove normal class2 projection rebuilds"
```

## Ambiguity Review

No architecture choice remains. `rebuildAllCounters` is repair-only and must not appear in the processor.
