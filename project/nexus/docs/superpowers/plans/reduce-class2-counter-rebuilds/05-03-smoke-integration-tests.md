# Task 5.3 Smoke Integration Tests Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add and run smoke tests covering the end-to-end Class 2 projection, delta outbox, worker drain, and repair fallback flows.

**Architecture:** Smoke verification must account for eventual consistency: processor success creates durable delta rows first; Redis counter visibility requires delta worker drain. The smoke tests use explicit fakes/mocks where full Docker integration is unavailable, but they must still exercise the real processor/service contracts and exact worker drain methods.

**Tech Stack:** Java, JUnit 5, Mockito, AssertJ, Maven, optional WSL Docker service check.

---

## File Structure

- Create: `nexus-trigger/src/test/java/cn/nexus/trigger/counter/Class2CounterProjectionSmokeTest.java`
- Create: `nexus-trigger/src/test/java/cn/nexus/trigger/counter/Class2CounterSchemaContractTest.java`
- Reference: `docs/migrations/20260427_01_class2_counter_projection_tables.sql`
- Reference: `docs/nexus_final_mysql_schema.sql`
- Reference: `nexus-domain/src/main/java/cn/nexus/domain/social/service/RelationCounterProjectionProcessor.java`
- Reference: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/counter/service/UserCounterService.java`

## Required Smoke Scenarios

- Follow active -> follower row created -> two pending delta rows -> `drainCounterDeltas(200)` -> Redis counters +1/+1.
- Duplicate follow -> no new delta rows.
- Unfollow -> two pending delta rows -> `drainCounterDeltas(200)` -> Redis counters -1/-1.
- Post published fresh false->true -> one pending `POST +1` row -> `drainCounterDeltas(200)` -> Redis post +1.
- Duplicate published -> no new delta.
- Stale post event -> no state change and no delta.
- Stale `PROCESSING` delta -> no Redis increment replay -> `DELTA_UNKNOWN_RESULT` rebuild request.
- Missing/malformed user snapshot -> bounded repair or durable rebuild request.

### Task 1: Add Schema Contract Smoke Test

- [ ] **Step 1: Write migration contract test**

```java
@Test
void migration_shouldContainRequiredClass2CounterTablesAndIndexes() throws Exception {
    String migration = Files.readString(Path.of("docs/migrations/20260427_01_class2_counter_projection_tables.sql"));

    assertThat(migration).contains("post_counter_projection");
    assertThat(migration).contains("user_counter_delta_outbox");
    assertThat(migration).contains("user_counter_rebuild_request");
    assertThat(migration).contains("uk_event_user_type");
    assertThat(migration).contains("processing_time");
    assertThat(migration).contains("last_rebuild_time");
}
```

- [ ] **Step 2: Write final schema contract test**

```java
@Test
void finalSchema_shouldContainClass2CounterTables() throws Exception {
    String schema = Files.readString(Path.of("docs/nexus_final_mysql_schema.sql"));

    assertThat(schema).contains("CREATE TABLE IF NOT EXISTS post_counter_projection");
    assertThat(schema).contains("CREATE TABLE IF NOT EXISTS user_counter_delta_outbox");
    assertThat(schema).contains("CREATE TABLE IF NOT EXISTS user_counter_rebuild_request");
}
```

### Task 2: Add Projection Smoke Test With Explicit Worker Drain

- [ ] **Step 1: Create in-memory fake delta outbox repository**

Inside `Class2CounterProjectionSmokeTest`, add a small fake implementing `IUserCounterDeltaOutboxRepository`. It must store rows by `(sourceEventId, counterUserId, counterType)`, expose pending row count for assertions, and return rows from `fetchRetryable`.

- [ ] **Step 2: Write follow smoke test**

```java
@Test
void followProjection_shouldBecomeVisibleAfterDeltaWorkerDrain() {
    // Arrange processor with fake relation repository, fake delta outbox, and mocked userCounterService.
    // Act: processor.process(follow ACTIVE event).
    // Assert: fake outbox has FOLLOWING +1 and FOLLOWER +1; userCounterService increment methods not called by processor.
    // Act: service.drainCounterDeltas(200).
    // Assert: userCounterService applied follow increments during worker drain.
}
```

- [ ] **Step 3: Write duplicate follow smoke test**

Same fixture, but `saveFollowerIfAbsent` returns false. Assert fake outbox pending count remains zero.

- [ ] **Step 4: Write post stale smoke test**

Use fake `IPostCounterProjectionRepository` that returns `stale=true`, `delta=0`. Assert no delta rows.

- [ ] **Step 5: Write stale processing delta smoke test**

Seed fake delta outbox with one `PROCESSING` row older than stale timeout. Run `drainCounterDeltas(200)`. Assert no increment method was called and fake rebuild request repository received `request(userId, "DELTA_UNKNOWN_RESULT")`.

### Task 3: Environment Check For Optional Docker-Backed Run

- [ ] **Step 1: Check local services**

```powershell
wsl.exe -d Ubuntu-22.04 bash -lc "docker ps --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}'"
```

Expected: output lists the Nexus MySQL and Redis containers if the local Docker-backed integration profile is used. This command is informational for this smoke task; the required smoke tests above must run without Docker by using fakes/mocks.

### Task 4: Run Smoke Tests

- [ ] **Step 1: Run exact smoke tests**

```powershell
& 'C:\Users\Administrator\Desktop\文档\.codex\tools\apache-maven-3.9.6\bin\mvn.cmd' -pl nexus-trigger -am -Dtest=Class2CounterProjectionSmokeTest,Class2CounterSchemaContractTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 2: Commit**

```powershell
git add nexus-trigger/src/test/java/cn/nexus/trigger/counter/Class2CounterProjectionSmokeTest.java nexus-trigger/src/test/java/cn/nexus/trigger/counter/Class2CounterSchemaContractTest.java
git commit -m "test: add class2 counter projection smoke coverage"
```

## Ambiguity Review

No architecture choice remains. Smoke tests have fixed file paths, fixed commands, fixed schema assertions, and explicit worker drain before Redis visibility assertions.
