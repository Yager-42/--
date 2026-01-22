# 分发领域（Feed/时间线）技术文档（以代码为准）

面向对象：新入职同学（不要求你先懂 DDD；你只要能顺着“从哪里进、往哪里走、数据存在哪”把链路跑通即可）。

一句话版本：**发帖成功后，系统把 `postId` 变成一条“索引卡片”写进很多人的 Redis 时间线；读时间线主要读 Redis，缺的再去 MySQL 补；大 V 不做全量写扩散，改成读侧拉 Outbox/聚合池；离线用户首次打开首页会触发“重建 inbox”。**

> 本文只写“当前项目里真的存在的实现”。每个结论都给出对应代码位置（文件 + 类/方法）。

---

## 0. 项目里这块东西放在哪？

你会看到 5 个“层”，不要被名词吓到：**trigger 负责接请求/收消息，domain 负责业务规则，infrastructure 负责 Redis/MySQL/MQ，types 放通用事件，api 放接口契约。**

相关代码：
- 分层说明：`.codex/DDD-ARCHITECTURE-SPECIFICATION.md`
- Maven 多模块根：`project/nexus/pom.xml`
- 入口（HTTP/MQ）：`project/nexus/nexus-trigger`
- 领域服务（业务编排）：`project/nexus/nexus-domain`
- 技术实现（Redis/MyBatis/RabbitMQ）：`project/nexus/nexus-infrastructure`
- 事件/枚举：`project/nexus/nexus-types`
- 接口契约/DTO：`project/nexus/nexus-api`

---

## 1. 分发领域到底解决什么问题？

把它想成“发朋友圈 + 看朋友圈”：
- **写入（发帖）**：你发了一条内容，系统会把“这条内容的编号 `postId`”复制到你自己和你粉丝的“收件箱时间线”里。
- **读取（刷首页）**：你刷首页，只需要从自己的“收件箱时间线”按时间倒着拿 `postId`，再去数据库把内容补全出来。

关键目标（从代码能看出来的）：
- **快**：读首页尽量不做复杂 SQL，优先读 Redis（`FeedTimelineRepository`）。
- **不爆炸**：大 V 粉丝太多时不全量写扩散，改成“读侧拉”（`FeedFanoutDispatcherConsumer#isBigV` + `FeedService#listBigVCandidates`）。
- **不串号/不信客户端**：userId 从网关 Header 注入（`UserContextInterceptor`），Controller 不信 DTO 里的 userId（`FeedController`）。

相关代码：
- 首页时间线入口：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/FeedController.java`
- 核心业务：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/FeedService.java`

---

## 2. 核心数据结构（最重要的 6 个概念）

下面每个概念，你都能在代码里找到它“长什么样、存哪里、谁写谁读”。

### 2.1 InboxTimeline（收件箱时间线，Redis ZSET）

你可以把它当成“每个用户一个收件箱，里面是一堆 postId，按发布时间倒序”。

- 存储：Redis ZSET
- Key：`feed:inbox:{userId}`
- member：`postId`（字符串）
- score：`publishTimeMs`（毫秒时间戳）

谁写：
- 写扩散（fanout）：`FeedDistributionService#fanoutSlice` → `IFeedTimelineRepository#addToInbox`
- 发布者自己保底写入：`FeedFanoutDispatcherConsumer#dispatch` → `IFeedTimelineRepository#addToInbox`
- 关注补偿回填：`FeedFollowCompensationService#onFollow` → `IFeedTimelineRepository#addToInbox`
- 离线重建替换写：`FeedInboxRebuildService#doRebuild` → `IFeedTimelineRepository#replaceInbox`

谁读：
- 首页 timeline：`FeedService#timeline` → `IFeedTimelineRepository#pageInboxEntries`

相关代码：
- 接口：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/adapter/repository/IFeedTimelineRepository.java`
- Redis 实现：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/FeedTimelineRepository.java`
- “无更多数据”哨兵：`FeedTimelineRepository` 里的 `INBOX_NO_MORE_MEMBER = "__NOMORE__"`

### 2.2 Outbox（作者发布流索引，Redis ZSET）

你可以把它当成“大 V 的收件箱替代品”：粉丝不一定都被写入 inbox，但作者一定把 `postId` 写进自己的 outbox，粉丝需要时来这里“拉”。

- Key：`feed:outbox:{authorId}`
- member：`postId`
- score：`publishTimeMs`

谁写：
- 发布事件消费（永远写）：`FeedFanoutDispatcherConsumer#dispatch` → `IFeedOutboxRepository#addToOutbox`

谁读：
- 首页 timeline 补大 V：`FeedService#listBigVCandidates` → `IFeedOutboxRepository#pageOutbox`

相关代码：
- 接口：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/adapter/repository/IFeedOutboxRepository.java`
- Redis 实现：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/FeedOutboxRepository.java`

### 2.3 BigV（大 V：粉丝太多不全量写扩散）

判定规则很简单：**粉丝数 >= 阈值**。

大 V 发布时做什么（写侧）：
- 仍然写 Outbox（用于拉）
- 仍然写“作者自己 inbox”（体验保底）
- 不投递全量 fanout task（避免写爆 Redis/拖垮消费者）
- 可选：把内容写入“大 V 聚合池”
- 可选：推送给“铁粉集合”（数量上限）

相关代码：
- 大 V 判定与跳过全量 fanout：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FeedFanoutDispatcherConsumer.java`
- 阈值配置：`feed.bigv.followerThreshold`（见 `application-dev.yml`）

### 2.4 BigV Pool（大 V 聚合池，Redis ZSET，可选开关）

你可以把它当成“很多大 V 的 outbox 合并成少数几个桶”，避免首页为了看大 V 内容去读很多个 Redis key。

- Key：`feed:bigv:pool:{bucket}`
- 开关：`feed.bigv.pool.enabled`（默认 false）
- 注意：读出来以后还要按“我关注了哪些作者”过滤（否则会看到没关注的）

相关代码：
- 接口：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/adapter/repository/IFeedBigVPoolRepository.java`
- Redis 实现：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/FeedBigVPoolRepository.java`
- 读侧是否用聚合池：`FeedService#shouldUseBigVPool` + `FeedService#listPoolCandidates`

### 2.5 CoreFans（铁粉集合，Redis SET）

你可以把它当成“大 V 的小范围推送名单”：大 V 发布时最多推给 N 个铁粉（且只推给在线的）。

- Key：`feed:corefans:{authorId}`
- 生成方式：旁路消费 `interaction.notify`（点赞/评论）事件，近似“高频互动粉丝”

相关代码：
- 生成消费者：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FeedCoreFansConsumer.java`
- MQ 绑定：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/config/FeedCoreFansMqConfig.java`
- Redis 实现：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/FeedCoreFansRepository.java`

### 2.6 Negative Feedback（负反馈，Redis SET/HASH）

你可以把它当成“我不想再看到某条内容/某类内容”的过滤条件。

当前实现做两类过滤：
- 按 postId 过滤：`feed:neg:{userId}`（SET）
- 按 postType 过滤：`feed:neg:postType:{userId}`（SET）
- 记录“点选了哪个类型”（用于撤销）：`feed:neg:postTypeByPost:{userId}`（HASH：postId->postType）

相关代码：
- 接口：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/adapter/repository/IFeedNegativeFeedbackRepository.java`
- Redis 实现：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/FeedNegativeFeedbackRepository.java`
- 写入与撤销：`FeedService#negativeFeedback` / `FeedService#cancelNegativeFeedback`

---

## 3. HTTP 接口（用户真正能看到的行为）

> 你只需要记住：**Controller 里的 userId 永远来自 `UserContext`，不信 requestDTO 里的 userId。**

### 3.1 首页时间线：GET `/api/v1/feed/timeline`

调用链：
`FeedController#timeline` → `IFeedService#timeline` → `FeedService#timeline`

DTO（契约层）：
- `FeedTimelineRequestDTO`：`cursor` / `limit` / `feedType`（里面虽然有 userId 字段，但会被忽略）
- `FeedTimelineResponseDTO`：`items` + `nextCursor`

相关代码：
- API 契约：`project/nexus/nexus-api/src/main/java/cn/nexus/api/social/IFeedApi.java`
- DTO：`project/nexus/nexus-api/src/main/java/cn/nexus/api/social/feed/dto/FeedTimelineRequestDTO.java`
- Controller：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/FeedController.java`
- userId 注入：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/support/UserContextInterceptor.java`

### 3.2 个人页时间线：GET `/api/v1/feed/profile/{targetId}`

特点：个人页不走 inbox，直接走 MySQL 分页（因为它只看一个人的内容，不需要分发）。

调用链：
`FeedController#profile` → `FeedService#profile` → `IContentRepository#listUserPosts`

相关代码：
- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/FeedService.java`
- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/adapter/repository/IContentRepository.java`
- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/ContentRepository.java`

### 3.3 负反馈：POST `/api/v1/feed/feedback/negative`

调用链：
`FeedController#submitNegativeFeedback` → `FeedService#negativeFeedback` → `IFeedNegativeFeedbackRepository`

相关代码：
- DTO：`project/nexus/nexus-api/src/main/java/cn/nexus/api/social/feed/dto/NegativeFeedbackRequestDTO.java`
- 写入逻辑：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/FeedService.java`

### 3.4 撤销负反馈：DELETE `/api/v1/feed/feedback/negative/{targetId}`

调用链：
`FeedController#cancelNegativeFeedback` → `FeedService#cancelNegativeFeedback`

---

## 4. 写链路：发帖成功后如何触发分发？

写链路最怕“事务没提交，消费者先读到半条数据”。这个项目用 **after-commit** 规避。

### 4.1 事务提交后再发事件（after-commit）

发布成功后，`ContentService.publish(...)` 会注册一个事务同步回调：**只有提交成功后才调用分发端口**。

相关代码：
- 发布主流程：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/ContentService.java`
- after-commit 分发：`ContentService#dispatchAfterCommit`（同文件内）
- 分发端口：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/adapter/port/IContentDispatchPort.java`

### 4.2 分发端口实现：发到 RabbitMQ

领域层只调用端口；具体怎么发 MQ 在 infrastructure 实现。

相关代码：
- MQ 发布：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/ContentDispatchPort.java`
- 事件类型：`project/nexus/nexus-types/src/main/java/cn/nexus/types/event/PostPublishedEvent.java`
- 交换机/路由键（字符串常量）：`ContentDispatchPort` 内 `EXCHANGE` / `ROUTING_KEY`

---

## 5. MQ 拓扑：PostPublishedEvent → fanout dispatcher → fanout task

### 5.1 FeedFanoutConfig（交换机、队列、死信、JSON 序列化）

这块决定了“消息从哪来、到哪去、失败去哪”：
- Exchange：`social.feed`
- Queue（发布事件）：`feed.post.published.queue`
- Queue（切片任务）：`feed.fanout.task.queue`
- DLX/DLQ：配置了死信交换机和死信队列
- 消息格式：统一 JSON（Jackson2JsonMessageConverter）

相关代码：
- `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/config/FeedFanoutConfig.java`

### 5.2 dispatcher：收到 PostPublishedEvent 后做什么？

你可以把 dispatcher 当成“分发调度员”，它只做两件事：
1) **做必须的、很快的写入**（Outbox + 作者自己 inbox）
2) **把大任务切片**，投递给 worker 并行跑

关键逻辑（按代码顺序）：
- 永远写 Outbox：`feedOutboxRepository.addToOutbox(...)`
- 作者自己 inbox 保底：`feedTimelineRepository.addToInbox(authorId, ...)`
- 判断大 V：粉丝数太多就跳过全量 fanout
- 非大 V：按 `batchSize` 切片，投递 `FeedFanoutTask(offset, limit)`

相关代码：
- 消费入口：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FeedFanoutDispatcherConsumer.java`
- 切片任务结构：`project/nexus/nexus-types/src/main/java/cn/nexus/types/event/FeedFanoutTask.java`

### 5.3 worker：消费一片 fanout task

worker 很“傻”，只负责执行一片：
- 拉取这一片粉丝：`IRelationRepository#listFollowerIds(authorId, offset, limit)`
- 过滤在线用户：`IFeedTimelineRepository#filterOnlineUsers`（以 inbox key 是否存在定义“在线”）
- 给在线粉丝写 inbox：`IFeedTimelineRepository#addToInbox`

相关代码：
- MQ worker：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FeedFanoutTaskConsumer.java`
- 领域执行：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/FeedDistributionService.java`
- 粉丝分页：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/adapter/repository/IRelationRepository.java`
- MyBatis SQL：`project/nexus/nexus-infrastructure/src/main/resources/mapper/social/FollowerMapper.xml`

---

## 6. 读链路：timeline 是怎么拼出来的？

### 6.1 timeline（首页）整体流程

你可以按这 6 步理解 `FeedService#timeline`：
1) 规范化 limit（默认 20，最大 100）
2) 如果是首页（cursor 为空）：可能触发 inbox 重建（只在 inbox key miss 时）
3) 从 inbox 拉一批候选 postId（放大 3 倍，用于后续过滤）
4) 再补一批“大 V 候选”（来自 outbox 或聚合池）
5) 合并、按时间倒序排序、去重
6) 回表查内容、应用负反馈过滤、返回 DTO

相关代码：
- 首页 timeline：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/FeedService.java`
- inbox 读取：`FeedTimelineRepository#pageInboxEntries`
- 回表热点优化：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/ContentRepository.java`

### 6.2 cursor（游标）到底是什么？

当前首页 timeline 的 cursor 协议很“省事”：
- cursor=上一页最后一个 `postId`（字符串）
- 下一页时，服务端会用这个 postId 去 MySQL 查 `createTime`，得到 `(cursorTimeMs, cursorPostId)`，然后按 Max_ID 语义继续翻页

相关代码：
- cursor 解析：`FeedService#resolveMaxIdCursor`（内部会调用 `IContentRepository#findPost`）
- Max_ID 比较：`FeedTimelineRepository#passCursor`（同逻辑也出现在 Outbox/Pool）

### 6.3 大 V 内容如何“混进”首页？

因为大 V 可能不写入你的 inbox，所以读侧要补：
- 先从你关注列表里挑出“大 V 作者”（需要查粉丝数）
- 对每个大 V 作者，从 `feed:outbox:{authorId}` 拉一些索引条目
- 合并进候选集合，再统一排序去重

相关代码：
- 关注列表：`IRelationAdjacencyCachePort#listFollowing`（Redis 邻接缓存）
- 挑大 V：`FeedService#pickBigVAuthors`（内部会调用 `IRelationRepository#countFollowerIds`）
- 拉 outbox：`IFeedOutboxRepository#pageOutbox`
- 可选聚合池：`FeedService#listPoolCandidates`

### 6.4 “读时修复”：清理 Redis 里的坏索引

如果 Redis 里有个 postId，但 MySQL 已经查不到内容（删除/未发布/脏数据），系统会做 best-effort 清理：
- 从当前用户 inbox 删除这条索引
- 尝试从作者 outbox / bigV pool 删除

相关代码：
- `FeedService#cleanupMissingIndexes`

---

## 7. 离线重建（inbox miss 时怎么补一份 inbox？）

离线的定义很“工程化”：**Redis 里没有 `feed:inbox:{userId}` 这个 key**。

重建策略（可落地且简单）：
- 目标集合 = 自己 + 最近 N 个关注对象
- 每个目标拉最近 K 条内容（从 MySQL）
- 合并排序后，写入 inbox（上限 M 条）
- 写入时会加一个“重建锁”，并用临时 key + RENAME 做原子替换

相关代码：
- 触发点：`FeedService#timeline`（首页 cursor 为空时调用 `rebuildIfNeeded`）
- 重建服务：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/FeedInboxRebuildService.java`
- 原子替换 + 锁：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/FeedTimelineRepository.java`

---

## 8. 关注/取关补偿（让在线用户“立刻看到变化”）

问题：你刚关注一个人，如果等下一次他发帖才写入 inbox，体验不好。

解决（只对在线用户）：
- 刚关注：立刻把对方最近 K 条内容写入你的 inbox（只补一点点，够体验）
- 取消关注：因为 inbox 里只有 postId 没有 authorId，没法精确删某个人的内容，所以直接强制重建

相关代码：
- 补偿服务：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/FeedFollowCompensationService.java`
- 关注事件来源：`RelationService#follow` / `RelationService#unfollow`
- 事件消费触达：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/listener/social/RelationEventListener.java`

---

## 9. 配置项（你调参只看这一节就够）

开发环境示例配置在这里（包含默认值）：
- `project/nexus/nexus-app/src/main/resources/application-dev.yml`

### 9.1 配置键一览表（默认值以 dev 配置/代码注解为准）

| 配置键 | 默认值 | 你能用它控制什么（白话） | 读取位置（代码） |
| --- | --- | --- | --- |
| `feed.inbox.maxSize` | 1000 | 每个用户 inbox 最多保留多少条索引（太小会“翻不动很久以前”，太大会占内存） | `FeedInboxProperties` → `FeedTimelineRepository#trimToMaxSize` |
| `feed.inbox.ttlDays` | 30 | inbox 多少天不访问就过期（过期后用户会被当作“离线”，下次首页会重建） | `FeedInboxProperties` → `FeedTimelineRepository#ttl` |
| `feed.outbox.maxSize` | 1000 | 每个作者 outbox 最多保留多少条索引 | `FeedOutboxProperties` → `FeedOutboxRepository#trimToMaxSize` |
| `feed.outbox.ttlDays` | 30 | outbox 多少天过期 | `FeedOutboxProperties` → `FeedOutboxRepository#ttl` |
| `feed.corefans.ttlDays` | 30 | 铁粉集合过期天数（避免永久膨胀） | `FeedCoreFansRepository` |
| `feed.fanout.batchSize` | 200 | fanout 切片大小（每个 task 处理多少粉丝） | `FeedFanoutDispatcherConsumer`、`FeedDistributionService` |
| `feed.rebuild.perFollowingLimit` | 20 | inbox 重建时：每个关注对象最多拉多少条内容 | `FeedInboxRebuildService#collectRecentPosts` |
| `feed.rebuild.inboxSize` | 200 | inbox 重建后：最终写入 inbox 的条数上限 | `FeedInboxRebuildService#buildInboxEntries` |
| `feed.rebuild.maxFollowings` | 2000 | inbox 重建/读侧扫描时：最多看多少个关注对象 | `FeedInboxRebuildService`、`FeedService` |
| `feed.rebuild.lockSeconds` | 30 | 重建互斥锁过期秒数（防止并发重建互相覆盖） | `FeedTimelineRepository#tryAcquireRebuildLock` |
| `feed.follow.compensate.recentPosts` | 20 | 刚关注补偿：回填对方最近多少条内容到 inbox | `FeedFollowCompensationService#onFollow` |
| `feed.bigv.followerThreshold` | 500000 | 大 V 阈值：粉丝数到多少开始“跳过全量写扩散” | `FeedFanoutDispatcherConsumer`、`FeedDistributionService`、`FeedService` |
| `feed.bigv.coreFanMaxPush` | 2000 | 大 V 发布时最多推送多少铁粉（只推在线） | `FeedFanoutDispatcherConsumer`、`FeedDistributionService` |
| `feed.bigv.pull.maxBigvFollowings` | 200 | 读侧最多把多少个关注对象当作“大 V 候选”去拉 outbox | `FeedService#pickBigVAuthors` |
| `feed.bigv.pull.perBigvLimit` | 50 | 每个大 V outbox 单次最多拉多少条索引 | `FeedService#listBigVCandidates` |
| `feed.bigv.pool.enabled` | false | 是否启用“大 V 聚合池”（兜底优化） | `FeedBigVPoolProperties`、`FeedService#shouldUseBigVPool` |
| `feed.bigv.pool.buckets` | 4 | 聚合池分几个桶（bucket） | `FeedBigVPoolProperties`、`FeedService#listPoolCandidates` |
| `feed.bigv.pool.maxSizePerBucket` | 500000 | 每个 bucket 最大保留条数 | `FeedBigVPoolProperties` → `FeedBigVPoolRepository#trimToMaxSize` |
| `feed.bigv.pool.ttlDays` | 7 | 聚合池过期天数（通常比 inbox/outbox 更短） | `FeedBigVPoolProperties` → `FeedBigVPoolRepository#ttl` |
| `feed.bigv.pool.fetchFactor` | 30 | 读侧从池里拉取的放大系数（池里有很多你不关注的人，需要多拉再过滤） | `FeedService#listPoolCandidates` |
| `feed.bigv.pool.triggerFollowings` | 200 | 你关注的人很多时，才启用“从聚合池读”（否则直接拉 outbox） | `FeedService#shouldUseBigVPool` |

### 9.2 Redis Key 一览表（按“写谁/读谁”来记）

| Key 模式 | 类型 | 写入方（代码） | 读取方（代码） | 过期/裁剪 |
| --- | --- | --- | --- | --- |
| `feed:inbox:{userId}` | ZSET | `FeedTimelineRepository#addToInbox` / `#replaceInbox` | `FeedTimelineRepository#pageInboxEntries` | TTL=`feed.inbox.ttlDays`；maxSize=`feed.inbox.maxSize` |
| `feed:inbox:tmp:{userId}:{epochMs}` | ZSET | `FeedTimelineRepository#replaceInbox`（临时 key） | 无（写完 RENAME） | 同 inbox |
| `feed:inbox:rebuild:lock:{userId}` | String | `FeedTimelineRepository#tryAcquireRebuildLock` | 无 | TTL=`feed.rebuild.lockSeconds` |
| `feed:outbox:{authorId}` | ZSET | `FeedOutboxRepository#addToOutbox` | `FeedOutboxRepository#pageOutbox` | TTL=`feed.outbox.ttlDays`；maxSize=`feed.outbox.maxSize` |
| `feed:bigv:pool:{bucket}` | ZSET | `FeedBigVPoolRepository#addToPool`（需 enabled=true） | `FeedBigVPoolRepository#pagePool` | TTL=`feed.bigv.pool.ttlDays`；maxSize=`feed.bigv.pool.maxSizePerBucket` |
| `feed:corefans:{authorId}` | SET | `FeedCoreFansRepository#addCoreFan` | `FeedCoreFansRepository#listCoreFans` | TTL=`feed.corefans.ttlDays`（每次写会续期） |
| `feed:neg:{userId}` | SET | `FeedNegativeFeedbackRepository#add`/`#remove` | `#contains`/`#listPostIds` | 当前实现未设置 TTL（会长期存在） |
| `feed:neg:postType:{userId}` | SET | `FeedNegativeFeedbackRepository#addPostType`/`#removePostType` | `#listPostTypes` | 当前实现未设置 TTL |
| `feed:neg:postTypeByPost:{userId}` | HASH | `FeedNegativeFeedbackRepository#saveSelectedPostType`/`#removeSelectedPostType` | `#getSelectedPostType` | 当前实现未设置 TTL |
| `social:adj:following:{sourceId}` | SET | `RelationAdjacencyCachePort#addFollow`/`#removeFollow`/`#rebuildFollowing` | `RelationAdjacencyCachePort#listFollowing` | 当前实现未设置 TTL |
| `social:adj:followers:{targetId}` / `social:adj:followers:bucket:{targetId}:b{n}` | SET | `RelationAdjacencyCachePort#addFollow`/`#removeFollow`/`#rebuildFollowers` | `RelationAdjacencyCachePort#listFollowers` | 当前实现未设置 TTL |

---

## 10. 本地跑通一条完整链路（最短路径）

你只要跑通下面 4 步，就能确认“分发领域”真正在工作：

1) 启动依赖：MySQL + Redis + RabbitMQ（见 `application-dev.yml`）
2) 启动应用（Spring Boot）：模块 `project/nexus/nexus-app`
3) 发帖：`POST /api/v1/content/publish`（必须带 Header `X-User-Id`）
4) 刷首页：`GET /api/v1/feed/timeline`（同样带 Header `X-User-Id`）

### 10.1 示例请求（可直接复制，按需改 userId/端口）

假设你的服务监听在 `http://localhost:8080`（如果不是，替换端口即可）。

发一条内容（作者自己一定能在首页看到）：

```bash
curl -X POST "http://localhost:8080/api/v1/content/publish" -H "Content-Type: application/json" -H "X-User-Id: 1001" -d "{\"text\":\"hello feed\",\"mediaInfo\":\"\",\"location\":\"\",\"visibility\":\"PUBLIC\",\"postTypes\":[\"TECH\"]}"
```

刷首页 timeline（注意：userId 来自 Header，不看 query/body 里的 userId）：

```bash
curl "http://localhost:8080/api/v1/feed/timeline?limit=20&feedType=FOLLOW" -H "X-User-Id: 1001"
```

如果你想验证“粉丝能收到分发”（fanout）：
1) 先让粉丝用户打开一次首页（让 `feed:inbox:{userId}` key 存在，成为“在线”）
2) 再关注作者
3) 作者再发帖
4) 粉丝刷首页应该能看到作者内容

关注接口：

```bash
curl -X POST "http://localhost:8080/api/v1/relation/follow" -H "Content-Type: application/json" -H "X-User-Id: 2001" -d "{\"targetId\":1001}"
```

相关代码：
- 发帖入口：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/ContentController.java`
- 发布后触发 after-commit：`ContentService#dispatchAfterCommit`
- MQ fanout：`FeedFanoutDispatcherConsumer` / `FeedFanoutTaskConsumer`
- 刷首页：`FeedController#timeline` / `FeedService#timeline`

---

## 11. 常见坑（读代码时最容易误解的点）

1) **“在线/离线”不是登录态**：只是 Redis inbox key 是否存在（`FeedTimelineRepository#inboxExists`）。
2) **大 V 不是不分发**：是“不全量写扩散”，改成 outbox/pool + corefans 推送（`FeedFanoutDispatcherConsumer#dispatch`）。
3) **DTO 里有 userId 但会被忽略**：以 `UserContext` 为准（`UserContextInterceptor` + `FeedController`）。
4) **失败不自动重试**：多个 MQ consumer 在异常时直接 `AmqpRejectAndDontRequeueException`，消息会进 DLQ，需要人工/任务补偿（见 `FeedFanoutConfig` 的 DLQ 配置）。
5) **读侧会做“懒清理”**：发现 postId 查不到，就把索引从 inbox/outbox/pool 清掉（`FeedService#cleanupMissingIndexes`）。
