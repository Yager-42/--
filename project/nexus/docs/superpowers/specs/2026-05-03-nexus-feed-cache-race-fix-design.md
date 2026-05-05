# Nexus Feed 三级缓存竞态修复设计

2026-05-03 | authored by rr & codex | status: design

## 目标

修复 Nexus Feed 三级缓存里会导致索引丢失或长期脏索引的竞态，同时保持当前读写吞吐能力。

本设计只收敛现有 Redis ZSET 读模型、Feed MQ 消费者和内容事件索引清理，不引入新基础设施，不引入跨系统补偿平台，不重写 Feed 架构。

## 问题边界

当前竞态集中在三类链路：

1. `replaceInbox` / `replaceOutbox` 使用 `tmpKey + rename`，并发 `addToInbox` / `addToOutbox` 可能被旧快照覆盖。
2. `post.updated` / `post.deleted` 对 Feed 聚合索引没有统一清理，导致 outbox、bigV pool、global latest 残留不可见内容。
3. outbox 重建缺少互斥锁，快速重复重建时最后一次 rename 可能覆盖前一次结果。

本设计不把推荐 session 陈旧、取消关注后 inbox 残留、block/follow 关系并发纳入本次修复。它们继续由读侧回表、关系过滤、TTL 自愈承担。

## 设计原则

1. **不改变 Feed 架构**：FOLLOW、RECOMMEND、POPULAR、NEIGHBORS、PROFILE 的读入口和业务语义不变。
2. **不全量扫描大 ZSET**：任何 Redis Lua 脚本都不得对 inbox/outbox 执行 `ZRANGE key 0 -1` 或等价全量读取。
3. **不把重建变成永久 union**：重建仍然是“替换为新快照”，只能额外保留一个有上限的最新端并发写入窗口，不能把旧集合全部合并回来。
4. **不主动清百万粉丝 inbox**：内容不可见时不扫描粉丝 inbox；inbox 继续读时懒清理。
5. **事件清理以 DB 当前状态为准**：`operatorId` 不是作者 ID，不能用它清 outbox/pool；清理消费者必须绕过缓存回表确认 author 和 status。
6. **MQ 队列不混用事件类型**：不同 Java payload 类型必须使用不同队列，或使用单一通用消息处理器显式按 routing key 分派。本设计选择不同队列。
7. **失败隔离**：outbox、bigV pool、global latest 的单项清理互不阻塞；一个失败不能阻止另外两个执行。

## 方案总览

本次修复采用三组小改动：

1. inbox/outbox 重建从 `tmpKey + rename` 改为“原子替换 + 有界保留最新端旧成员”。
2. 为 global latest 补 `removeFromLatest(postId)`。
3. 新增 Feed 索引清理消费者，分别消费 `post.updated` 和 `post.deleted`，按 DB 当前状态决定是否清聚合索引。

不新增新的业务表，不新增 Redis Stream，不新增跨服务补偿任务。

## C1: Inbox 原子有界重建

文件：`FeedTimelineRepository.java`

### 规则

`replaceInbox(userId, entries)` 必须保持这些语义：

1. 外部接口不变。
2. 仍保留现有 `tryAcquireRebuildLock(userId)`。
3. 重建结果必须包含本次 DB 快照生成的 entries。
4. 重建结果可以额外保留 real inbox 中最新端的一段旧成员，用于吸收重建期间并发发布 fanout 写入。
5. 额外保留的旧成员数量必须有硬上限：新增配置 `feed.rebuild.mergeWindowSize`，默认 256；实际窗口为 `min(feed.rebuild.mergeWindowSize, feed.inbox.maxSize)`；不得全量保留旧集合。
6. 空 entries 是“权威空快照”：只写入 `__NOMORE__` 哨兵，不合并 real inbox 旧成员，避免把重建要清掉的旧索引重新带回。
7. TTL 和 maxSize 裁剪必须和替换在同一个原子脚本中完成，避免短暂暴露无 TTL 或超大 ZSET。
8. 脚本结束后不得留下 tmpKey。

### 有界保留窗口

实现者不得使用“合并 realKey 全部成员”的方案。

允许的保留策略只有一种：从 real inbox 的最新端读取一个固定上限的成员，合并进 tmp，再按 score/postId 语义裁剪到 maxSize。这个窗口只用于降低并发发布 fanout 被 rename 覆盖的概率，不承担历史修复或补偿职责。

这样做的取舍是明确的：

- 保留近期并发写入能力，避免高频发布在重建期间丢失。
- 不保留长尾旧索引，避免 deleted/unfollow/过期内容被重建永久带回。
- 单次 Lua 工作量受 max window 限制，避免 Redis 被大 key 全量脚本阻塞。

关注后的历史补偿写入 `FeedFollowCompensationService.addToInbox` 是 best-effort，不作为本次原子重建的强一致目标。原因是补偿写入的帖子 score 可能早于 inbox 最新端窗口；为了保持方案简单且保护 Redis，不为它引入全量合并或额外补偿队列。取消关注和新关注后的可见性继续由关系 DB、读侧过滤、后续刷新/重建保证。

空快照期间发生的并发 `addToInbox` 也按 best-effort 处理。本设计选择“空重建能真正清空旧索引”，优先于保护极少数空快照窗口内的并发写入。后续正常发布事件、刷新重建或读侧过滤会恢复可见性。

### 不允许

- 不允许 `ZRANGE real 0 -1`。
- 不允许把 real 的旧成员全部写回 tmp。
- 不允许在 Java 侧 rename 后再 trim。
- 不允许为了补偿极端竞态引入全量粉丝扫描或重建后异步大任务。

## C2: Outbox 原子有界重建与重建锁

文件：`FeedOutboxRepository.java`

### 规则

`replaceOutbox(authorId, entries)` 采用与 C1 相同的“原子替换 + 有界保留最新端旧成员”规则。

额外要求：

1. 新增 `feed:outbox:rebuild:lock:{authorId}` 互斥锁。
2. 锁使用 `SETNX + TTL`，默认 TTL 30 秒。
3. 获取锁失败时直接跳过本次重建，不等待、不自旋、不阻塞发布路径。
4. entries 为空时仍执行原子替换为空 outbox，而不是直接 `delete(outboxKey)` 后返回；空 outbox 不合并旧成员，避免把已不可见或已过期的旧内容重新带回。
5. TTL 和 maxSize 裁剪必须在同一个原子替换脚本中完成。
6. outbox 的保留窗口使用新增配置 `feed.outbox.rebuildMergeWindowSize`，默认 256；实际窗口为 `min(feed.outbox.rebuildMergeWindowSize, feed.outbox.maxSize)`。

### 能力边界

C2 只解决 outbox 重建覆盖并发 add 的竞态，不解决大 V 类别切换的全部语义问题。

NORMAL/BIGV 切换期间，历史内容是否已经存在于 inbox、是否从 outbox/pool 拉取，仍由现有读侧 merge、去重、关系过滤承担。本次不做历史内容迁移。

## C3: Global Latest 删除能力

文件：

- `IFeedGlobalLatestRepository.java`
- `FeedGlobalLatestRepository.java`

新增 `removeFromLatest(Long postId)`。

规则：

1. `postId == null` 时直接返回。
2. 只删除 `feed:global:latest` 中对应 member。
3. 不扫描、不回表、不影响推荐系统 item。

## C4: Feed 索引清理消费者

文件：新增 `FeedIndexCleanupConsumer.java`

### 队列规则

必须使用两个独立队列：

1. `feed.index.cleanup.updated.queue` 只绑定 `post.updated`。
2. `feed.index.cleanup.deleted.queue` 只绑定 `post.deleted`。

两个队列可以由同一个 consumer 类处理，但不能让两个不同 payload 类型竞争消费同一个队列。

### 清理判断

consumer 收到事件后必须执行以下流程：

1. 校验 `eventId` 与 `postId`，缺失则按永久失败处理。
2. 使用 `contentRepository.findPostBypassCache(postId)` 回表读取当前帖子。
3. 如果 DB 返回 `null`，只执行 `removeFromLatest(postId)`，然后结束。
4. 如果 DB 返回帖子，取 `post.userId` 作为 authorId；不得使用 `operatorId` 作为 authorId。
5. 仅当帖子当前状态不是 `PUBLISHED(2)` 时，清理聚合索引。
6. 如果帖子当前仍是 `PUBLISHED(2)`，说明这是普通内容更新或仍可见状态变化，只允许依赖现有 content/card 缓存失效，不得从 outbox、pool、latest 删除。

### 清理范围

当帖子当前不可见时，清理范围仅为：

1. `feedOutboxRepository.removeFromOutbox(authorId, postId)`
2. `feedBigVPoolRepository.removeFromPool(authorId, postId)`
3. `feedGlobalLatestRepository.removeFromLatest(postId)`

不主动扫描或清理：

- 粉丝 inbox
- 推荐 session cache
- Gorse item
- card/stat cache

原因：

- 粉丝 inbox 可能百万级，主动清理会破坏高并发能力。
- 推荐 session 是快照型缓存，继续读时回表过滤和 TTL 自愈。
- Gorse 已有独立 delete 消费者，本设计不接管推荐系统。
- card/stat 已由内容缓存失效和计数副作用链路处理。

### 失败处理

三个索引删除操作必须分别捕获异常并记录 warn。一个索引删除失败不得阻止其它索引删除。

如果回表失败，消息应失败重试，不应直接 ack；否则会永久跳过清理。

## C5: MQ 拓扑

文件：`FeedFanoutConfig.java`

新增：

1. `RK_POST_UPDATED = "post.updated"`
2. `RK_POST_DELETED = "post.deleted"`
3. `Q_FEED_INDEX_CLEANUP_UPDATED = "feed.index.cleanup.updated.queue"`
4. `Q_FEED_INDEX_CLEANUP_DELETED = "feed.index.cleanup.deleted.queue"`
5. 对应两个 DLQ routing key 和 DLQ。

绑定规则：

- `Q_FEED_INDEX_CLEANUP_UPDATED` 绑定 `social.feed / post.updated`
- `Q_FEED_INDEX_CLEANUP_DELETED` 绑定 `social.feed / post.deleted`

`post.deleted` 被推荐 item delete 队列和 index cleanup delete 队列同时消费是预期行为。

## C6: 计数副作用日志

文件：`KnowpostCounterSideEffectListener.java`

本项只允许调整异常日志，不允许改变计数、副作用、缓存失效语义。

规则：

1. 异常路径可以 warn。
2. 正常跳过、无副作用、非 knowpost 事件不得 warn。
3. 不新增重试、不新增补偿队列。

## C7: 删除未使用 fanout 入口

文件：

- `IFeedDistributionService.java`
- `FeedDistributionService.java`

删除 `fanout(PostPublishedEvent)`，保留 `fanoutSlice(...)`。

规则：

1. 删除前必须确认零调用方。
2. 发布事件的唯一分发入口继续是 `FeedFanoutDispatcherConsumer`。
3. 不改变 `FeedFanoutTaskConsumer` 和 `fanoutSlice` 语义。

## C8: 删除旧 ContentDispatchPort

文件：

- `ContentDispatchPort.java`
- `IContentDispatchPort.java`，仅当确认零调用方且无 Spring bean 依赖时删除。

规则：

1. 删除前必须确认接口和实现均零调用方。
2. 不替换为新的 dispatch port。
3. 内容事件继续由 `ContentEventOutboxPort` 负责。

## 不做的事

1. 不主动清理推荐 session。
2. 不主动扫描粉丝 inbox。
3. 不重建所有关注者 timeline。
4. 不给 category hash 加 TTL。
5. 不改变 block/follow 读侧过滤。
6. 不修改 Count 系统。
7. 不引入 Redis Stream、CDC 新链路、分布式事务或全局版本服务。
8. 不用 `operatorId` 推断作者。
9. 不把所有 `post.updated` 都当作不可见删除。

## 实现偏差防护规则

实现时如果出现以下变化，视为偏离本设计：

1. Lua 或 Java 对 inbox/outbox 做全量旧集合合并。
2. 内容普通编辑导致帖子从 outbox、pool、latest 删除。
3. cleanup consumer 使用 `operatorId` 清 outbox/pool。
4. 一个 RabbitMQ queue 被两个不同 payload 类型的 listener 竞争消费。
5. 为清理 inbox 引入粉丝扫描。
6. 为推荐 session 引入主动批量删除。
7. outbox 空重建直接 delete 正式 key。
8. rename 后再异步 trim/expire。
9. 为解决本问题新增表、新外部组件或新长期后台补偿任务。

## 验收用例

最小验收必须覆盖：

1. inbox 重建期间并发 `addToInbox`，最终 inbox 包含重建快照和有界窗口内的并发新增，且不超过 maxSize。
2. outbox 重建期间并发 `addToOutbox`，最终 outbox 不丢最新端并发新增，且不超过 maxSize。
3. outbox 两个并发 `replaceOutbox`，只有一个获得重建锁，另一个跳过。
4. inbox 空重建只保留 `__NOMORE__`，不合并 real inbox 旧成员。
5. outbox 空重建不直接 delete 正式 key，且不合并旧成员。
6. Lua 重建后正式 key 有 TTL，成员数不超过 maxSize，tmpKey 不存在。
7. `post.updated` 后 DB 当前状态仍为 `PUBLISHED(2)`，不删除 outbox、pool、latest。
8. `post.updated` 后 DB 当前状态为非 `PUBLISHED(2)`，删除 outbox、pool、latest。
9. `post.deleted` 后 DB 返回 `null`，只删除 latest，不使用 operatorId 删除 outbox/pool。
10. `post.deleted` 后 DB 返回非 published 帖子，使用 `post.userId` 删除 outbox/pool，并删除 latest。
11. 两个 index cleanup 队列分别消费 updated/deleted，不发生 payload 类型竞争。
12. content/card 缓存失效原链路仍照常执行。
13. `FeedFanoutTaskConsumer` 仍只调用 `fanoutSlice`，发布 fanout 行为不变。

## 回滚与兼容

1. Repository 外部方法签名保持不变。
2. 新增 MQ 队列独立于现有 fanout 和 recommend delete 队列。
3. 如果 cleanup consumer 出现问题，可以停用新增队列绑定，Feed 主读写链路仍保持现有行为。
4. 如果 Lua 重建出现问题，可以回退到旧 `tmpKey + rename` 实现，但会重新暴露重建覆盖增量的竞态。
