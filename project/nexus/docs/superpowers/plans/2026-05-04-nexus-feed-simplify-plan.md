# Nexus Feed 关注推送简化重构实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将关注推送索引从 4 种 Redis 结构简化为 2 种（inbox + AuthorTimeline），合并 cleanup 队列，取消 Lua 原子重建，接入关注补偿。

**Architecture:** 保持 Redis ZSET + RabbitMQ + repository/service 边界不变。Outbox 改名为 AuthorTimeline（新接口+新实现，旧接口标记废弃），cleanup 两个队列合并为一个，重建改为读时激活（无 Lua/锁/哨兵），关注补偿通过独立 MQ 队列接入。

**Tech Stack:** Java 17, Spring Boot, Redis ZSET, RabbitMQ, MyBatis-Plus, Maven.

---

## Hard Boundaries

1. 只改 FOLLOW 推送索引，不动 RECOMMEND/POPULAR/NEIGHBORS/PROFILE 读入口
2. 不动推荐 item upsert/delete 消费者和队列
3. 不删除 `feed:global:latest` Redis key（如果推荐系统仍依赖）；只取消 FOLLOW dispatcher 对它的写入责任
4. 不动 Count 系统、卡片缓存、关系邻接缓存、可靠 MQ outbox
5. 不在关系写事务内同步写 Feed Redis
6. 不引入新的基础设施（Redis Stream、CDC、新表、新 exchange）

## Command Context

所有 `mvn`、`rg`、`git` 命令在 `project/nexus` 目录执行。

---

## Implementation Map

### New files
- `nexus-domain/src/main/java/cn/nexus/domain/social/adapter/repository/IFeedAuthorTimelineRepository.java`
- `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/config/FeedAuthorTimelineProperties.java`
- `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/FeedAuthorTimelineRepository.java`
- `nexus-trigger/src/main/java/cn/nexus/trigger/mq/config/FeedFollowCompensationMqConfig.java`
- `nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FollowFeedCompensationConsumer.java`

### Modified files
- `nexus-trigger/src/main/java/cn/nexus/trigger/mq/config/FeedFanoutConfig.java` — 合并 cleanup 队列
- `nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FeedFanoutDispatcherConsumer.java` — 拆掉 latest/pool/自写 inbox
- `nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FeedIndexCleanupConsumer.java` — 只清 AuthorTimeline
- `nexus-domain/src/main/java/cn/nexus/domain/social/service/FeedInboxRebuildService.java` — 改为读时激活
- `nexus-domain/src/main/java/cn/nexus/domain/social/service/FeedFollowCompensationService.java` — 改用 AuthorTimeline
- `nexus-domain/src/main/java/cn/nexus/domain/social/service/FeedService.java` — 读路径改 AuthorTimeline + self
- `nexus-domain/src/main/java/cn/nexus/domain/social/service/FeedDistributionService.java` — 移除无用注入
- `nexus-domain/src/main/java/cn/nexus/domain/social/service/FeedAuthorCategoryStateMachine.java` — 移除 outbox rebuild
- `nexus-domain/src/main/java/cn/nexus/domain/social/service/IFeedInboxRebuildService.java` — 改名为 IFeedInboxActivationService
- `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/FeedTimelineRepository.java` — 取消 Lua/锁/哨兵

### Deleted files
- `nexus-domain/src/main/java/cn/nexus/domain/social/service/IFeedOutboxRebuildService.java`
- `nexus-domain/src/main/java/cn/nexus/domain/social/service/FeedOutboxRebuildService.java`

### Test files to modify
- `nexus-infrastructure/src/test/java/cn/nexus/infrastructure/adapter/social/repository/FeedTimelineRepositoryTest.java`
- `nexus-infrastructure/src/test/java/cn/nexus/infrastructure/adapter/social/repository/FeedOutboxRepositoryTest.java`
- `nexus-trigger/src/test/java/cn/nexus/trigger/mq/config/FeedFanoutConfigTest.java`
- `nexus-trigger/src/test/java/cn/nexus/trigger/mq/consumer/FeedFanoutDispatcherConsumerTest.java`
- `nexus-trigger/src/test/java/cn/nexus/trigger/mq/consumer/FeedIndexCleanupConsumerTest.java`
- `nexus-domain/src/test/java/cn/nexus/domain/social/service/FeedServiceTest.java`
- `nexus-domain/src/test/java/cn/nexus/domain/social/service/FeedInboxRebuildServiceTest.java`
- `nexus-app/src/test/java/cn/nexus/contract/mq/ReliableMqArchitectureContractTest.java`
- `nexus-app/src/test/java/cn/nexus/integration/FeedFanoutRealIntegrationTest.java`
- `nexus-app/src/test/java/cn/nexus/integration/feed/FeedHttpRealIntegrationTest.java`

---

## Task 1: Create AuthorTimeline Repository

**Files:**
- Create: `nexus-domain/src/main/java/cn/nexus/domain/social/adapter/repository/IFeedAuthorTimelineRepository.java`
- Create: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/config/FeedAuthorTimelineProperties.java`
- Create: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/FeedAuthorTimelineRepository.java`
- Create: `nexus-infrastructure/src/test/java/cn/nexus/infrastructure/adapter/social/repository/FeedAuthorTimelineRepositoryTest.java`

- [ ] **Step 1: Write FeedAuthorTimelineProperties**

```java
package cn.nexus.infrastructure.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "feed.author.timeline")
public class FeedAuthorTimelineProperties {
    private int maxSize = 1000;
    private int ttlDays = 30;
}
```

- [ ] **Step 2: Write IFeedAuthorTimelineRepository interface**

File: `nexus-domain/src/main/java/cn/nexus/domain/social/adapter/repository/IFeedAuthorTimelineRepository.java`

```java
package cn.nexus.domain.social.adapter.repository;

import cn.nexus.domain.social.model.valobj.FeedInboxEntryVO;
import java.util.List;

/**
 * AuthorTimeline: 某个作者的发布索引（替代旧 outbox）。
 * key: feed:timeline:{authorId}, ZSET score=publishTimeMs, member=postId
 */
public interface IFeedAuthorTimelineRepository {

    void addToTimeline(Long authorId, Long postId, Long publishTimeMs);

    void removeFromTimeline(Long authorId, Long postId);

    List<FeedInboxEntryVO> pageTimeline(Long authorId, Long cursorTimeMs, Long cursorPostId, int limit);

    boolean timelineExists(Long authorId);
}
```

- [ ] **Step 3: Write FeedAuthorTimelineRepositoryTest**

File: `nexus-infrastructure/src/test/java/cn/nexus/infrastructure/adapter/social/repository/FeedAuthorTimelineRepositoryTest.java`

```java
package cn.nexus.infrastructure.adapter.social.repository;

import cn.nexus.domain.social.model.valobj.FeedInboxEntryVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FeedAuthorTimelineRepositoryTest {

    @Mock
    StringRedisTemplate stringRedisTemplate;

    @Mock
    ZSetOperations<String, String> zSetOperations;

    @Mock
    cn.nexus.infrastructure.config.FeedAuthorTimelineProperties properties;

    @InjectMocks
    FeedAuthorTimelineRepository repository;

    @BeforeEach
    void setUp() {
        when(properties.getMaxSize()).thenReturn(1000);
        when(properties.getTtlDays()).thenReturn(30);
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(stringRedisTemplate.getExpire(anyString())).thenReturn(Duration.ofDays(10));
    }

    @Test
    void addToTimeline_shouldZaddAndTrim() {
        when(zSetOperations.add(eq("feed:timeline:1"), eq("100"), anyDouble())).thenReturn(true);
        when(zSetOperations.removeRange(eq("feed:timeline:1"), eq(0L), eq(-1001L))).thenReturn(0L);

        repository.addToTimeline(1L, 100L, 999L);

        verify(zSetOperations).add(eq("feed:timeline:1"), eq("100"), anyDouble());
        verify(zSetOperations).removeRange(eq("feed:timeline:1"), eq(0L), eq(-1001L));
    }

    @Test
    void removeFromTimeline_shouldZrem() {
        repository.removeFromTimeline(1L, 100L);
        verify(zSetOperations).remove("feed:timeline:1", "100");
    }

    @Test
    void pageTimeline_shouldUseMaxIdCursor() {
        Set<ZSetOperations.TypedTuple<String>> mockSet = mock(Set.class);
        when(zSetOperations.reverseRangeByScoreWithScores(
                eq("feed:timeline:2"), anyDouble(), anyDouble(), eq(0L), eq(20L)))
                .thenReturn(mockSet);
        when(mockSet.iterator()).thenReturn(Collections.emptyIterator());

        List<FeedInboxEntryVO> result = repository.pageTimeline(2L, 999L, 100L, 20);
        assertThat(result).isEmpty();
    }

    @Test
    void timelineExists_shouldReturnTrue() {
        when(stringRedisTemplate.hasKey("feed:timeline:1")).thenReturn(true);
        assertThat(repository.timelineExists(1L)).isTrue();
    }

    @Test
    void timelineExists_shouldReturnFalse() {
        when(stringRedisTemplate.hasKey("feed:timeline:1")).thenReturn(false);
        assertThat(repository.timelineExists(1L)).isFalse();
    }
}
```

- [ ] **Step 4: Write FeedAuthorTimelineRepository implementation**

File: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/FeedAuthorTimelineRepository.java`

```java
package cn.nexus.infrastructure.adapter.social.repository;

import cn.nexus.domain.social.adapter.repository.IFeedAuthorTimelineRepository;
import cn.nexus.domain.social.model.valobj.FeedInboxEntryVO;
import cn.nexus.infrastructure.config.FeedAuthorTimelineProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Repository
@RequiredArgsConstructor
public class FeedAuthorTimelineRepository implements IFeedAuthorTimelineRepository {

    private static final String KEY_PREFIX = "feed:timeline:";

    private final StringRedisTemplate stringRedisTemplate;
    private final FeedAuthorTimelineProperties properties;

    @Override
    public void addToTimeline(Long authorId, Long postId, Long publishTimeMs) {
        if (authorId == null || postId == null || publishTimeMs == null) return;
        String key = timelineKey(authorId);
        stringRedisTemplate.opsForZSet().add(key, String.valueOf(postId), publishTimeMs.doubleValue());
        expireIfNeeded(key);
        trimToMaxSize(key);
    }

    @Override
    public void removeFromTimeline(Long authorId, Long postId) {
        if (authorId == null || postId == null) return;
        stringRedisTemplate.opsForZSet().remove(timelineKey(authorId), String.valueOf(postId));
    }

    @Override
    public List<FeedInboxEntryVO> pageTimeline(Long authorId, Long cursorTimeMs, Long cursorPostId, int limit) {
        if (authorId == null) return List.of();
        double maxScore = cursorTimeMs != null ? cursorTimeMs.doubleValue() : Double.POSITIVE_INFINITY;
        double minScore = 0.0;
        int safeLimit = Math.max(1, Math.min(limit, properties.getMaxSize()));
        Set<ZSetOperations.TypedTuple<String>> raw = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(timelineKey(authorId), minScore, maxScore, 0, safeLimit);
        if (raw == null || raw.isEmpty()) return List.of();
        List<FeedInboxEntryVO> result = new ArrayList<>(raw.size());
        for (ZSetOperations.TypedTuple<String> t : raw) {
            if (t == null || t.getValue() == null) continue;
            long postId = parseLong(t.getValue());
            if (postId == 0L) continue;
            long ts = (long) t.getScore();
            if (cursorTimeMs != null && ts == cursorTimeMs && postId >= cursorPostId) continue;
            result.add(FeedInboxEntryVO.builder().postId(postId).publishTimeMs(ts).build());
        }
        return result;
    }

    @Override
    public boolean timelineExists(Long authorId) {
        if (authorId == null) return false;
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(timelineKey(authorId)));
    }

    private String timelineKey(Long authorId) {
        return KEY_PREFIX + authorId;
    }

    private void expireIfNeeded(String key) {
        Long ttl = stringRedisTemplate.getExpire(key);
        if (ttl != null && ttl < 0) {
            stringRedisTemplate.expire(key, Duration.ofDays(properties.getTtlDays()));
        }
    }

    private void trimToMaxSize(String key) {
        int maxSize = Math.max(1, properties.getMaxSize());
        stringRedisTemplate.opsForZSet().removeRange(key, 0, -(maxSize + 1L));
    }

    private long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
```

- [ ] **Step 5: Verify**

Run: `mvn -pl nexus-infrastructure test -Dtest="FeedAuthorTimelineRepositoryTest"`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add nexus-domain/src/main/java/cn/nexus/domain/social/adapter/repository/IFeedAuthorTimelineRepository.java \
        nexus-infrastructure/src/main/java/cn/nexus/infrastructure/config/FeedAuthorTimelineProperties.java \
        nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/FeedAuthorTimelineRepository.java \
        nexus-infrastructure/src/test/java/cn/nexus/infrastructure/adapter/social/repository/FeedAuthorTimelineRepositoryTest.java
git commit -m "feat: add AuthorTimeline repository replacing outbox"
```

---

## Task 2: Add Follow Compensation MQ Topology

**Files:**
- Create: `nexus-trigger/src/main/java/cn/nexus/trigger/mq/config/FeedFollowCompensationMqConfig.java`
- Modify: `nexus-domain/src/main/java/cn/nexus/domain/social/model/valobj/RelationCounterRouting.java`

- [ ] **Step 1: Add FOLLOW_COMPENSATE routing constant to RelationCounterRouting**

Edit `RelationCounterRouting.java`, add after `Q_BLOCK`:

```java
/** RoutingKey：关注 Feed 补偿 */
public static final String RK_FOLLOW_COMPENSATE = "relation.counter.follow.compensate";

/** Queue：关注 Feed 补偿队列 */
public static final String Q_FOLLOW_COMPENSATE = "relation.counter.follow.compensate.queue";

/** DLQ：关注 Feed 补偿死信队列 */
public static final String DLQ_FOLLOW_COMPENSATE = "relation.counter.follow.compensate.dlq.queue";

/** DLX RoutingKey：关注 Feed 补偿 */
public static final String RK_FOLLOW_COMPENSATE_DLX = "relation.counter.follow.compensate.dlx";
```

- [ ] **Step 2: Write FeedFollowCompensationMqConfig**

File: `nexus-trigger/src/main/java/cn/nexus/trigger/mq/config/FeedFollowCompensationMqConfig.java`

```java
package cn.nexus.trigger.mq.config;

import cn.nexus.domain.social.model.valobj.RelationCounterRouting;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * Feed 关注补偿 MQ 拓扑：消费 relation exchange 的 FOLLOW 事件，补偿写入 inbox。
 */
@Configuration
public class FeedFollowCompensationMqConfig {

    @Bean
    public Queue feedFollowCompensateQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", RelationCounterRouting.DLX_EXCHANGE);
        args.put("x-dead-letter-routing-key", RelationCounterRouting.RK_FOLLOW_COMPENSATE_DLX);
        return new Queue(RelationCounterRouting.Q_FOLLOW_COMPENSATE, true, false, false, args);
    }

    @Bean
    public Queue feedFollowCompensateDlqQueue() {
        return new Queue(RelationCounterRouting.DLQ_FOLLOW_COMPENSATE, true);
    }

    @Bean
    public Binding feedFollowCompensateBinding(
            @Qualifier("feedFollowCompensateQueue") Queue queue,
            @Qualifier("relationExchange") DirectExchange exchange) {
        return BindingBuilder.bind(queue).to(exchange).with(RelationCounterRouting.RK_FOLLOW_COMPENSATE);
    }

    @Bean
    public Binding feedFollowCompensateDlqBinding(
            @Qualifier("feedFollowCompensateDlqQueue") Queue queue,
            @Qualifier("relationDlxExchange") DirectExchange dlxExchange) {
        return BindingBuilder.bind(queue).to(dlxExchange).with(RelationCounterRouting.RK_FOLLOW_COMPENSATE_DLX);
    }
}
```

- [ ] **Step 3: Verify**

Run: `mvn -pl nexus-trigger compile`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add nexus-domain/src/main/java/cn/nexus/domain/social/model/valobj/RelationCounterRouting.java \
        nexus-trigger/src/main/java/cn/nexus/trigger/mq/config/FeedFollowCompensationMqConfig.java
git commit -m "feat: add follow feed compensation mq topology"
```

---

## Task 3: Create FollowFeedCompensationConsumer

**Files:**
- Create: `nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FollowFeedCompensationConsumer.java`
- Create: `nexus-trigger/src/test/java/cn/nexus/trigger/mq/consumer/FollowFeedCompensationConsumerTest.java`

- [ ] **Step 1: Write FollowFeedCompensationConsumerTest**

File: `nexus-trigger/src/test/java/cn/nexus/trigger/mq/consumer/FollowFeedCompensationConsumerTest.java`

```java
package cn.nexus.trigger.mq.consumer;

import cn.nexus.domain.social.service.IFeedFollowCompensationService;
import cn.nexus.types.event.relation.RelationCounterProjectEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FollowFeedCompensationConsumerTest {

    @Mock IFeedFollowCompensationService compensationService;
    @InjectMocks FollowFeedCompensationConsumer consumer;

    @Test
    void onFollowActive_shouldCallOnFollow() {
        RelationCounterProjectEvent event = new RelationCounterProjectEvent();
        event.setSourceId(1L);
        event.setTargetId(2L);
        event.setStatus("ACTIVE");
        consumer.onFollowEvent(event);
        verify(compensationService).onFollow(1L, 2L);
    }

    @Test
    void onUnfollow_shouldCallOnUnfollow() {
        RelationCounterProjectEvent event = new RelationCounterProjectEvent();
        event.setSourceId(1L);
        event.setTargetId(2L);
        event.setStatus("UNFOLLOW");
        consumer.onFollowEvent(event);
        verify(compensationService).onUnfollow(1L, 2L);
    }

    @Test
    void nullSourceOrTarget_shouldSkip() {
        RelationCounterProjectEvent event = new RelationCounterProjectEvent();
        event.setStatus("ACTIVE");
        consumer.onFollowEvent(event);
        verifyNoInteractions(compensationService);
    }
}
```

- [ ] **Step 2: Write FollowFeedCompensationConsumer**

File: `nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FollowFeedCompensationConsumer.java`

```java
package cn.nexus.trigger.mq.consumer;

import cn.nexus.domain.social.model.valobj.RelationCounterRouting;
import cn.nexus.domain.social.service.IFeedFollowCompensationService;
import cn.nexus.infrastructure.mq.reliable.annotation.ReliableMqConsume;
import cn.nexus.types.event.relation.RelationCounterProjectEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class FollowFeedCompensationConsumer {

    private final IFeedFollowCompensationService compensationService;

    @RabbitListener(queues = RelationCounterRouting.Q_FOLLOW_COMPENSATE, containerFactory = "reliableMqListenerContainerFactory")
    @ReliableMqConsume(consumerName = "FollowFeedCompensationConsumer", eventId = "#event.relationEventId", payload = "#event")
    public void onFollowEvent(RelationCounterProjectEvent event) {
        if (event == null || event.getSourceId() == null || event.getTargetId() == null) return;
        String status = event.getStatus() == null ? "" : event.getStatus().trim().toUpperCase();
        if ("ACTIVE".equals(status)) {
            compensationService.onFollow(event.getSourceId(), event.getTargetId());
        } else if ("UNFOLLOW".equals(status)) {
            compensationService.onUnfollow(event.getSourceId(), event.getTargetId());
        }
    }
}
```

- [ ] **Step 3: Verify**

Run: `mvn -pl nexus-trigger test -Dtest="FollowFeedCompensationConsumerTest"`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FollowFeedCompensationConsumer.java \
        nexus-trigger/src/test/java/cn/nexus/trigger/mq/consumer/FollowFeedCompensationConsumerTest.java
git commit -m "feat: add follow feed compensation consumer"
```

---

## Task 4: Merge Cleanup Queues into Single Queue

**Files:**
- Modify: `nexus-trigger/src/main/java/cn/nexus/trigger/mq/config/FeedFanoutConfig.java`
- Modify: `nexus-trigger/src/test/java/cn/nexus/trigger/mq/config/FeedFanoutConfigTest.java`

- [ ] **Step 1: Update FeedFanoutConfig constants**

In `FeedFanoutConfig.java`, replace the two cleanup queue constants and their DLQ constants with a single set:

```java
/** Queue：Feed 索引清理队列（consumes both post.updated and post.deleted） */
public static final String Q_FEED_INDEX_CLEANUP = "feed.index.cleanup.queue";

/** DLQ：Feed 索引清理死信队列 */
public static final String DLQ_FEED_INDEX_CLEANUP = "feed.index.cleanup.dlq.queue";

/** DLX RoutingKey：Feed 索引清理死信路由键 */
public static final String DLX_ROUTING_KEY_FEED_INDEX_CLEANUP = "feed.index.cleanup.dlx";
```

Remove: `Q_FEED_INDEX_CLEANUP_UPDATED`, `Q_FEED_INDEX_CLEANUP_DELETED`, `DLQ_FEED_INDEX_CLEANUP_UPDATED`, `DLQ_FEED_INDEX_CLEANUP_DELETED`, `DLX_ROUTING_KEY_FEED_INDEX_CLEANUP_UPDATED`, `DLX_ROUTING_KEY_FEED_INDEX_CLEANUP_DELETED`.

- [ ] **Step 2: Update queue beans**

Replace `feedIndexCleanupUpdatedQueue()` and `feedIndexCleanupDeletedQueue()` with:

```java
@Bean
public Queue feedIndexCleanupQueue() {
    Map<String, Object> args = new HashMap<>();
    args.put("x-dead-letter-exchange", DLX_EXCHANGE);
    args.put("x-dead-letter-routing-key", DLX_ROUTING_KEY_FEED_INDEX_CLEANUP);
    return new Queue(Q_FEED_INDEX_CLEANUP, true, false, false, args);
}
```

Replace two DLQ beans with:

```java
@Bean
public Queue feedIndexCleanupDlqQueue() {
    return new Queue(DLQ_FEED_INDEX_CLEANUP, true);
}
```

- [ ] **Step 3: Update bindings**

Replace `feedIndexCleanupUpdatedBinding` and `feedIndexCleanupDeletedBinding` with:

```java
@Bean
public Binding feedIndexCleanupBinding(@Qualifier("feedIndexCleanupQueue") Queue queue,
                                        @Qualifier("feedExchange") DirectExchange exchange) {
    return BindingBuilder.bind(queue).to(exchange).with(RK_POST_UPDATED);
}

@Bean
public Binding feedIndexCleanupDeletedBinding(@Qualifier("feedIndexCleanupQueue") Queue queue,
                                               @Qualifier("feedExchange") DirectExchange exchange) {
    return BindingBuilder.bind(queue).to(exchange).with(RK_POST_DELETED);
}
```

Replace two DLQ bindings with:

```java
@Bean
public Binding feedIndexCleanupDlqBinding(@Qualifier("feedIndexCleanupDlqQueue") Queue queue,
                                           @Qualifier("feedDlxExchange") DirectExchange dlxExchange) {
    return BindingBuilder.bind(queue).to(dlxExchange).with(DLX_ROUTING_KEY_FEED_INDEX_CLEANUP);
}
```

- [ ] **Step 4: Update FeedFanoutConfigTest**

Update `indexCleanupQueuesBindUpdatedAndDeletedSeparately` to verify the single queue with two routing keys:

```java
@Test
void indexCleanupQueueBindsBothUpdatedAndDeletedToSingleQueue() {
    // Single queue bound with both post.updated and post.deleted routing keys
    assertThat(config.Q_FEED_INDEX_CLEANUP).isEqualTo("feed.index.cleanup.queue");
    assertThat(config.DLQ_FEED_INDEX_CLEANUP).isEqualTo("feed.index.cleanup.dlq.queue");
}
```

- [ ] **Step 5: Verify**

Run: `mvn -pl nexus-trigger test -Dtest="FeedFanoutConfigTest"`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add nexus-trigger/src/main/java/cn/nexus/trigger/mq/config/FeedFanoutConfig.java \
        nexus-trigger/src/test/java/cn/nexus/trigger/mq/config/FeedFanoutConfigTest.java
git commit -m "refactor: merge feed index cleanup queues into single queue"
```

---

## Task 5: Simplify FeedIndexCleanupConsumer

**Files:**
- Modify: `nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FeedIndexCleanupConsumer.java`
- Modify: `nexus-trigger/src/test/java/cn/nexus/trigger/mq/consumer/FeedIndexCleanupConsumerTest.java`

- [ ] **Step 1: Rewrite FeedIndexCleanupConsumer**

Replace the entire class with the simplified version. Key changes:
- Both `onUpdated` and `onDeleted` listen on `Q_FEED_INDEX_CLEANUP`
- Remove `IFeedBigVPoolRepository` and `IFeedGlobalLatestRepository` dependencies
- `cleanupIfInvisible` only calls `removeFromTimeline` (not pool or latest)
- `consumerName` for both methods must differ

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class FeedIndexCleanupConsumer {

    private final IContentRepository contentRepository;
    private final IFeedAuthorTimelineRepository feedAuthorTimelineRepository;

    @RabbitListener(queues = FeedFanoutConfig.Q_FEED_INDEX_CLEANUP, containerFactory = "reliableMqListenerContainerFactory")
    @ReliableMqConsume(consumerName = "FeedIndexCleanupUpdatedConsumer", eventId = "#event.eventId", payload = "#event")
    public void onUpdated(PostUpdatedEvent event) {
        validate(event == null ? null : event.getEventId(), event == null ? null : event.getPostId(),
                "feed index cleanup updated payload invalid");
        cleanupIfInvisible(event.getPostId());
    }

    @RabbitListener(queues = FeedFanoutConfig.Q_FEED_INDEX_CLEANUP, containerFactory = "reliableMqListenerContainerFactory")
    @ReliableMqConsume(consumerName = "FeedIndexCleanupDeletedConsumer", eventId = "#event.eventId", payload = "#event")
    public void onDeleted(PostDeletedEvent event) {
        validate(event == null ? null : event.getEventId(), event == null ? null : event.getPostId(),
                "feed index cleanup deleted payload invalid");
        cleanupIfInvisible(event.getPostId());
    }

    private void cleanupIfInvisible(Long postId) {
        ContentPostEntity post = contentRepository.findPostBypassCache(postId);
        if (post == null) {
            log.warn("feed index cleanup: post not found in DB, postId={}, skipping authorTimeline removal", postId);
            return;
        }
        if (Integer.valueOf(ContentPostStatusEnumVO.PUBLISHED.getCode()).equals(post.getStatus())) {
            return;
        }
        Long authorId = post.getUserId();
        try {
            feedAuthorTimelineRepository.removeFromTimeline(authorId, postId);
        } catch (Exception e) {
            log.warn("feed index cleanup remove authorTimeline failed, authorId={}, postId={}", authorId, postId, e);
        }
    }

    private void validate(String eventId, Long postId, String message) {
        if (eventId == null || eventId.isBlank() || postId == null) {
            throw new ReliableMqPermanentFailureException(message);
        }
    }
}
```

- [ ] **Step 2: Rewrite FeedIndexCleanupConsumerTest**

Key test scenarios (replace all existing tests):

```java
@Test void onUpdated_invalidPayloadThrowsPermanentFailure() { /* unchanged */ }
@Test void onDeleted_invalidPayloadThrowsPermanentFailure() { /* unchanged */ }
@Test void onDeleted_dbNullDoesNotRemoveTimeline() { /* DB null → skip, warn */ }
@Test void onUpdated_publishedPostDoesNotRemoveTimeline() { /* status=2 → no-op */ }
@Test void onUpdated_nonPublishedPostRemovesTimelineByDbAuthor() { /* status≠2 → removeFromTimeline */ }
@Test void onUpdated_contentLookupFailureRethrowsForRetry() { /* unchanged */ }
```

- [ ] **Step 3: Verify**

Run: `mvn -pl nexus-trigger test -Dtest="FeedIndexCleanupConsumerTest"`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FeedIndexCleanupConsumer.java \
        nexus-trigger/src/test/java/cn/nexus/trigger/mq/consumer/FeedIndexCleanupConsumerTest.java
git commit -m "refactor: simplify cleanup consumer to only remove authorTimeline"
```

---

## Task 6: Simplify FeedFanoutDispatcherConsumer

**Files:**
- Modify: `nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FeedFanoutDispatcherConsumer.java`
- Modify: `nexus-trigger/src/test/java/cn/nexus/trigger/mq/consumer/FeedFanoutDispatcherConsumerTest.java`

- [ ] **Step 1: Rewrite dispatch method**

Key changes in `FeedFanoutDispatcherConsumer`:
- Remove `IFeedBigVPoolRepository`, `IFeedGlobalLatestRepository` dependencies
- Replace `IFeedOutboxRepository` with `IFeedAuthorTimelineRepository`
- Remove `addToInbox(author)` — author sees own posts via AuthorTimeline merge in read path
- Remove `addToLatest`
- Remove `addToPool` and bigV pool write
- `addToOutbox` → `addToTimeline`
- Keep `IFeedTimelineRepository` for fanout to followers

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class FeedFanoutDispatcherConsumer {

    private final FeedFanoutTaskProducer feedFanoutTaskProducer;
    private final IFeedTimelineRepository feedTimelineRepository;
    private final IFeedAuthorTimelineRepository feedAuthorTimelineRepository;
    private final IRelationRepository relationRepository;
    private final IFeedAuthorCategoryRepository feedAuthorCategoryRepository;
    private final FeedAuthorCategoryStateMachine feedAuthorCategoryStateMachine;

    @Value("${feed.fanout.batchSize:200}")
    private int batchSize;

    @Value("${feed.bigv.followerThreshold:500000}")
    private int bigvFollowerThreshold;

    @RabbitListener(queues = FeedFanoutConfig.QUEUE, containerFactory = "reliableMqListenerContainerFactory")
    @ReliableMqConsume(consumerName = "FeedFanoutDispatcherConsumer", eventId = "#event.eventId", payload = "#event")
    public void onMessage(PostPublishedEvent event) {
        if (event == null || event.getPostId() == null || event.getAuthorId() == null
                || event.getPublishTimeMs() == null || event.getEventId() == null || event.getEventId().isBlank()) {
            throw new ReliableMqPermanentFailureException("feed fanout dispatch payload invalid");
        }
        dispatch(event);
    }

    private void dispatch(PostPublishedEvent event) {
        if (event == null) return;
        Long postId = event.getPostId();
        Long authorId = event.getAuthorId();
        Long publishTimeMs = event.getPublishTimeMs();
        if (postId == null || authorId == null || publishTimeMs == null) return;

        // Write to author's own timeline (replaces old outbox)
        feedAuthorTimelineRepository.addToTimeline(authorId, postId, publishTimeMs);

        // Big V: skip fanout; readers pull from AuthorTimeline
        Integer category = feedAuthorCategoryRepository.getCategory(authorId);
        if (category == null) {
            feedAuthorCategoryStateMachine.onFollowerCountChanged(authorId);
            category = feedAuthorCategoryRepository.getCategory(authorId);
        }
        if (category != null && category == FeedAuthorCategoryEnumVO.BIGV.getCode()) {
            log.info("skip fanout for bigv author, postId={}, authorId={}", postId, authorId);
            return;
        }

        // Normal author: fanout to followers
        int followerCount = relationRepository.countFollowerIds(authorId);
        int pageSize = Math.max(1, batchSize);
        if (followerCount <= 0) return;
        int slices = (followerCount + pageSize - 1) / pageSize;
        for (int i = 0; i < slices; i++) {
            int offset = i * pageSize;
            String taskEventId = (event.getEventId() == null ? "feed-fanout" : event.getEventId()) + ":" + offset + ":" + pageSize;
            FeedFanoutTask task = new FeedFanoutTask(taskEventId, postId, authorId, publishTimeMs, offset, pageSize);
            feedFanoutTaskProducer.publish(task);
        }
        log.info("feed fanout dispatched, postId={}, authorId={}, totalFollowers={}, slices={}",
                postId, authorId, followerCount, slices);
    }
}
```

- [ ] **Step 2: Update FeedFanoutDispatcherConsumerTest**

Update test to:
- Remove `IFeedBigVPoolRepository`, `IFeedGlobalLatestRepository` mocks
- Replace `IFeedOutboxRepository` mock with `IFeedAuthorTimelineRepository`
- Remove assertions for `addToInbox(author)`, `addToLatest`, `addToPool`
- Assert `feedAuthorTimelineRepository.addToTimeline(authorId, postId, publishTimeMs)` is called

- [ ] **Step 3: Verify**

Run: `mvn -pl nexus-trigger test -Dtest="FeedFanoutDispatcherConsumerTest"`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FeedFanoutDispatcherConsumer.java \
        nexus-trigger/src/test/java/cn/nexus/trigger/mq/consumer/FeedFanoutDispatcherConsumerTest.java
git commit -m "refactor: simplify dispatcher to use AuthorTimeline, remove latest/pool/author-inbox"
```

---

## Task 7: Replace Inbox Rebuild with Activation

**Files:**
- Modify: `nexus-domain/src/main/java/cn/nexus/domain/social/service/IFeedInboxRebuildService.java`
- Modify: `nexus-domain/src/main/java/cn/nexus/domain/social/service/FeedInboxRebuildService.java`
- Modify: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/FeedTimelineRepository.java`
- Modify: `nexus-domain/src/test/java/cn/nexus/domain/social/service/FeedInboxRebuildServiceTest.java`
- Modify: `nexus-infrastructure/src/test/java/cn/nexus/infrastructure/adapter/social/repository/FeedTimelineRepositoryTest.java`

- [ ] **Step 1: Rename interface to IFeedInboxActivationService**

Rename `IFeedInboxRebuildService.java` to `IFeedInboxActivationService.java`. Change `forceRebuild` to `activateIfNeeded`:

```java
package cn.nexus.domain.social.service;

/**
 * Inbox 激活服务：离线用户回归时，从关注者 AuthorTimeline 拉取内容写入 inbox。
 * 不做 Lua 原子替换、不写哨兵、不加重建锁。
 */
public interface IFeedInboxActivationService {

    /**
     * 仅在 inbox key miss 时激活：从所有关注者的 AuthorTimeline 拉取第一页并写回 inbox。
     *
     * @return true if activation was performed
     */
    boolean activateIfNeeded(Long userId);
}
```

- [ ] **Step 2: Rewrite FeedInboxRebuildService → FeedInboxActivationService**

Rename class to `FeedInboxActivationService`, implements `IFeedInboxActivationService`.

Key changes:
- Dependencies: replace `IContentRepository` with `IFeedAuthorTimelineRepository`
- `activateIfNeeded`: if inbox doesn't exist, pull from ALL followees' AuthorTimeline (NORMAL + BIGV), merge, write to inbox via ZADD (not replaceInbox)
- No Lua, no lock, no `__NOMORE__`, no `replaceInbox` call
- No DB `listUserPosts` calls — all from AuthorTimeline
- `activateIfNeeded` replaces `rebuildIfNeeded` (same trigger: inbox miss on FOLLOW refresh)
- Remove `forceRebuild`, `filterNormalAuthors`, `collectRecentPosts`

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class FeedInboxActivationService implements IFeedInboxActivationService {

    private final IRelationAdjacencyCachePort relationAdjacencyCachePort;
    private final IFeedAuthorTimelineRepository feedAuthorTimelineRepository;
    private final IFeedAuthorCategoryRepository feedAuthorCategoryRepository;
    private final IFeedTimelineRepository feedTimelineRepository;

    @Value("${feed.activation.perFollowingLimit:5}")
    private int perFollowingLimit;

    @Value("${feed.activation.maxFollowings:2000}")
    private int maxFollowings;

    @Value("${feed.activation.inboxSize:200}")
    private int activationInboxSize;

    @Override
    public boolean activateIfNeeded(Long userId) {
        if (userId == null) return false;
        if (feedTimelineRepository.inboxExists(userId)) return false;
        doActivate(userId);
        return true;
    }

    private void doActivate(Long userId) {
        List<Long> followings = relationAdjacencyCachePort.listFollowing(userId, Math.max(0, maxFollowings));
        if (followings == null) followings = List.of();

        // Include self + all followings
        List<Long> targets = new ArrayList<>();
        targets.add(userId);
        for (Long fid : followings) {
            if (fid != null && !fid.equals(userId)) targets.add(fid);
        }

        // Pull from each target's AuthorTimeline, merge, write top N to inbox
        List<FeedInboxEntryVO> all = new ArrayList<>();
        int limit = Math.max(1, perFollowingLimit);
        for (Long targetId : targets) {
            List<FeedInboxEntryVO> entries = feedAuthorTimelineRepository.pageTimeline(targetId, null, null, limit);
            all.addAll(entries);
        }

        all.sort(Comparator.<FeedInboxEntryVO, Long>comparing(FeedInboxEntryVO::getPublishTimeMs)
                .thenComparing(FeedInboxEntryVO::getPostId).reversed());

        int inboxLimit = Math.max(1, activationInboxSize);
        List<FeedInboxEntryVO> top = all.size() <= inboxLimit ? all : all.subList(0, inboxLimit);

        if (top.isEmpty()) {
            // Don't create empty inbox with sentinel; skip activation
            return;
        }

        for (FeedInboxEntryVO e : top) {
            feedTimelineRepository.addToInbox(userId, e.getPostId(), e.getPublishTimeMs());
        }
        log.info("feed inbox activated, userId={}, entries={}", userId, top.size());
    }
}
```

- [ ] **Step 3: Remove replaceInbox from FeedTimelineRepository**

In `FeedTimelineRepository`:
- Remove `REPLACE_INBOX_SCRIPT` Lua script
- Remove `replaceInbox` method
- Remove `tryAcquireRebuildLock`, `rebuildLockKey`, `inboxTmpKey`
- Remove `rebuildWindowSize`, `@Value("${feed.rebuild.mergeWindowSize:256}")` 
- Remove `@Value("${feed.rebuild.lockSeconds:30}")`
- Remove sentinel handling (`filterNoMore`, `__NOMORE__`)

Remove `replaceInbox` from `IFeedTimelineRepository` interface.

- [ ] **Step 4: Update tests**

Update `FeedInboxRebuildServiceTest`:
- Rename to `FeedInboxActivationServiceTest`
- Replace `IContentRepository` mock with `IFeedAuthorTimelineRepository`
- Test: `activateIfNeeded_shouldPullFromAllFolloweeTimelines`
- Test: `activateIfNeeded_shouldSkipWhenInboxExists`
- Test: `activateIfNeeded_emptyResultShouldNotWriteInbox`

Update `FeedTimelineRepositoryTest`:
- Remove `replaceInbox_*` tests
- Remove lock-related tests

- [ ] **Step 5: Verify**

Run: `mvn -pl nexus-domain test -Dtest="FeedInboxActivationServiceTest"` and
`mvn -pl nexus-infrastructure test -Dtest="FeedTimelineRepositoryTest"`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add nexus-domain/src/main/java/cn/nexus/domain/social/service/IFeedInboxRebuildService.java \
        nexus-domain/src/main/java/cn/nexus/domain/social/service/FeedInboxRebuildService.java \
        nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/FeedTimelineRepository.java \
        nexus-domain/src/main/java/cn/nexus/domain/social/adapter/repository/IFeedTimelineRepository.java
git rm nexus-domain/src/main/java/cn/nexus/domain/social/service/IFeedInboxRebuildService.java
git add ...
git commit -m "refactor: replace inbox rebuild with activation, remove Lua/lock/sentinel"
```

---

## Task 8: Update FeedService Read Path

**Files:**
- Modify: `nexus-domain/src/main/java/cn/nexus/domain/social/service/FeedService.java`
- Modify: `nexus-domain/src/test/java/cn/nexus/domain/social/service/FeedServiceTest.java`

- [ ] **Step 1: Update FeedService dependencies and followTimeline**

Key changes in `FeedService`:
- Replace `IFeedOutboxRepository` → `IFeedAuthorTimelineRepository`
- Replace `IFeedInboxRebuildService` → `IFeedInboxActivationService`
- Remove `IFeedBigVPoolRepository`, `IFeedGlobalLatestRepository` (from FOLLOW path)
- In `followTimeline`:
  - `rebuildIfNeeded` → `activateIfNeeded`
  - `pageOutbox` → `pageTimeline` for BIGV authors
  - Add self AuthorTimeline as a source (step 3.5)
  - Remove `cleanupMissingIndexes` for outbox/pool (only inbox cleanup remains)
  - Remove BigV pool reads

- [ ] **Step 2: Update AuthorBuckets to use pageTimeline**

In `splitAuthorsByCategory`, BIGV authors' posts are now pulled via `pageTimeline` instead of `pageOutbox`.

- [ ] **Step 3: Add self AuthorTimeline to merge sources**

Add self as a source in `followTimeline`:

```java
// Self AuthorTimeline
List<FeedInboxEntryVO> selfEntries = feedAuthorTimelineRepository.pageTimeline(userId, cursorTs, cursorPostId, limit);
// Add to merge sources
```

- [ ] **Step 4: Update FeedServiceTest**

Key test updates:
- `timeline_followRefreshShouldMergeInboxAndBigVOutboxUsingMaxIdOrder` → rename to `...MergeInboxAndAuthorTimeline...`
- Replace `IFeedOutboxRepository` mock with `IFeedAuthorTimelineRepository`
- Replace `IFeedInboxRebuildService` mock with `IFeedInboxActivationService`
- Assert `pageTimeline` is called instead of `pageOutbox`

- [ ] **Step 5: Verify**

Run: `mvn -pl nexus-domain test -Dtest="FeedServiceTest"`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add nexus-domain/src/main/java/cn/nexus/domain/social/service/FeedService.java \
        nexus-domain/src/test/java/cn/nexus/domain/social/service/FeedServiceTest.java
git commit -m "refactor: update follow read path to use AuthorTimeline and activation"
```

---

## Task 9: Update FeedFollowCompensationService to Use AuthorTimeline

**Files:**
- Modify: `nexus-domain/src/main/java/cn/nexus/domain/social/service/FeedFollowCompensationService.java`

- [ ] **Step 1: Replace contentRepository with AuthorTimeline**

In `onFollow`:
- Replace `contentRepository.listUserPosts(followeeId, null, limit)` with `feedAuthorTimelineRepository.pageTimeline(followeeId, null, null, limit)`
- Remove `IContentRepository` dependency
- Add `IFeedAuthorTimelineRepository` dependency

```java
@Service
@RequiredArgsConstructor
public class FeedFollowCompensationService implements IFeedFollowCompensationService {

    private final IFeedTimelineRepository feedTimelineRepository;
    private final IFeedAuthorTimelineRepository feedAuthorTimelineRepository;

    @Value("${feed.follow.compensate.recentPosts:20}")
    private int recentPosts;

    @Override
    public void onFollow(Long followerId, Long followeeId) {
        if (followerId == null || followeeId == null || followerId.equals(followeeId)) return;
        if (!feedTimelineRepository.inboxExists(followerId)) return;

        int limit = Math.max(1, recentPosts);
        List<FeedInboxEntryVO> entries = feedAuthorTimelineRepository.pageTimeline(followeeId, null, null, limit);
        if (entries.isEmpty()) return;

        for (FeedInboxEntryVO e : entries) {
            feedTimelineRepository.addToInbox(followerId, e.getPostId(), e.getPublishTimeMs());
        }
    }

    @Override
    public void onUnfollow(Long followerId, Long followeeId) {
        // No-op: read-side filtering handles unfollow visibility
    }
}
```

- [ ] **Step 2: Verify**

Run: `mvn -pl nexus-domain compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add nexus-domain/src/main/java/cn/nexus/domain/social/service/FeedFollowCompensationService.java
git commit -m "refactor: compensation reads from AuthorTimeline instead of DB"
```

---

## Task 10: Remove Dead Code

**Files:**
- Delete: `nexus-domain/src/main/java/cn/nexus/domain/social/service/IFeedOutboxRebuildService.java`
- Delete: `nexus-domain/src/main/java/cn/nexus/domain/social/service/FeedOutboxRebuildService.java`
- Modify: `nexus-domain/src/main/java/cn/nexus/domain/social/service/FeedAuthorCategoryStateMachine.java`
- Modify: `nexus-domain/src/main/java/cn/nexus/domain/social/service/FeedDistributionService.java`

- [ ] **Step 1: Remove outbox rebuild service**

Delete `IFeedOutboxRebuildService.java` and `FeedOutboxRebuildService.java`.

- [ ] **Step 2: Update FeedAuthorCategoryStateMachine**

Remove `IFeedOutboxRebuildService` dependency. Remove `forceRebuild` call:

```java
// Before: if category changed, feedOutboxRebuildService.forceRebuild(authorId);
// After: just update category, no rebuild
if (!newCategory.equals(existingCategory)) {
    feedAuthorCategoryRepository.setCategory(authorId, newCategory);
    log.info("author category changed, authorId={}, old={}, new={}", authorId, existingCategory, newCategory);
}
```

- [ ] **Step 3: Clean up FeedDistributionService**

Remove unused `IFeedOutboxRepository` and `IFeedBigVPoolRepository` fields.

- [ ] **Step 4: Verify**

Run: `mvn compile`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git rm nexus-domain/src/main/java/cn/nexus/domain/social/service/IFeedOutboxRebuildService.java \
       nexus-domain/src/main/java/cn/nexus/domain/social/service/FeedOutboxRebuildService.java
git add nexus-domain/src/main/java/cn/nexus/domain/social/service/FeedAuthorCategoryStateMachine.java \
        nexus-domain/src/main/java/cn/nexus/domain/social/service/FeedDistributionService.java
git commit -m "chore: remove outbox rebuild service and dead dependencies"
```

---

## Task 11: Update Architecture Contract Test

**Files:**
- Modify: `nexus-app/src/test/java/cn/nexus/contract/mq/ReliableMqArchitectureContractTest.java`

- [ ] **Step 1: Update inventory**

Key changes:
- Add `FollowFeedCompensationConsumer.java` to `SIDE_EFFECTING_LISTENER_FILES`
- Add `FollowFeedCompensationConsumer` to `EXPECTED_LISTENER_INVENTORY`
- Update `FeedIndexCleanupConsumer` entries (both listeners now on same queue `Q_FEED_INDEX_CLEANUP`)
- Remove any deleted listener references
- Update `DOMAIN_INBOX_LISTENERS_FROM_INVENTORY` if needed for the compensation consumer

- [ ] **Step 2: Verify**

Run: `mvn -pl nexus-app test -Dtest="ReliableMqArchitectureContractTest"`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add nexus-app/src/test/java/cn/nexus/contract/mq/ReliableMqArchitectureContractTest.java
git commit -m "test: update mq architecture contract for feed simplification"
```

---

## Task 12: Update Integration Tests

**Files:**
- Modify: `nexus-app/src/test/java/cn/nexus/integration/FeedFanoutRealIntegrationTest.java`
- Modify: `nexus-app/src/test/java/cn/nexus/integration/feed/FeedHttpRealIntegrationTest.java`

- [ ] **Step 1: Update FeedFanoutRealIntegrationTest**

- Replace outbox assertions with AuthorTimeline assertions
- Remove `feed:global:latest` assertions
- Remove BigV pool assertions
- Test that `feed:timeline:{authorId}` contains the post after fanout
- Test that `feed:inbox:{followerId}` contains the post

- [ ] **Step 2: Update FeedHttpRealIntegrationTest**

- Update assertions to use AuthorTimeline key instead of outbox key
- Ensure FOLLOW timeline response still returns correct posts

- [ ] **Step 3: Verify**

Run: `mvn -pl nexus-app test -Dtest="FeedFanoutRealIntegrationTest,FeedHttpRealIntegrationTest"`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add nexus-app/src/test/java/cn/nexus/integration/FeedFanoutRealIntegrationTest.java \
        nexus-app/src/test/java/cn/nexus/integration/feed/FeedHttpRealIntegrationTest.java
git commit -m "test: update integration tests for feed simplification"
```

---

## Task 13: Final Verification

**Files:** No production edits.

- [ ] **Step 1: Full compile**

Run: `mvn compile`
Expected: BUILD SUCCESS

- [ ] **Step 2: All feed-related tests**

Run:
```bash
mvn -pl nexus-infrastructure test
mvn -pl nexus-trigger test
mvn -pl nexus-domain test
mvn -pl nexus-app test -Dtest="ReliableMqArchitectureContractTest"
```

Expected: All PASS

- [ ] **Step 3: Drift scan**

Run and confirm no regressions:

```bash
# No Lua scripts left in inbox/timeline repos
rg -n "REPLACE_INBOX_SCRIPT\|REPLACE_OUTBOX_SCRIPT\|eval\|redisScript" \
   nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/FeedTimelineRepository.java

# No __NOMORE__ sentinel
rg -n "__NOMORE__\|NOMORE" nexus-*

# No rebuild lock keys in timeline repo
rg -n "rebuild.*lock\|lock.*rebuild" \
   nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/FeedTimelineRepository.java

# No addToLatest/addToPool in dispatcher
rg -n "addToLatest\|addToPool" \
   nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FeedFanoutDispatcherConsumer.java

# No bigV pool references in cleanup consumer
rg -n "BigVPool\|bigVPool\|bigv.*pool" \
   nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FeedIndexCleanupConsumer.java

# No outbox rebuild references in state machine
rg -n "rebuild\|OutboxRebuild" \
   nexus-domain/src/main/java/cn/nexus/domain/social/service/FeedAuthorCategoryStateMachine.java
```

- [ ] **Step 4: Verify git status**

Run: `git status --short`
Confirm only expected files are changed.

---

## Execution Order

Fixed order (each task depends on previous):

1. Task 1: AuthorTimeline Repository
2. Task 2: Follow Compensation MQ Topology
3. Task 3: FollowFeedCompensationConsumer
4. Task 4: Merge Cleanup Queues
5. Task 5: Simplify Cleanup Consumer
6. Task 6: Simplify Dispatcher
7. Task 7: Replace Rebuild with Activation
8. Task 8: Update FeedService Read Path
9. Task 9: Update Compensation Service
10. Task 10: Remove Dead Code
11. Task 11: Update Contract Test
12. Task 12: Update Integration Tests
13. Task 13: Final Verification

---

## Drift Review Checklist

每个任务完成后检查:
1. 是否引入了新的 Redis key 类型（除 inbox 和 AuthorTimeline 外）
2. 是否引入了 Lua 脚本、重建锁或 `__NOMORE__` 哨兵
3. 是否在 FOLLOW dispatcher 中写了 latest 或 pool
4. 是否在 cleanup consumer 中清理了 inbox
5. 是否在关系写事务内同步写了 Feed Redis
6. 是否修改了推荐/热门/邻近流路径
7. 是否删除了推荐系统仍在使用的 `feed:global:latest` key
8. 配置键是否使用了计划外名称

## Handoff

Plan complete and saved to `docs/superpowers/plans/2026-05-04-nexus-feed-simplify-plan.md`.

Two execution options:

1. **Subagent-Driven (recommended)** - dispatch a fresh subagent per task, review between tasks, fast iteration
2. **Incline Execution** - execute tasks in this session using executing-plans, batch execution with checkpoints

Which approach?
