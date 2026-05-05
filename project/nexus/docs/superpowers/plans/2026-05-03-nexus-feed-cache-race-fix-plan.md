# Nexus Feed Cache Race Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复 Nexus Feed inbox/outbox 重建覆盖并发写入的竞态，并补齐 `post.updated` / `post.deleted` 对 Feed 聚合索引的清理链路。

**Architecture:** 保持现有 Redis ZSET + RabbitMQ + repository/service 边界不变。重建逻辑改为 Redis Lua 内的原子替换：非空快照使用“临时 key 构造 + 有界最新端旧成员合并 + 裁剪 + TTL + 原子 rename”，outbox 空快照在同一 Lua 入口内原子删除正式 key。清理链路新增独立 MQ 队列和 consumer，按 DB 当前状态清理 outbox、bigV pool、global latest。

**Tech Stack:** Java 17, Spring Boot, Redis Lua, RabbitMQ, MyBatis-Plus, Maven.

---

## Hard Boundaries

这些规则是实现门禁，不是建议：

1. 不引入 Redis Stream、CDC 新链路、新业务表、全局版本服务、分布式事务、长期后台补偿任务。
2. 不重写 Feed 架构；FOLLOW、RECOMMEND、POPULAR、NEIGHBORS、PROFILE 入口和语义不变。
3. 不主动扫描粉丝 inbox，不主动清推荐 session，不重建所有关注者 timeline。
4. 不修改 Count 系统语义，不改变 card/detail/content 缓存失效原链路。
5. 不用 `operatorId` 推断作者；清 outbox/pool 的 authorId 只能来自 `findPostBypassCache(postId)` 返回的 `post.userId`。
6. 不把所有 `post.updated` 当作不可见删除；只有 DB 当前状态不是 `PUBLISHED(2)` 才清聚合索引。
7. 不让一个 RabbitMQ queue 被两个不同 payload 类型的 listener 竞争消费。
8. 不在 Lua 或 Java 中对 inbox/outbox 做全量旧集合合并；禁止 `ZRANGE real 0 -1`、`ZREVRANGE real 0 -1` 或等价无界读取。
9. 不在 rename 后再补做 trim/expire；非空替换的裁剪、TTL、rename 必须处于同一个 Redis Lua 脚本。
10. 不把空 outbox 重建实现成 Java 侧 `delete(outboxKey)` 后返回；空 outbox 必须走同一个 Lua 重建入口。

## Command Context

除非任务明确给出别的目录，所有 `mvn`、`rg`、`git` 命令都在 `project/nexus` 目录执行。不要在 workspace 根目录执行 Maven 模块命令。

## Implementation Map

- `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/FeedTimelineRepository.java`
  - inbox 原子有界重建。
  - 读取 `feed.rebuild.mergeWindowSize` 和现有 `feed.rebuild.lockSeconds`。
- `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/config/FeedOutboxProperties.java`
  - outbox 重建合并窗口配置。
- `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/FeedOutboxRepository.java`
  - outbox 原子有界重建和重建锁。
- `nexus-domain/src/main/java/cn/nexus/domain/social/adapter/repository/IFeedGlobalLatestRepository.java`
  - 增加 latest 删除接口。
- `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/FeedGlobalLatestRepository.java`
  - 实现 latest 删除。
- `nexus-trigger/src/main/java/cn/nexus/trigger/mq/config/FeedFanoutConfig.java`
  - 增加 index cleanup updated/deleted 队列、DLQ 和 binding。
- `nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FeedIndexCleanupConsumer.java`
  - 消费 `post.updated` / `post.deleted` 并按 DB 当前状态清理聚合索引。
- `nexus-domain/src/main/java/cn/nexus/domain/social/service/KnowpostCounterSideEffectListener.java`
  - 只升级异常日志级别。
- `nexus-domain/src/main/java/cn/nexus/domain/social/service/IFeedDistributionService.java`
  - 零调用确认后删除 `fanout(PostPublishedEvent)`。
- `nexus-domain/src/main/java/cn/nexus/domain/social/service/FeedDistributionService.java`
  - 零调用确认后删除 `fanout(PostPublishedEvent)` 实现。
- `nexus-domain/src/main/java/cn/nexus/domain/social/adapter/port/IContentDispatchPort.java`
  - 零调用且无 bean 依赖确认后删除。
- `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/ContentDispatchPort.java`
  - 零调用且无 bean 依赖确认后删除。
- `nexus-app/src/test/java/cn/nexus/contract/mq/ReliableMqArchitectureContractTest.java`
  - 同步 MQ 架构契约。

## Configuration Contract

1. Inbox 合并窗口外部配置键固定为 `feed.rebuild.mergeWindowSize`，默认 `256`。
2. Inbox 现有锁配置键保持 `feed.rebuild.lockSeconds`，默认 `30`。
3. Outbox 合并窗口外部配置键固定为 `feed.outbox.rebuildMergeWindowSize`，默认 `256`。
4. Outbox 新增锁配置键固定为 `feed.outbox.rebuildLockSeconds`，默认 `30`。
5. 实际窗口计算固定为 `min(max(0, configuredWindow), maxSize)`；`maxSize` 固定使用对应 inbox/outbox 的当前 maxSize。
6. `FeedInboxProperties` 当前 prefix 是 `feed.inbox`。不得把 `feed.inbox.rebuildMergeWindowSize` 当成本次权威配置键。若实现选择向 `FeedInboxProperties` 添加字段，必须另行保留 `feed.rebuild.mergeWindowSize` 的读取入口；本计划优先使用 repository 中的 `@Value("${feed.rebuild.mergeWindowSize:256}")`。

## Task 1: Inbox Atomic Bounded Rebuild

**Files:**
- Modify: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/FeedTimelineRepository.java`

- [ ] **Step 1: 写 inbox 重建竞态测试**

覆盖以下行为：

1. 非空快照重建时，最终正式 inbox 同时包含本次 entries 和 real inbox 最新端窗口内旧成员。
2. 成员数不超过 `feed.inbox.maxSize`。
3. 正式 inbox 有 TTL。
4. Lua 执行结束后 tmpKey 不存在。
5. 空 entries 是权威空快照：最终只保留 `__NOMORE__`，不合并 real inbox 旧成员。
6. 非空快照接近 `maxSize` 时，真实 post member 优先；`__NOMORE__` 可以被裁剪，不得为了保留哨兵挤掉真实帖子。

测试不依赖线程睡眠判断竞态；直接预置 real key，再调用 `replaceInbox` 验证有界合并语义。

- [ ] **Step 2: 实现 inbox Lua 替换**

实现规则：

1. 方法签名不变：`replaceInbox(Long userId, List<FeedInboxEntryVO> entries)`。
2. 保留现有 `tryAcquireRebuildLock(userId)`；获取锁失败直接返回。
3. 使用唯一 tmpKey 构造新 ZSET，脚本内完成写 entries、写 `__NOMORE__`、有界合并、裁剪、TTL、rename。
4. 非空 entries 时，从 real inbox 最新端合并 `windowSize` 条旧成员；空 entries 时不合并旧成员。
5. Lua ARGV 必须只携带有限 entries 和有限配置值；不得把 real 旧集合在 Java 侧读出后传入 Lua。
6. 裁剪语义保留“最新 score 端”，不得把 `__NOMORE__` 之外的低分旧成员长期带回。
7. `ttl().getSeconds()` 进入 Lua 前必须大于 0。
8. `__NOMORE__` 只在空快照中是强制保留成员；非空快照按 score 裁剪即可。

- [ ] **Step 3: 清理旧路径**

确认 `replaceInbox` 内不再存在这些旧实现点：

1. Java 侧对 tmpKey 逐步写入后直接 `stringRedisTemplate.rename(tmpKey, inboxKey)`。
2. rename 后再对正式 key 执行 `expire`。
3. rename 后再对正式 key 执行 `trimToMaxSize`。
4. 任何无界 `range` / `reverseRange` 读取 real inbox。

- [ ] **Step 4: 验证**

运行：

- `mvn -pl nexus-infrastructure test -Dtest="*FeedTimeline*"`
- 若没有匹配测试类，运行 `mvn -pl nexus-infrastructure test`。

提交点：`fix: make inbox rebuild atomic with bounded merge window`

## Task 2: Outbox Atomic Bounded Rebuild

**Files:**
- Modify: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/config/FeedOutboxProperties.java`
- Modify: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/FeedOutboxRepository.java`

- [ ] **Step 1: 添加 outbox 配置**

在 `FeedOutboxProperties` 增加 `rebuildMergeWindowSize`，默认 `256`，绑定外部键 `feed.outbox.rebuildMergeWindowSize`。

不得修改 `maxSize`、`ttlDays` 默认值和语义。

- [ ] **Step 2: 写 outbox 重建测试**

覆盖以下行为：

1. 非空快照重建合并 real outbox 最新端窗口内旧成员。
2. 成员数不超过 `feed.outbox.maxSize`。
3. 正式 outbox 有 TTL。
4. Lua 执行结束后 tmpKey 不存在。
5. 空 entries 在 Lua 内原子删除正式 outbox，不直接走 Java 侧 delete 分支，不合并旧成员。
6. 两个连续 `replaceOutbox` 在锁未释放时，第二个直接跳过。

- [ ] **Step 3: 添加 outbox 重建锁**

实现规则：

1. 锁 key 固定为 `feed:outbox:rebuild:lock:{authorId}`。
2. 使用 `SETNX + TTL`。
3. TTL 配置键固定为 `feed.outbox.rebuildLockSeconds`，默认 `30`。
4. 获取锁失败直接返回，不等待、不自旋、不阻塞发布写入。
5. 不主动释放锁；依赖 TTL 结束，保持和 inbox 当前重建锁模型一致。

- [ ] **Step 4: 实现 outbox Lua 替换**

实现规则：

1. 方法签名不变：`replaceOutbox(Long authorId, List<FeedInboxEntryVO> entries)`。
2. Lua 内完成写 entries、有界合并、裁剪、TTL、rename。
3. 非空 entries 时，从 real outbox 最新端合并 `windowSize` 条旧成员；空 entries 时不合并旧成员。
4. outbox 不写 `__NOMORE__` 哨兵。
5. 空 entries 时，Lua 分支执行 `DEL real` 并确保 tmpKey 不残留；不得尝试 rename 一个不存在的空 tmp ZSET。
6. 禁止 Java 侧直接 `delete(outboxKey)` 处理空 entries。
7. 禁止 rename 后再 trim/expire。

- [ ] **Step 5: 验证**

运行：

- `mvn -pl nexus-infrastructure test -Dtest="*FeedOutbox*"`
- 若没有匹配测试类，运行 `mvn -pl nexus-infrastructure test`。

提交点：`fix: make outbox rebuild atomic with bounded merge window`

## Task 3: Global Latest Delete API

**Files:**
- Modify: `nexus-domain/src/main/java/cn/nexus/domain/social/adapter/repository/IFeedGlobalLatestRepository.java`
- Modify: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/FeedGlobalLatestRepository.java`

- [ ] **Step 1: 增加接口**

新增 `removeFromLatest(Long postId)`。

规则：

1. `postId == null` 直接返回。
2. 只删除 `feed:global:latest` 里的同名 member。
3. 不回表、不扫描、不触碰推荐系统。

- [ ] **Step 2: 补测试**

覆盖 null 幂等、存在 member 删除、不存在 member 删除不抛异常。

- [ ] **Step 3: 验证**

运行 `mvn -pl nexus-infrastructure test -Dtest="*FeedGlobalLatest*"`；若没有匹配测试类，运行 `mvn -pl nexus-infrastructure test`。

提交点：`feat: add global latest delete API`

## Task 4: Feed Index Cleanup MQ Topology

**Files:**
- Modify: `nexus-trigger/src/main/java/cn/nexus/trigger/mq/config/FeedFanoutConfig.java`

- [ ] **Step 1: 添加 routing key 常量**

在 `FeedFanoutConfig` 添加：

1. `RK_POST_UPDATED = "post.updated"`
2. `RK_POST_DELETED = "post.deleted"`

若其他配置类已有同名字面量，不迁移现有推荐 delete 逻辑；本次只保证新增 cleanup 队列绑定清晰。

- [ ] **Step 2: 添加 cleanup queue 和 DLQ**

新增四个队列：

1. `feed.index.cleanup.updated.queue`
2. `feed.index.cleanup.deleted.queue`
3. `feed.index.cleanup.updated.dlq.queue`
4. `feed.index.cleanup.deleted.dlq.queue`

新增队列必须复用 `social.feed` 和 `social.feed.dlx.exchange`，不得新增 exchange。

- [ ] **Step 3: 添加 binding**

绑定规则固定为：

1. `feed.index.cleanup.updated.queue` 绑定 `social.feed / post.updated`。
2. `feed.index.cleanup.deleted.queue` 绑定 `social.feed / post.deleted`。
3. 两个 DLQ 分别绑定自己的 DLX routing key。

`post.deleted` 同时被推荐 item delete 队列和 index cleanup delete 队列消费是预期行为。

- [ ] **Step 4: 契约影响检查**

当前 `ReliableMqArchitectureContractTest` 只盘点 raw publish 和 `@RabbitListener` 方法，不盘点 queue bean/binding bean。仅新增 queue/binding 时，不修改该契约测试。

若删除 `ContentDispatchPort` 会改变 raw publish allowed list，必须在 Task 8 同步处理；不得在本任务提前删除旧 port 相关 allowlist，除非 Task 8 同步完成。

- [ ] **Step 5: 验证**

运行 `mvn -pl nexus-trigger test`。

提交点：`feat: add feed index cleanup mq topology`

## Task 5: Feed Index Cleanup Consumer

**Files:**
- Create: `nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FeedIndexCleanupConsumer.java`
- Modify: `nexus-app/src/test/java/cn/nexus/contract/mq/ReliableMqArchitectureContractTest.java`

- [ ] **Step 1: 写 consumer 单测**

覆盖以下分支：

1. `post.updated` payload 缺少 `eventId` 或 `postId`，抛永久失败异常。
2. `post.deleted` payload 缺少 `eventId` 或 `postId`，抛永久失败异常。
3. DB 返回 null，只调用 `removeFromLatest(postId)`。
4. DB 返回 `PUBLISHED(2)`，不调用 outbox、pool、latest 删除。
5. DB 返回非 `PUBLISHED(2)`，使用 `post.userId` 删除 outbox/pool，并删除 latest。
6. 单项删除抛异常时，其余删除仍会执行，异常只记录 warn。
7. `contentRepository.findPostBypassCache(postId)` 抛异常时，消息处理方法向外抛出异常，让可靠 MQ 重试。

- [ ] **Step 2: 创建 consumer**

实现规则：

1. 一个类可以有两个 listener 方法。
2. updated listener 只监听 `FeedFanoutConfig.Q_FEED_INDEX_CLEANUP_UPDATED`。
3. deleted listener 只监听 `FeedFanoutConfig.Q_FEED_INDEX_CLEANUP_DELETED`。
4. 两个 listener 都使用 `reliableMqListenerContainerFactory` 和 `@ReliableMqConsume`。
5. `consumerName` 必须不同，避免可靠消费记录冲突。
6. `eventId` 使用 `#event.eventId`，payload 使用 `#event`。

- [ ] **Step 3: 实现清理决策**

决策规则固定为：

1. 先校验 event，再回表。
2. 回表方法固定为 `contentRepository.findPostBypassCache(postId)`。
3. DB null：只删 latest，立即结束。
4. DB 非 null：authorId 固定取 `post.getUserId()`。
5. DB status 等于 `2`：不删 outbox/pool/latest。
6. DB status 不等于 `2`，包含 null：删 outbox、pool、latest。
7. outbox、pool、latest 三个删除互相隔离；单项失败不阻断其余项。
8. 回表失败不捕获为成功，不 ack。

- [ ] **Step 4: 更新架构契约**

把两个 listener 方法加入 listener inventory。

把 `FeedIndexCleanupConsumer.java` 加入 side-effecting listener file set，确保可靠消费注解继续受契约测试约束。

- [ ] **Step 5: 验证**

运行：

- `mvn -pl nexus-trigger test -Dtest="*FeedIndexCleanup*"`
- `mvn -pl nexus-app test -Dtest="ReliableMqArchitectureContractTest"`

提交点：`feat: add feed index cleanup consumer`

## Task 6: Counter Side-Effect Logging Only

**Files:**
- Modify: `nexus-domain/src/main/java/cn/nexus/domain/social/service/KnowpostCounterSideEffectListener.java`

- [ ] **Step 1: 升级异常日志**

只调整 best-effort 计数副作用异常路径：

1. `incrementReceivedBestEffort` 的异常日志从 debug 升为 warn。
2. `applyFeedSideEffectsBestEffort` 的异常日志从 debug 升为 warn。

- [ ] **Step 2: 保持语义不变**

禁止改动：

1. 计数 delta。
2. 副作用触发条件。
3. 缓存失效行为。
4. retry、补偿队列、新 MQ。
5. 正常跳过路径的日志级别。

- [ ] **Step 3: 验证**

运行 `mvn -pl nexus-domain test -Dtest="*KnowpostCounter*"`；若没有匹配测试类，运行 `mvn -pl nexus-domain test`。

提交点：`fix: warn on counter side effect failures`

## Task 7: Remove Dead fanout Entry

**Files:**
- Modify: `nexus-domain/src/main/java/cn/nexus/domain/social/service/IFeedDistributionService.java`
- Modify: `nexus-domain/src/main/java/cn/nexus/domain/social/service/FeedDistributionService.java`

- [ ] **Step 1: 零调用门禁**

删除前运行并确认只有接口声明和实现本身命中：

- `rg -n "fanout\\(" nexus-domain nexus-trigger nexus-infrastructure nexus-app`

若存在业务调用方，本任务停止，先修正计划。

- [ ] **Step 2: 删除 dead method**

删除 `fanout(PostPublishedEvent)` 接口声明和实现。

保留：

1. `fanoutSlice(...)`。
2. `FeedFanoutDispatcherConsumer` 发布切片任务链路。
3. `FeedFanoutTaskConsumer` 调用 `fanoutSlice` 的行为。

- [ ] **Step 3: 验证**

运行 `mvn -pl nexus-domain test`。

提交点：`chore: remove unused feed fanout entry`

## Task 8: Remove Legacy ContentDispatchPort

**Files:**
- Delete: `nexus-domain/src/main/java/cn/nexus/domain/social/adapter/port/IContentDispatchPort.java`
- Delete: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/ContentDispatchPort.java`
- Modify: `nexus-app/src/test/java/cn/nexus/contract/mq/ReliableMqArchitectureContractTest.java`

- [ ] **Step 1: 零调用和 bean 门禁**

删除前运行并确认：

1. `rg -n "IContentDispatchPort|ContentDispatchPort" nexus-domain nexus-infrastructure nexus-trigger nexus-app`
2. 命中只剩接口、实现、架构契约 allowlist。
3. 没有构造器注入、字段注入、`@Autowired`、`ApplicationContext.getBean` 依赖该 port。

若存在依赖，本任务停止，先修正计划。

- [ ] **Step 2: 删除旧 port**

删除接口和实现。

不得新增替代 port。内容事件继续由 `ContentEventOutboxPort` 负责。

- [ ] **Step 3: 更新架构契约**

从 raw publish allowlist 删除 `ContentDispatchPort` 三条记录。

不得删除 `ContentEventOutboxPort` 的 allowlist。

- [ ] **Step 4: 验证**

运行：

- `mvn -pl nexus-app test -Dtest="ReliableMqArchitectureContractTest"`
- `mvn compile`

提交点：`chore: remove legacy content dispatch port`

## Task 9: Cross-Cutting Race and Contract Tests

**Files:**
- Test additions in the modules touched by Tasks 1-5.

- [ ] **Step 1: Redis rebuild acceptance tests**

必须覆盖：

1. inbox 非空重建保留 bounded latest-end old members。
2. inbox 空重建只保留 `__NOMORE__`。
3. inbox 非空满载时真实帖子优先，`__NOMORE__` 不强制占用容量。
4. outbox 非空重建保留 bounded latest-end old members。
5. outbox 空重建不合并旧成员。
6. inbox/outbox 正式 key TTL 存在。
7. inbox/outbox 成员数不超过 maxSize。
8. tmpKey 不残留。
9. Lua 不依赖全量旧集合。

- [ ] **Step 2: MQ cleanup acceptance tests**

必须覆盖：

1. updated/deleted 分队列消费。
2. DB 当前 `PUBLISHED(2)` 不清聚合索引。
3. DB 当前非 `PUBLISHED(2)` 清 outbox/pool/latest。
4. DB null 只清 latest。
5. 单项删除失败不阻断其他删除。
6. 回表失败触发消息重试路径。

- [ ] **Step 3: Existing behavior guard**

必须确认：

1. `FeedFanoutTaskConsumer` 仍只调用 `fanoutSlice`。
2. 推荐 item delete 队列仍消费 `post.deleted`。
3. content/card 缓存失效消费者仍存在。
4. follow/block 读侧过滤未被改动。

- [ ] **Step 4: 验证**

运行：

- `mvn -pl nexus-infrastructure test`
- `mvn -pl nexus-trigger test`
- `mvn -pl nexus-domain test`
- `mvn -pl nexus-app test -Dtest="ReliableMqArchitectureContractTest"`

提交点：`test: cover feed cache race fixes`

## Task 10: Final Verification

**Files:**
- No production edits unless verification exposes a failure tied to this change.

- [ ] **Step 1: 全量编译**

在 `project/nexus` 目录运行 `mvn compile`。

预期：BUILD SUCCESS。

- [ ] **Step 2: 定向测试**

运行：

1. `mvn -pl nexus-infrastructure test`
2. `mvn -pl nexus-trigger test`
3. `mvn -pl nexus-domain test`
4. `mvn -pl nexus-app test -Dtest="ReliableMqArchitectureContractTest"`

- [ ] **Step 3: 偏差扫描**

运行并确认无违背本计划的命中：

1. `rg -n "ZRANGE[^\\n]*0[^\\n]*-1|ZREVRANGE[^\\n]*0[^\\n]*-1|reverseRange\\([^\\n]*,\\s*0\\s*,\\s*-1|range\\([^\\n]*,\\s*0\\s*,\\s*-1" nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/FeedTimelineRepository.java nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/FeedOutboxRepository.java`
2. `rg -n "operatorId|getOperatorId" nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FeedIndexCleanupConsumer.java`
3. `rg -n "stringRedisTemplate\\.delete\\(outboxKey\\)|\\.delete\\(outboxKey\\)" nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/FeedOutboxRepository.java`
4. `rg -n "removeFromInbox|recommend.*session|session.*recommend" nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FeedIndexCleanupConsumer.java`
5. `rg -n "Redis Stream|CDC|全局版本|分布式事务|new table|新增表" nexus-*`
6. `rg -n "feed\\.inbox\\.rebuildMergeWindowSize|feed\\.inbox\\.rebuild-merge-window-size" nexus-*`

允许命中仅限注释或测试中明确表达“禁止”的断言。

- [ ] **Step 4: Git 检查**

运行 `git status --short`。

确认只包含本计划相关文件；不得整理或回滚用户的无关改动。

提交点：最终验证不强制提交；只有修复测试或契约时才提交。

## Execution Order

固定顺序：

1. Task 1
2. Task 2
3. Task 3
4. Task 4
5. Task 5
6. Task 6
7. Task 7
8. Task 8
9. Task 9
10. Task 10

原因：

1. Redis 重建先落地，避免 MQ 清理和重建语义交叉调试。
2. latest delete API 先于 cleanup consumer。
3. MQ topology 先于 consumer。
4. 契约测试更新与 listener/port 删除同任务完成，避免制造中间假失败。

## Drift Review Checklist

每个任务完成后检查：

1. 是否新增了本计划未列出的基础设施、表、exchange、queue、后台任务。
2. 是否出现全量读取 inbox/outbox 旧集合。
3. 是否把空 rebuild 当作普通非空 rebuild 合并旧成员。
4. 是否在 rename 后补 trim/expire。
5. 是否让 `post.updated` 在 DB 仍 published 时删除聚合索引。
6. 是否使用 `operatorId` 清 outbox/pool。
7. 是否主动清粉丝 inbox 或推荐 session。
8. 是否改变现有 fanoutSlice、推荐 item delete、content cache eviction 链路。
9. 是否把配置键写成计划外名称。
10. 是否修改无关模块或无关脏文件。

## Handoff

Plan complete and saved to `docs/superpowers/plans/2026-05-03-nexus-feed-cache-race-fix-plan.md`.

Two execution options:

1. **Subagent-Driven (recommended)** - dispatch a fresh subagent per task, review between tasks, fast iteration.
2. **Inline Execution** - execute tasks in this session using `superpowers:executing-plans`, with checkpoints.

Which approach?
