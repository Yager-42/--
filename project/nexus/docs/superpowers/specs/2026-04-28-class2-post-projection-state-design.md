# Class 2 Post Projection State — Design Spec

**Topic:** Post counter projection state table for published-state edge detection
**Change:** reduce-class2-counter-rebuilds
**Group:** 2 of 5
**Date:** 2026-04-28

## Context

Post events (PUBLISHED/UNPUBLISHED/DELETED) carry a status but don't carry previous state. Unlike follow events where `user_follower` table rows serve as natural edge detectors, post counter increments need to know whether the published state actually changed. A lightweight MySQL projection state table provides this edge detection.

## Scope

3 tasks:
- 2.1: DDL migration for `post_counter_projection` table
- 2.2: Domain contract `IPostCounterProjectionRepository` (already created in Group 1 Task R1)
- 2.3: Infrastructure DAO, PO, MyBatis mapper, and repository implementation

## Design

### Table DDL

```sql
CREATE TABLE IF NOT EXISTS post_counter_projection (
    post_id BIGINT NOT NULL PRIMARY KEY,
    author_id BIGINT NOT NULL,
    projected_published TINYINT NOT NULL DEFAULT 0,
    last_event_id BIGINT NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### Edge Detection Logic

The `compareAndWrite` method runs within the existing `@Transactional` boundary on `RelationCounterProjectionProcessor.process()`. It uses two-step read-then-write:

```text
1. SELECT projected_published, last_event_id WHERE post_id = ?
   |
   ├─ Row exists:
   │   ├─ incoming.eventId <= existing.lastEventId → return STALE_EVENT
   │   ├─ incoming.targetPublished == existing.projected_published → return SAME_STATE
   │   └─ else → UPDATE projected_published, last_event_id → return EDGE_TRANSITION
   │
   └─ Row does NOT exist:
       ├─ INSERT (post_id, author_id, targetPublished, eventId)
       ├─ targetPublished == true → return EDGE_TRANSITION (0→1)
       └─ targetPublished == false → return SAME_STATE (0→0, no edge)
```

First-write author_id is treated as business invariant. If a subsequent event carries a different author_id for the same post_id, the repository logs a warning but uses the stored author_id.

### File Structure

```
nexus-domain/
└── src/main/java/cn/nexus/domain/social/adapter/repository/
    └── IPostCounterProjectionRepository.java  [CREATED in Group 1]

nexus-infrastructure/
├── src/main/java/cn/nexus/infrastructure/
│   ├── dao/social/IPostCounterProjectionDao.java         [CREATE]
│   ├── dao/social/po/PostCounterProjectionPO.java        [CREATE]
│   └── adapter/social/repository/
│       └── PostCounterProjectionRepository.java           [CREATE]
├── src/main/resources/mapper/social/
│   └── PostCounterProjectionMapper.xml                   [CREATE]

docs/migrations/
└── 20260428_01_add_post_counter_projection.sql           [CREATE]
```

### DAO Interface

```java
public interface IPostCounterProjectionDao {
    PostCounterProjectionPO selectByPostId(Long postId);
    int insert(PostCounterProjectionPO po);
    int updateState(Long postId, int projectedPublished, Long lastEventId);
}
```

### MyBatis Mapper Operations

- `selectByPostId` — standard SELECT by PK
- `insert` — INSERT IGNORE for initial row creation
- `updateState` — conditional UPDATE WHERE post_id = ? AND last_event_id < incoming eventId
