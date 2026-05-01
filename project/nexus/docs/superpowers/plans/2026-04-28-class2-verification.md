# Class 2 Verification — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Run full test suite to verify INCRBY-based Class 2 projection is correct, complete, and coherent.

**Architecture:** Three-layer verification: unit tests (processor + service), trigger consumer tests, and integration smoke tests.

**Tech Stack:** JUnit Jupiter 5, Maven Surefire, Spring Boot Test

---

### Task R10: Run unit tests for processor and counter service

- [ ] **Step 1: Run processor unit tests**

```bash
cd /Users/rr/Desktop/revive/--/project/nexus && mvn test -pl nexus-domain -Dtest=RelationCounterProjectionProcessorTest -DfailIfNoTests=false
```
Expected: **All 11 tests PASS**

If any test fails, debug and fix before proceeding:
- Check that `incrementFollowings`/`incrementFollowers`/`incrementPosts` were called with correct arguments
- Check that `rebuildAllCounters` was never called
- Check that edge detector stubs return correct values

- [ ] **Step 2: Run counter service tests**

```bash
cd /Users/rr/Desktop/revive/--/project/nexus && mvn test -pl nexus-infrastructure -Dtest=UserCounterServiceTest -DfailIfNoTests=false
```
Expected: **All tests PASS**

Verify:
- `rebuildAllCounters` no longer triggers `sumLikeReceivedBestEffort` for like_received
- `rebuildClass2Slots` correctly preserves existing like_received value
- Following, follower, post slots rebuilt from MySQL COUNT as before
- **Note:** If any existing test asserts non-zero `like_received` after `rebuildAllCounters`, update it — `like_received` now defaults to 0 on fresh rebuild

- [ ] **Step 3: Commit (if any test fixes were needed)**

```bash
git add -A && git diff --cached --stat
# If nothing to commit: done
```

---

### Task R11: Run trigger consumer idempotency tests

- [ ] **Step 1: Run consumer tests**

```bash
cd /Users/rr/Desktop/revive/--/project/nexus && mvn test -pl nexus-trigger -Dtest=RelationCounterProjectConsumerTest -DfailIfNoTests=false
```
Expected: **All tests PASS**

Verify:
- `DUPLICATE_DONE` results are acked without re-processing
- `STARTED` results proceed through processor and mark DONE
- `InProgressRedeliveryException` triggers nack with requeue

- [ ] **Step 2: If consumer test file doesn't exist, skip**

The consumer test may not exist yet. Skip if `RelationCounterProjectConsumerTest` not found.

---

### Task R12: Run integration / smoke tests

- [ ] **Step 1: Run outbox publish job tests**

```bash
cd /Users/rr/Desktop/revive/--/project/nexus && mvn test -pl nexus-trigger -Dtest=RelationEventOutboxPublishJobTest -DfailIfNoTests=false
```
Expected: **All tests PASS**

- [ ] **Step 2: Run full test suite for changed modules**

```bash
cd /Users/rr/Desktop/revive/--/project/nexus && mvn verify -pl nexus-domain,nexus-infrastructure,nexus-trigger -am
```
Expected: **BUILD SUCCESS**

- [ ] **Step 3: Final commit**

```bash
git add -A
git status
# Verify no unexpected changes, commit if needed
git commit -m "verify: all tests pass for INCRBY class2 projection rewrite"
```
