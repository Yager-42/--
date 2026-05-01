# Task 1.4 Infrastructure Outbox And Rebuild Tests Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add infrastructure regression tests for rebuild request coalescing, delta outbox idempotency, and bounded draining.

**Architecture:** Two durable MySQL queues exist: `user_counter_rebuild_request` for repair and `user_counter_delta_outbox` for normal Class 2 Redis application. Tests should lock repository contracts before implementation details are written.

**Tech Stack:** Java, JUnit 5, Mockito, MyBatis repository pattern, Maven.

---

## File Structure

- Create: `nexus-infrastructure/src/test/java/cn/nexus/infrastructure/adapter/counter/repository/UserCounterRebuildRequestRepositoryTest.java`
- Create: `nexus-infrastructure/src/test/java/cn/nexus/infrastructure/adapter/counter/repository/UserCounterDeltaOutboxRepositoryTest.java`
- Reference: `nexus-domain/src/main/java/cn/nexus/domain/counter/adapter/repository/IUserCounterRebuildRequestRepository.java`
- Reference: `nexus-domain/src/main/java/cn/nexus/domain/counter/adapter/repository/IUserCounterDeltaOutboxRepository.java`

## Exact Behavior To Lock

- Rebuild request `request(userId, reason)` delegates to one upsert per user.
- Repeated rebuild requests increment request metadata in DAO contract, not duplicate rows.
- Delta outbox `enqueue(sourceEventId, userId, type, delta)` uses unique `(source_event_id, counter_user_id, counter_type)`.
- Fetch APIs are bounded and never return null.
- Stale `PROCESSING` delta rows are terminalized as `UNKNOWN_APPLIED` and schedule `DELTA_UNKNOWN_RESULT` repair; they are not replayed as increments.

### Task 1: Add Rebuild Repository Tests

- [ ] **Step 1: Write coalescing test against mocked DAO**

```java
@Test
void request_shouldUseSingleUserUpsert() {
    IUserCounterRebuildRequestDao dao = Mockito.mock(IUserCounterRebuildRequestDao.class);
    UserCounterRebuildRequestRepository repository = new UserCounterRebuildRequestRepository(dao);

    repository.request(42L, "SAMPLE_MISMATCH");

    verify(dao).upsertRequest(42L, "SAMPLE_MISMATCH");
}
```

- [ ] **Step 2: Write bounded fetch test**

```java
@Test
void fetchDue_shouldPassLimitAndMapRows() {
    IUserCounterRebuildRequestDao dao = Mockito.mock(IUserCounterRebuildRequestDao.class);
    when(dao.fetchDue(any(), any(), eq(50))).thenReturn(List.of(row(42L, "PENDING")));
    UserCounterRebuildRequestRepository repository = new UserCounterRebuildRequestRepository(dao);

    List<UserCounterRebuildRequestVO> rows = repository.fetchDue(50);

    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).getUserId()).isEqualTo(42L);
}
```

### Task 2: Add Delta Outbox Repository Tests

- [ ] **Step 1: Write idempotent enqueue test**

```java
@Test
void enqueue_shouldUseEventUserTypeUniqueKey() {
    IUserCounterDeltaOutboxDao dao = Mockito.mock(IUserCounterDeltaOutboxDao.class);
    UserCounterDeltaOutboxRepository repository = new UserCounterDeltaOutboxRepository(dao);

    repository.enqueue(7001L, 42L, UserCounterType.FOLLOWING, 1L);

    verify(dao).insertIgnore(argThat(po ->
            po.getSourceEventId().equals(7001L)
                    && po.getCounterUserId().equals(42L)
                    && "FOLLOWING".equals(po.getCounterType())
                    && po.getDeltaValue().equals(1L)));
}
```

- [ ] **Step 2: Write stale processing fetch test**

```java
@Test
void fetchStaleProcessing_shouldReturnRowsForUnknownResultHandling() {
    IUserCounterDeltaOutboxDao dao = Mockito.mock(IUserCounterDeltaOutboxDao.class);
    Date staleBefore = new Date(System.currentTimeMillis() - 300_000L);
    when(dao.fetchStaleProcessing(staleBefore, 100)).thenReturn(List.of(deltaRow(1L, "PROCESSING")));
    UserCounterDeltaOutboxRepository repository = new UserCounterDeltaOutboxRepository(dao);

    List<UserCounterDeltaOutboxVO> rows = repository.fetchStaleProcessing(staleBefore, 100);

    assertThat(rows).hasSize(1);
    verify(dao).fetchStaleProcessing(staleBefore, 100);
}
```

- [ ] **Step 3: Run focused infrastructure tests**

Run:

```powershell
& 'C:\Users\Administrator\Desktop\文档\.codex\tools\apache-maven-3.9.6\bin\mvn.cmd' -pl nexus-infrastructure -Dtest=UserCounterRebuildRequestRepositoryTest,UserCounterDeltaOutboxRepositoryTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected before implementation: compilation fails until DAO/repository classes exist.

- [ ] **Step 4: Commit tests after implementation passes**

```powershell
git add nexus-infrastructure/src/test/java/cn/nexus/infrastructure/adapter/counter/repository
git commit -m "test: cover counter repair and delta outbox repositories"
```

## Ambiguity Review

No architecture choice remains. Delta outbox is per counter slot, not per event, because one event can affect multiple users and counter types.
