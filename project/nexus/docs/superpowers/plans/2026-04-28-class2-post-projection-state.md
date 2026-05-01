# Class 2 Post Projection State — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add MySQL `post_counter_projection` storage with edge detection for Class 2 post counter increments.

**Architecture:** Standard MyBatis DAO/PO/Mapper pattern. Repository implements the domain contract (`IPostCounterProjectionRepository`) with a read-then-write `compareAndWrite` method that classifies each event as EDGE_TRANSITION, SAME_STATE, or STALE_EVENT.

**Tech Stack:** MyBatis 3, MySQL, Spring @Transactional, Lombok

---

### Task R3: DDL Migration

**Files:**
- CREATE: `docs/migrations/20260428_01_add_post_counter_projection.sql`

- [ ] **Step 1: Write migration**

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

- [ ] **Step 2: Commit**

```bash
git add docs/migrations/20260428_01_add_post_counter_projection.sql
git commit -m "feat: add post_counter_projection DDL for class2 post edge detection"
```

---

### Task R4: Infrastructure DAO + PO + Mapper

**Files:**
- CREATE: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/dao/social/IPostCounterProjectionDao.java`
- CREATE: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/dao/social/po/PostCounterProjectionPO.java`
- CREATE: `nexus-infrastructure/src/main/resources/mapper/social/PostCounterProjectionMapper.xml`

- [ ] **Step 1: Write PO**

```java
package cn.nexus.infrastructure.dao.social.po;

import java.util.Date;
import lombok.Data;

@Data
public class PostCounterProjectionPO {
    private Long postId;
    private Long authorId;
    private Integer projectedPublished;
    private Long lastEventId;
    private Date createTime;
    private Date updateTime;
}
```

- [ ] **Step 2: Write DAO interface**

```java
package cn.nexus.infrastructure.dao.social;

import cn.nexus.infrastructure.dao.social.po.PostCounterProjectionPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface IPostCounterProjectionDao {

    PostCounterProjectionPO selectByPostId(@Param("postId") Long postId);

    int insert(PostCounterProjectionPO po);

    int updateState(@Param("postId") Long postId,
                    @Param("projectedPublished") Integer projectedPublished,
                    @Param("lastEventId") Long lastEventId);
}
```

- [ ] **Step 3: Write MyBatis mapper XML**

```xml
<?xml version="1.0" encoding="UTF-8" ?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="cn.nexus.infrastructure.dao.social.IPostCounterProjectionDao">

    <resultMap id="PostCounterProjectionMap" type="cn.nexus.infrastructure.dao.social.po.PostCounterProjectionPO">
        <id property="postId" column="post_id"/>
        <result property="authorId" column="author_id"/>
        <result property="projectedPublished" column="projected_published"/>
        <result property="lastEventId" column="last_event_id"/>
        <result property="createTime" column="create_time"/>
        <result property="updateTime" column="update_time"/>
    </resultMap>

    <select id="selectByPostId" resultMap="PostCounterProjectionMap">
        SELECT post_id, author_id, projected_published, last_event_id, create_time, update_time
        FROM post_counter_projection
        WHERE post_id = #{postId}
    </select>

    <insert id="insert" parameterType="cn.nexus.infrastructure.dao.social.po.PostCounterProjectionPO">
        INSERT IGNORE INTO post_counter_projection (post_id, author_id, projected_published, last_event_id, create_time, update_time)
        VALUES (#{postId}, #{authorId}, #{projectedPublished}, #{lastEventId}, NOW(), NOW())
    </insert>

    <update id="updateState">
        UPDATE post_counter_projection
        SET projected_published = #{projectedPublished},
            last_event_id = #{lastEventId},
            update_time = NOW()
        WHERE post_id = #{postId}
          AND last_event_id &lt; #{lastEventId}
    </update>

</mapper>
```

- [ ] **Step 4: Commit**

```bash
git add nexus-infrastructure/src/main/java/cn/nexus/infrastructure/dao/social/IPostCounterProjectionDao.java \
        nexus-infrastructure/src/main/java/cn/nexus/infrastructure/dao/social/po/PostCounterProjectionPO.java \
        nexus-infrastructure/src/main/resources/mapper/social/PostCounterProjectionMapper.xml
git commit -m "feat: add post_counter_projection DAO, PO, and MyBatis mapper"
```

---

### Task R5: Repository Implementation

**Files:**
- CREATE: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/PostCounterProjectionRepository.java`

- [ ] **Step 1: Write repository implementation**

```java
package cn.nexus.infrastructure.adapter.social.repository;

import cn.nexus.domain.social.adapter.repository.IPostCounterProjectionRepository;
import cn.nexus.infrastructure.dao.social.IPostCounterProjectionDao;
import cn.nexus.infrastructure.dao.social.po.PostCounterProjectionPO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

@Slf4j
@Repository
@RequiredArgsConstructor
public class PostCounterProjectionRepository implements IPostCounterProjectionRepository {

    // Concurrency assumption: events for the same postId are consumed sequentially
    // by a single RabbitMQ consumer thread (manual ack, ordered queue processing).
    // The two-step read-then-write (SELECT + INSERT IGNORE/UPDATE) is safe under
    // this model. If this assumption changes, the INSERT IGNORE path needs re-SELECT
    // after insertion to determine the true edge result.

    private final IPostCounterProjectionDao dao;

    @Override
    public EdgeResult compareAndWrite(Long postId, Long authorId,
                                      boolean targetPublished, Long relationEventId) {
        if (postId == null || authorId == null || relationEventId == null) {
            return EdgeResult.SAME_STATE;
        }

        PostCounterProjectionPO existing = dao.selectByPostId(postId);

        if (existing != null) {
            if (relationEventId <= existing.getLastEventId()) {
                return EdgeResult.STALE_EVENT;
            }
            boolean currentPublished = existing.getProjectedPublished() != null
                    && existing.getProjectedPublished() == 1;
            if (currentPublished == targetPublished) {
                int updated = dao.updateState(postId, targetPublished ? 1 : 0, relationEventId);
                if (updated == 0) {
                    return EdgeResult.STALE_EVENT;
                }
                return EdgeResult.SAME_STATE;
            }
            int updated = dao.updateState(postId, targetPublished ? 1 : 0, relationEventId);
            if (updated == 0) {
                return EdgeResult.STALE_EVENT;
            }
            return EdgeResult.EDGE_TRANSITION;
        }

        PostCounterProjectionPO po = new PostCounterProjectionPO();
        po.setPostId(postId);
        po.setAuthorId(authorId);
        po.setProjectedPublished(targetPublished ? 1 : 0);
        po.setLastEventId(relationEventId);
        dao.insert(po);

        if (targetPublished) {
            return EdgeResult.EDGE_TRANSITION;
        }
        return EdgeResult.SAME_STATE;
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/PostCounterProjectionRepository.java
git commit -m "feat: implement PostCounterProjectionRepository with edge detection"
```
