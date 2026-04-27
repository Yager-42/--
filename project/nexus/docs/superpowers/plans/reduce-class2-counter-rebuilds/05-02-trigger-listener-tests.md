# Task 5.2 Trigger Listener Tests Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Verify RabbitMQ consumer idempotency marks events done only after durable projection and delta enqueue succeed.

**Architecture:** `RelationCounterProjectConsumer` wraps consumer idempotency and processor execution in a transaction. With delta outbox, `markDone` means MySQL projection state and delta rows are durable; Redis application can still lag asynchronously.

**Tech Stack:** Java, Spring transaction template tests, Mockito, Maven.

---

## File Structure

- Modify/Verify: `nexus-trigger/src/test/java/cn/nexus/trigger/listener/social/RelationCounterProjectConsumerTest.java`
- Verify: `nexus-trigger/src/main/java/cn/nexus/trigger/listener/social/RelationCounterProjectConsumer.java`

## Exact Behavior

- `DUPLICATE_DONE` -> ack, processor not called.
- `STARTED` + processor success -> `markDone`, ack.
- `STARTED` + processor throws -> `markFail`, nack without requeue.
- `IN_PROGRESS` -> nack with requeue.
- No test should assert Redis counter visibility immediately after consumer success.

### Task 1: Add/Update Tests

- [ ] **Step 1: Processor success verifies done after process**

Use Mockito `InOrder`:

```java
InOrder inOrder = inOrder(consumerRecordService, processor);
inOrder.verify(consumerRecordService).startManual(eventId, "RelationCounterProjectConsumer", "{}");
inOrder.verify(processor).process(event);
inOrder.verify(consumerRecordService).markDone(eventId, "RelationCounterProjectConsumer");
```

- [ ] **Step 2: Processor failure prevents done**

Throw from `processor.process(event)`, verify `markDone` never called and `markFail` called.

### Task 2: Run Trigger Tests

- [ ] **Step 1: Execute command**

```powershell
& 'C:\Users\Administrator\Desktop\文档\.codex\tools\apache-maven-3.9.6\bin\mvn.cmd' -pl nexus-trigger -am -Dtest=RelationCounterProjectConsumerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 2: Commit if tests changed**

```powershell
git add nexus-trigger/src/test/java/cn/nexus/trigger/listener/social/RelationCounterProjectConsumerTest.java
git commit -m "test: verify relation counter consumer idempotency"
```

## Ambiguity Review

No architecture choice remains. Consumer success means durable MySQL enqueue, not immediate Redis application.
