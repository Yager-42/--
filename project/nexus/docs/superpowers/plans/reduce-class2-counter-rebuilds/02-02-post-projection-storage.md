# Task 2.2 Post Projection Storage Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement MyBatis storage and repository support for `post_counter_projection`.

**Architecture:** `PostCounterProjectionRepository` performs a row-level locked state transition inside a transaction. It inserts first-seen posts, rejects stale `relationEventId <= last_event_id`, computes `delta`, and updates durable state before the processor enqueues counter deltas. A post's author is a business invariant; mismatches keep the stored author id and do not migrate authors.

**Tech Stack:** Java, Spring `@Repository`, Spring transactions, MyBatis XML, MySQL, JUnit 5, Mockito, AssertJ, Maven.

---

## File Structure

- Create: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/dao/social/IPostCounterProjectionDao.java`
- Create: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/dao/social/po/PostCounterProjectionPO.java`
- Create: `nexus-infrastructure/src/main/resources/mapper/social/PostCounterProjectionMapper.xml`
- Create: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/PostCounterProjectionRepository.java`
- Create: `nexus-infrastructure/src/test/java/cn/nexus/infrastructure/adapter/social/repository/PostCounterProjectionRepositoryTest.java`
- Create: `nexus-infrastructure/src/test/java/cn/nexus/infrastructure/dao/social/PostCounterProjectionMapperContractTest.java`
- Modify: `docs/migrations/20260427_01_class2_counter_projection_tables.sql`
- Modify: `docs/nexus_final_mysql_schema.sql`

## DDL

```sql
CREATE TABLE IF NOT EXISTS post_counter_projection (
  post_id BIGINT NOT NULL,
  author_id BIGINT NOT NULL,
  projected_published TINYINT NOT NULL DEFAULT 0,
  last_event_id BIGINT NOT NULL DEFAULT 0,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (post_id),
  KEY idx_author_state (author_id, projected_published, update_time)
);
```

### Task 1: Write Failing Repository And Mapper Tests

- [ ] **Step 1: Add first insert published test**

```java
@Test
void projectPublishedState_firstPublishedInsert_shouldReturnPlusOneDelta() {
    IPostCounterProjectionDao dao = Mockito.mock(IPostCounterProjectionDao.class);
    PostCounterProjectionRepository repository = new PostCounterProjectionRepository(dao);
    when(dao.selectByPostIdForUpdate(9001L)).thenReturn(null);

    PostCounterProjectionResultVO result = repository.projectPublishedState(9001L, 77L, true, 2001L);

    assertThat(result.isStale()).isFalse();
    assertThat(result.getAuthorId()).isEqualTo(77L);
    assertThat(result.isPreviousPublished()).isFalse();
    assertThat(result.isCurrentPublished()).isTrue();
    assertThat(result.getDelta()).isEqualTo(1L);
    verify(dao).insert(argThat(po ->
            po.getPostId().equals(9001L)
                    && po.getAuthorId().equals(77L)
                    && po.getProjectedPublished().equals(1)
                    && po.getLastEventId().equals(2001L)));
}
```

- [ ] **Step 2: Add same-state fresh event test**

```java
@Test
void projectPublishedState_sameStateFreshEvent_shouldUpdateEventIdAndReturnZeroDelta() {
    IPostCounterProjectionDao dao = Mockito.mock(IPostCounterProjectionDao.class);
    when(dao.selectByPostIdForUpdate(9001L)).thenReturn(row(9001L, 77L, 1, 2001L));
    PostCounterProjectionRepository repository = new PostCounterProjectionRepository(dao);

    PostCounterProjectionResultVO result = repository.projectPublishedState(9001L, 77L, true, 2002L);

    assertThat(result.getDelta()).isZero();
    assertThat(result.isStale()).isFalse();
    verify(dao).updateState(9001L, 77L, 1, 2002L);
}
```

- [ ] **Step 3: Add publish-to-unpublish edge test**

```java
@Test
void projectPublishedState_unpublishFreshEvent_shouldReturnMinusOneDelta() {
    IPostCounterProjectionDao dao = Mockito.mock(IPostCounterProjectionDao.class);
    when(dao.selectByPostIdForUpdate(9001L)).thenReturn(row(9001L, 77L, 1, 2001L));
    PostCounterProjectionRepository repository = new PostCounterProjectionRepository(dao);

    PostCounterProjectionResultVO result = repository.projectPublishedState(9001L, 77L, false, 2002L);

    assertThat(result.getDelta()).isEqualTo(-1L);
    verify(dao).updateState(9001L, 77L, 0, 2002L);
}
```

- [ ] **Step 4: Add stale event test**

```java
@Test
void projectPublishedState_staleEventId_shouldReturnStaleNoop() {
    IPostCounterProjectionDao dao = Mockito.mock(IPostCounterProjectionDao.class);
    when(dao.selectByPostIdForUpdate(9001L)).thenReturn(row(9001L, 77L, 1, 2002L));
    PostCounterProjectionRepository repository = new PostCounterProjectionRepository(dao);

    PostCounterProjectionResultVO result = repository.projectPublishedState(9001L, 77L, false, 2002L);

    assertThat(result.isStale()).isTrue();
    assertThat(result.getDelta()).isZero();
    verify(dao, never()).updateState(any(), any(), any(), any());
    verify(dao, never()).insert(any());
}
```

- [ ] **Step 5: Add author mismatch invariant test**

```java
@Test
void projectPublishedState_authorMismatch_shouldKeepStoredAuthorAndNotMigrate() {
    IPostCounterProjectionDao dao = Mockito.mock(IPostCounterProjectionDao.class);
    when(dao.selectByPostIdForUpdate(9001L)).thenReturn(row(9001L, 77L, 0, 2001L));
    PostCounterProjectionRepository repository = new PostCounterProjectionRepository(dao);

    PostCounterProjectionResultVO result = repository.projectPublishedState(9001L, 88L, true, 2002L);

    assertThat(result.getAuthorId()).isEqualTo(77L);
    assertThat(result.getDelta()).isEqualTo(1L);
    verify(dao).updateState(9001L, 77L, 1, 2002L);
}
```

- [ ] **Step 6: Add mapper contract test for row lock**

```java
@Test
void mapper_shouldSelectProjectionForUpdate() throws Exception {
    String mapper = Files.readString(Path.of(
            "nexus-infrastructure/src/main/resources/mapper/social/PostCounterProjectionMapper.xml"));

    assertThat(mapper).containsIgnoringCase("FOR UPDATE");
}
```

- [ ] **Step 7: Run tests and confirm failure**

```powershell
& 'C:\Users\Administrator\Desktop\文档\.codex\tools\apache-maven-3.9.6\bin\mvn.cmd' -pl nexus-infrastructure -Dtest=PostCounterProjectionRepositoryTest,PostCounterProjectionMapperContractTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected before implementation: compilation fails until DAO, PO, mapper, and repository exist.

### Task 2: Add DAO And Mapper

- [ ] **Step 1: Create PO**

Fields: `postId`, `authorId`, `projectedPublished`, `lastEventId`, `createTime`, `updateTime`. Use local Lombok conventions from existing `po` classes.

- [ ] **Step 2: Create DAO**

```java
PostCounterProjectionPO selectByPostIdForUpdate(@Param("postId") Long postId);
int insert(PostCounterProjectionPO po);
int updateState(@Param("postId") Long postId,
                @Param("authorId") Long authorId,
                @Param("projectedPublished") Integer projectedPublished,
                @Param("lastEventId") Long lastEventId);
```

- [ ] **Step 3: Create XML mapper**

`selectByPostIdForUpdate` must include `FOR UPDATE`. `updateState` must update `author_id`, `projected_published`, `last_event_id`, and `update_time = NOW()`.

### Task 3: Implement Repository Algorithm

- [ ] **Step 1: Implement repository with transaction**

```java
@Repository
@RequiredArgsConstructor
public class PostCounterProjectionRepository implements IPostCounterProjectionRepository {
    private final IPostCounterProjectionDao dao;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PostCounterProjectionResultVO projectPublishedState(Long postId, Long authorId,
                                                               boolean targetPublished, Long relationEventId) {
        if (postId == null || authorId == null || relationEventId == null) {
            return result(postId, authorId, false, false, 0L, true);
        }
        PostCounterProjectionPO row = dao.selectByPostIdForUpdate(postId);
        if (row == null) {
            dao.insert(newRow(postId, authorId, targetPublished, relationEventId));
            return result(postId, authorId, false, targetPublished, targetPublished ? 1L : 0L, false);
        }
        boolean previous = Integer.valueOf(1).equals(row.getProjectedPublished());
        if (relationEventId <= row.getLastEventId()) {
            return result(postId, row.getAuthorId(), previous, previous, 0L, true);
        }
        Long effectiveAuthorId = row.getAuthorId();
        long delta = previous == targetPublished ? 0L : (targetPublished ? 1L : -1L);
        dao.updateState(postId, effectiveAuthorId, targetPublished ? 1 : 0, relationEventId);
        return result(postId, effectiveAuthorId, previous, targetPublished, delta, false);
    }
}
```

- [ ] **Step 2: Invalid input behavior**

If `postId`, `authorId`, or `relationEventId` is null, return `delta=0`, `stale=true`, and do not write. Do not throw for malformed events from MQ.

- [ ] **Step 3: Author mismatch handling**

If row exists and `row.authorId` differs from incoming `authorId`, keep stored author id in the returned result, update state using stored author id, and do not implement migration.

### Task 4: Verify

- [ ] **Step 1: Run repository and mapper contract tests**

```powershell
& 'C:\Users\Administrator\Desktop\文档\.codex\tools\apache-maven-3.9.6\bin\mvn.cmd' -pl nexus-infrastructure -Dtest=PostCounterProjectionRepositoryTest,PostCounterProjectionMapperContractTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 2: Commit**

```powershell
git add nexus-infrastructure/src/main/java/cn/nexus/infrastructure/dao/social/IPostCounterProjectionDao.java nexus-infrastructure/src/main/java/cn/nexus/infrastructure/dao/social/po/PostCounterProjectionPO.java nexus-infrastructure/src/main/resources/mapper/social/PostCounterProjectionMapper.xml nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/PostCounterProjectionRepository.java nexus-infrastructure/src/test/java/cn/nexus/infrastructure/adapter/social/repository/PostCounterProjectionRepositoryTest.java nexus-infrastructure/src/test/java/cn/nexus/infrastructure/dao/social/PostCounterProjectionMapperContractTest.java docs/migrations/20260427_01_class2_counter_projection_tables.sql docs/nexus_final_mysql_schema.sql
git commit -m "feat: add post counter projection storage"
```

## Ambiguity Review

No architecture choice remains. Ordering is monotonic `relationEventId`, locking is MySQL `FOR UPDATE`, and author mismatch is invalid data rather than a supported migration path.
