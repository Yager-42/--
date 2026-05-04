# Nexus Feed 关注推送简化重构设计

2026-05-04 | authored by rr & claude | status: design

## 动机

当前关注推送系统为了实现功能，叠加了过多优化和兜底机制：
- 4 种 Redis 数据存储（inbox、outbox、bigV pool、global latest）
- online/offline 二分判定 + Lua 原子重建 + merge window
- 大 V 独立 Pool + category 状态机 + outbox 重建
- 独立索引清理链路（两个队列 + DB 回表判断）
- 读侧懒清理混在组装逻辑里

本质上是为了优化极端场景（大 V、不活跃用户、并发竞态），污染了主路径。

## 目标

破坏性重构关注推送系统，只保留两种 Redis 结构（inbox + timeline），砍掉多余的存储、队列、重建逻辑和复杂的竞态处理。

## 范围边界

本设计只约束 **FOLLOW 关注推送索引**，不是整个 Feed 域、推荐域、关系域或缓存域的大清理。

### 本次必须改的范围

- FOLLOW 读写主链路：发帖 fanout、关注页读取、关注补偿、取关过滤、删除/不可见索引清理
- FOLLOW 专用 Redis 索引：`feed:inbox:{userId}` 与 `feed:timeline:{authorId}`
- `social.feed` exchange 下与 FOLLOW 索引维护直接相关的队列
- 与 FOLLOW 索引相关的配置键、仓储接口、消费者命名和测试

### 明确不在本次改动范围

- 推荐流 `RECOMMEND`、热门流 `POPULAR`、同城/邻近流等非 FOLLOW feedType
- 推荐系统 item upsert/delete、feedback、session、seen、Gorse 调用与推荐降级策略
- `feed:card:*` 卡片缓存、计数缓存、用户资料缓存、关系邻接缓存
- 关系真相源、关系 outbox、可靠 MQ outbox、消费者幂等记录
- 内容发布、编辑、删除的事务模型和事件 outbox 主链路

因此，“只保留两种 Redis 结构”仅表示 FOLLOW 推送索引只保留两类 ZSET。不能据此删除作者分类 hash、卡片缓存、推荐 session、关系邻接、可靠消息 outbox 等其他能力依赖。

## 术语约束

为避免实现时把现有命名继续放大歧义，后续代码、测试和文档按以下语义理解：

| 术语 | 含义 | Redis key | 备注 |
|------|------|-----------|------|
| InboxTimeline | 某个读者的关注收件箱 | `feed:inbox:{userId}` | 只由普通作者 fanout、关注补偿、离线回归激活写入 |
| AuthorTimeline | 某个作者自己的发布索引 | `feed:timeline:{authorId}` | 替代旧 outbox；大 V 和离线回归从这里拉 |
| HTTP timeline | 对外接口返回的 feed 页面 | 无固定 key | 由 InboxTimeline + AuthorTimeline 归并组装，不等于 AuthorTimeline |
| Outbox | 可靠消息 outbox 或旧作者发布索引 | 视上下文而定 | 新 FOLLOW 索引中不再使用 `feed:outbox:{authorId}` 这个 Redis key |

实现时如果保留旧接口名做小步迁移，必须在接口注释、测试名和适配器方法里写明语义已经是 AuthorTimeline；不能让 `outbox` 继续作为 FOLLOW 业务概念存在。

## 设计原则

1. FOLLOW 索引只有两个写入事实：作者发帖写 AuthorTimeline；普通作者 fanout、在线关注补偿、离线回归激活写 InboxTimeline。
2. 删除、不可见、取关、拉黑都不扫描粉丝 inbox；读侧过滤保证可见性，必要时只懒清理当前读者 inbox。
3. 大 V 不再有独立 pool，也不触发 outbox rebuild；大 V 只是不 fanout，读侧直接拉其 AuthorTimeline。
4. 离线回归不做原子替换重建，不写哨兵，不加重建锁；它只是一次读时拉取和激活 inbox。
5. 推荐流不能依赖 FOLLOW 写路径隐式维护 latest 兜底。如果推荐仍需要 latest 兜底，必须在推荐域另行保留、接管或替换，不能继续把 `feed:global:latest` 当 FOLLOW 核心索引。

## 存储模型

只保留两种 Redis ZSET：

```
feed:inbox:{userId}      — 粉丝收件箱，被 fanout 写入（NORMAL 作者的帖子）
feed:timeline:{authorId} — 作者的帖子列表，发帖时作者自己写入
```

配置独立：

```
feed.inbox.maxSize = 1000
feed.inbox.ttlDays = 30
feed.timeline.maxSize = 1000
feed.timeline.ttlDays = 30
```

实现约束：
- 两类 key 都使用 `publishTimeMs` 作为 ZSET score，member 为 `postId` 字符串。
- 同毫秒排序必须稳定：所有分页、归并、游标比较统一使用 `publishTimeMs DESC + postId DESC`。
- `timeline` 配置必须是独立配置，不继续复用 `feed.outbox.*`。旧 `feed.outbox.*` 只允许作为兼容期读取旧 key 的配置，不允许作为新 key 的主配置。
- TTL 语义保持“索引活跃期”，不是内容可见期；内容可见性以 DB 状态和关系过滤为准。

### 砍掉的存储

| 砍掉 | 理由 |
|------|------|
| `feed:outbox:{authorId}` | 被 `feed:timeline:{authorId}` 替代 |
| `feed:bigv:pool:{bucket}` | 大 V 粉丝直接读大 V 的 timeline |
| `feed:global:latest` 的 FOLLOW ownership | 推荐降级兜底不属于关注推送核心；FOLLOW 写路径不再负责维护它 |

### 保留但不计入 FOLLOW 两类索引

| 保留 | 理由 |
|------|------|
| 作者 category hash | 仍用于 NORMAL / BIGV 判定，但不能触发 outbox rebuild |
| `feed:card:*` | Feed 卡片基础信息缓存，属于组装层 |
| 推荐 session / seen / feedback 相关 key | 属于推荐链路，不由本设计清理 |
| 推荐 latest 兜底 key（如仍存在） | 属于推荐链路；本设计只取消 FOLLOW 对它的写入责任 |
| 关系邻接缓存 | 关注列表读取依赖，不属于 Feed 推送索引 |
| 可靠 MQ outbox / consumer record | 消息可靠性基础设施，不属于 Feed Redis 索引 |

### 不需要的机制

- `__NOMORE__` 哨兵：取消重建机制后不再需要
- Lua 原子重建 + merge window：取消重建后不再需要
- 重建锁：取消重建后不再需要
- outbox rebuild service：AuthorTimeline 只由发帖和清理事件维护，不做全量重建

## 写路径

### 发帖

```
FeedFanoutDispatcherConsumer.onMessage(PostPublishedEvent)
│
├─ ZADD feed:timeline:{authorId}  postId, publishTimeMs
│   └─ trim to maxSize + set TTL
│
├─ 查询作者 category（大 V / 普通）
│
├─ 大 V → 结束（不做 fanout）
│
└─ 普通 → 计数粉丝 → 切片
    └─ 每片发 FeedFanoutTask MQ → FeedFanoutTaskConsumer
        └─ fanoutSlice: pageFollowers → filterOnline → ZADD feed:inbox:{followerId}
```

和当前的差异：
- 砍掉 FOLLOW dispatcher 中的 `addToLatest`
- 砍掉 `addToPool`
- 砍掉 `addToInbox(author)` —— 读路径会合并 timeline
- `addToOutbox` → `addToTimeline`

实现规则：
- `PostPublishedEvent.authorId` 是 AuthorTimeline key 的唯一来源；不能从当前登录态、operatorId 或回表猜测作者。
- Dispatcher 不再依赖 `IFeedOutboxRepository`、`IFeedBigVPoolRepository`、`IFeedGlobalLatestRepository`。如果推荐域仍保留 latest 兜底，必须由推荐域自己的写入或回填机制维护。
- 作者本人在 FOLLOW 页看见自己的帖子，统一通过读路径合并自己的 AuthorTimeline 实现；不能继续在发帖时特殊写作者 inbox，也不能要求用户关注自己。
- category 缺失时允许按当前粉丝数补齐 category hash，但补齐动作不得触发 AuthorTimeline 重建。
- fanout 切片模型和 producer 保持；切片 consumer 只负责把 NORMAL 作者帖子写入在线粉丝 inbox，不读取或写入 AuthorTimeline。

### 删除 / 不可见

```
post.deleted / post.updated → FeedIndexCleanupConsumer（单一队列）
│
├─ 回表 DB 取 post.userId（authorId 必须来自 DB，不用 operatorId）
└─ ZREM feed:timeline:{authorId} postId
```

inbox 不主动清理，读时懒处理。

和当前的差异：
- 砍掉 `removeFromLatest`
- 砍掉 `removeFromPool`
- 砍掉 `removeFromOutbox` → 变为 `removeFromTimeline`
- updated/deleted 合并为一个队列，一个 consumer 做一件事
- 仍需 DB 回表取 authorId（PostUpdatedEvent/PostDeletedEvent 不携带 authorId）

硬性前提：
- 删除事件要能清理 AuthorTimeline，必须满足二选一：内容删除是软删且 `findPostBypassCache(postId)` 仍可读到 `userId`；或事件模型补充 `authorId`。如果两者都不满足，`post.deleted` 无法可靠执行 `ZREM feed:timeline:{authorId}`。
- 本设计优先选择“继续依赖软删回表”，不扩大事件模型；实现时必须用测试固定这个前提。
- 若回表返回 null，cleanup consumer 只能记录告警/指标并结束，不能扫描所有 timeline，也不能用 operatorId 替代 authorId。
- `post.updated` 只有当 DB 状态不是 `PUBLISHED` 时才移除 AuthorTimeline；普通内容编辑但仍 PUBLISHED 不移除索引。
- cleanup consumer 不处理 inbox 删除，也不处理推荐 item delete；推荐删除消费者继续走原有独立队列。

### 关注

```
onFollow(followerId, followeeId)
│
├─ 读 feed:timeline:{followeeId} 最近 N 条
└─ ZADD feed:inbox:{followerId} 每条
```

必须接入到关注事件处理链路（当前 `FeedFollowCompensationService` 定义但未接入）。

接入规则：
- 关注补偿挂在关系事件消费之后，而不是关系写事务内直接写 Feed Redis。
- 当前关系事件已经有 `FOLLOW ACTIVE / UNFOLLOW` 投影链路；Feed 关注补偿必须新增独立队列消费同一个 relation exchange 的 FOLLOW 路由，不能挂到关系计数投影事务里。
- 新队列只做 Feed inbox 补偿，失败只进入自己的 DLQ，不影响关系计数、粉丝倒排、邻接缓存投影成功。
- `ACTIVE` 调用 `onFollow(sourceId, targetId)`；`UNFOLLOW` 调用 `onUnfollow(sourceId, targetId)`。
- `onFollow` 从 AuthorTimeline 读取，不再从 DB `listUserPosts` 回表拉最近内容。只有 AuthorTimeline key miss 且实现选择兼容旧数据时，才允许短期回退 DB；兼容期结束后删除该回退。
- 关注补偿只对在线用户执行：`feed:inbox:{followerId}` 存在才写入。这样 `inbox key exists` 继续保持“在线/活跃”语义，不会因为离线关注动作提前激活 inbox。
- 离线用户新关注后的内容回填由下一次 FOLLOW 首页激活处理；不在关注事件里创建 inbox。
- `recentPosts` 只限制单次关注补偿拉取条数，不改变 AuthorTimeline 的保留上限。

### 取关

不操作 Redis。读路径过滤即可。

规则：
- 取关不强制重建 inbox，不扫描 inbox，不按 authorId 精确删除旧条目。
- 取关后的“立刻不可见”由读侧根据实时关注关系过滤保证。
- 拉黑沿用同一原则：关系链路清理关注边，Feed 读侧用 block 过滤兜住可见性。

## 读路径

### FOLLOW Timeline 读取

```
FeedService.followTimeline(userId, cursor)
│
├─ 1. 关注列表分组：NORMAL / BIGV
│
├─ 2. pageInbox(userId, cursor, limit)
│     从 feed:inbox:{userId} 拉取（NORMAL 作者 fanout 写入）
│
├─ 3. 对每个 BIGV 关注对象:
│     pageTimeline(bigVAuthorId, cursor, limit)
│     从 feed:timeline:{bigVAuthorId} 拉取
│
├─ 3.5 固定拉取当前用户自己的 AuthorTimeline:
│     pageTimeline(userId, cursor, limit)
│     从 feed:timeline:{userId} 拉取
│
├─ 4. 归并排序（多路归并，publishTimeMs 降序）
│
├─ 5. 过滤
│   ├─ block 过滤
│   ├─ unfollow 过滤
│   └─ 脏帖清理：post 不存在或非 PUBLISHED → ZREM feed:inbox:{userId} postId
│
└─ 6. 组装 → FeedCardAssembleService
```

读路径规则：
- FOLLOW 页必须统一走 Max-ID 游标：`cursorTs + cursorPostId`。旧字符串 cursor 只作为兼容入口，不应继续作为新 FOLLOW 主分页协议。
- 日常 FOLLOW 读取固定拉 `InboxTimeline + BIGV AuthorTimeline + self AuthorTimeline`。NORMAL 关注者的帖子应已通过 fanout/补偿/激活进入 inbox，不能每次读都扫描所有 NORMAL 关注者 timeline。
- 所有被拉取的 source 都按同一个 cursor 拉取，再统一多路归并；不能先分别取满一页后按 source 分段返回。
- 归并前后都必须按 postId 去重，避免普通作者切换 category、关注补偿或兼容期双写造成重复卡片。
- 过滤必须在组装前完成：不存在、非 PUBLISHED、未关注、互相拉黑都不能进入 `FeedCardAssembleService`。
- 懒清理只允许清理当前用户 `feed:inbox:{userId}` 中的脏 postId；不能清理 AuthorTimeline，AuthorTimeline 只由 cleanup consumer 维护。
- 如果一页候选被过滤后不足 `limit`，允许在同次请求内按扫描预算继续向后补拉；扫描预算必须有上限，避免关注数或脏数据导致长尾请求。
- `FeedCardAssembleService` 保持组装职责，不塞入索引清理、关注判断或 timeline 拉取逻辑。

### 重建（用户长时间离线后回归）

取消被动重建，改为读时自然恢复：

```
inbox key 不存在？（离线 > 30 天，TTL 过期）
│
│   → 不扫描所有关注者，不执行重建
│   → 从所有关注者的 timeline 拉取第一页（包括 NORMAL 和 BIGV 关注者）
│   → 多路归并后的结果写入 feed:inbox:{userId}（重新激活）
│   → 之后正常 fanout 照常写入 inbox
│
└─ inbox key 存在？→ 正常流程（pageInbox + pageTimeline for BIGV）
```

核心取舍：离线回归后第一页稍慢（多了 N 次 pull），但消除了 rebuild service + Lua 脚本 + merge window + 重建锁。

离线回归规则：
- 触发条件只在 FOLLOW 首页 refresh 且 `feed:inbox:{userId}` key 不存在；翻页请求不能触发回归激活。
- 回归激活读取关注列表中的 NORMAL 和 BIGV 作者 AuthorTimeline；不再用 DB `listUserPosts` 按作者回表重建。
- 回归激活写入 inbox 的是归并后的第一页候选，最多写 `feed.rebuild.inboxSize` 或新的等价配置；它不是全量重建。
- 激活写入不能用 Lua rename 覆盖正式 key，不能写 `__NOMORE__`，不能使用 rebuild lock。允许普通 ZADD + trim + TTL，因为并发 fanout 写入与激活写入都是幂等追加。
- 空关注或无候选时，本文档选择“不写哨兵、不创建空 inbox”。结果是下一次首页仍会尝试激活；这是为了避免重新引入哨兵语义。若后续要优化空用户重复尝试，只能用独立轻量短 TTL 标记，且不能混入 inbox ZSET。
- 作者本人 timeline 是否参与回归必须与 FOLLOW 页日常读路径一致；本文档默认包含当前用户自己的 AuthorTimeline，让作者能在 FOLLOW 页看到自己的发布内容。

## MQ 拓扑

`social.feed` exchange 上的队列精简为：

| 队列 | routing key | 用途 |
|------|------------|------|
| `feed.post.published.queue` | `post.published` | 发帖 fanout 入口 |
| `feed.fanout.task.queue` | `feed.fanout.task` | fanout 切片执行 |
| `feed.index.cleanup.queue` | `post.updated`, `post.deleted` | 单一队列，处理更新和删除 |

每个队列配 1 个 DLQ。`post.deleted` 继续被推荐系统消费者同时消费。

实现规则：
- cleanup 是一个队列绑定两个 routing key，不是两个 listener 方法绑定两个队列。
- cleanup consumer 可以保留两个入口方法解析不同事件类型，但消费队列必须唯一，DLQ 也必须唯一。
- 队列常量、bean 名、DLQ routing key、`@RabbitListener`、`@ReliableMqConsume.consumerName` 必须同步改名，不能留下 updated/deleted 双队列的半旧拓扑。
- 删除旧队列声明不等于删除 RabbitMQ 中已存在的物理队列；上线说明要写明旧队列需要运维清理或自然废弃。
- 推荐 item upsert/delete 队列不属于本表，不能因为 `post.deleted` 也被推荐消费而合并进 `social.feed` cleanup。
- 关注补偿消费 relation exchange 的 FOLLOW 路由，使用独立队列和 DLQ；它不计入 `social.feed` 的 3 个队列，也不能复用关系计数投影队列。

### 砍掉的队列

| 砍掉 | 理由 |
|------|------|
| `feed.index.cleanup.updated.queue` | 合并为一个 cleanup 队列 |
| `feed.index.cleanup.deleted.queue` | 合并为一个 cleanup 队列 |

## 代码边界约束

后续实现要按现有分层收敛，不引入跨层捷径：

| 层 | 允许做 | 不允许做 |
|----|--------|----------|
| trigger consumer | 消费 MQ、校验事件、调用领域服务或仓储端口 | 直接拼复杂读侧逻辑、扫描 Redis keyspace |
| domain service | 编排 fanout、关注补偿、FOLLOW 读取、过滤、归并 | 依赖 RedisTemplate、RabbitTemplate 或 MyBatis mapper |
| domain repository interface | 表达 InboxTimeline / AuthorTimeline 语义 | 暴露 Redis 低级命令或 key 拼接细节 |
| infrastructure repository | 实现 ZSET add/page/remove/trim/ttl | 做关注关系判断、内容状态判断、卡片组装 |
| assemble service | 批量组装 FeedItem | 拉取 timeline、清理索引、改变游标 |

建议的命名收敛：
- 新增或重命名为 `IFeedAuthorTimelineRepository` 表达 AuthorTimeline；如果为降低改动保留 `IFeedOutboxRepository`，必须把旧 outbox Redis key 与 rebuild 方法标为兼容期待删除。
- `IFeedTimelineRepository` 当前实际表示 InboxTimeline；不要在其中加入作者 timeline 方法，避免一个接口同时代表读者 inbox 和作者发布流。
- `FeedInboxRebuildService` 的重建语义要删除或改名为 `FeedInboxActivationService`。如果保留类名，必须删除 `forceRebuild`、Lua replace、lock、哨兵、merge window 等旧语义。
- `FeedAuthorCategoryStateMachine` 只能维护 category，不再依赖 `IFeedOutboxRebuildService`。

## 和当前实现的差异总览

| | 当前 | 重构后 |
|------|------|------|
| Redis key 类型 | 4 (inbox, outbox, pool, latest) | 2 (inbox, timeline) |
| MQ 队列 (social.feed) | 6+ | 3 |
| Consumer 类 | 5+ | 3 (Dispatcher, Task, Cleanup) |
| Lua 脚本 | inbox/outbox 原子重建 | 无 |
| 重建逻辑 | rebuildService × 2 + merge window | 读时自然恢复 |
| Big V 处理 | 独立 Pool + category 状态机 + outbox 重建 | 直接读 timeline |
| 索引清理 | 回表判断 → 清 3 个索引 | 只 ZREM timeline |
| Global Latest | FOLLOW 发帖写入并被推荐兜底复用 | FOLLOW 不再拥有；推荐是否保留另行处理 |
| 关注补偿 | 写了没接入 | 接入关注事件 |
| 哨兵 __NOMORE__ | 有 | 无 |

## 不变的组件

以下现有组件不受影响，继续按当前逻辑运作：

- `FeedFanoutTask` 切片模型（record）
- `PostPublishedEvent`、`PostUpdatedEvent`、`PostDeletedEvent` 事件类型
- `FeedFanoutTaskProducer` 发布切片消息
- `filterOnlineUsers`（EXISTS pipeline 过滤在线用户）
- `FeedCardAssembleService` 组装逻辑
- `IRelationRepository` 关注关系查询
- `FeedAuthorCategoryRepository` 大 V 判定（保留 category hash）
- 推荐 item upsert/delete 消费者（独立于关注推送）
- DLQ 完备性（每个队列配 DLQ）

需要修正的表述：
- `filterOnlineUsers` 保留在 fanout 切片里；关注补偿使用单用户 `inboxExists` 判定；离线回归不使用在线过滤。
- `FeedAuthorCategoryRepository` 保留，但 `FeedAuthorCategoryStateMachine` 不再触发 outbox rebuild。
- 推荐消费者不受 FOLLOW 索引简化影响；如果推荐读侧当前依赖 `feed:global:latest` 作为兜底，不能在本次实现中直接删仓储或删 key，必须先让推荐域接管或替换该兜底。

## 不做的事

1. 不引入新的存储系统或消息系统
2. 不修改推荐系统（Gorse）的 item 管理
3. 不修改 Count 系统
4. 不改变 HTTP API 接口签名
5. 不主动扫描粉丝 inbox（继续懒清理）
6. 不引入 Redis Stream / CDC / 分布式事务
7. 不在设计或实现中保留大段旧 Lua 作为“备用重建”
8. 不为了删除旧 key 写 Redis keyspace scan 迁移脚本
9. 不在关系写事务内同步写 Feed Redis

## 验收用例

1. 普通作者发帖 → fanout 写入关注者 inbox，作者 timeline 包含该帖
2. 大 V 发帖 → 不 fanout，仅写入 timeline；粉丝读时从 timeline 拉取
3. 帖子删除 → timeline 中移除，inbox 中懒清理
4. 帖子更新为非 PUBLISHED → timeline 中移除
5. 新用户关注 → 被关注者最近 N 条写入关注者 inbox
6. 取关 → 读时过滤，不操作 Redis
7. 用户离线 30+ 天后回归 → inbox 不存在，从关注者 timeline 拉取并写回
8. 并发发帖期间 → 无 Lua 重建竞态（因不重建）

补充验收约束：
9. 作者本人发帖后，FOLLOW 页能通过作者 AuthorTimeline 看到该帖，且发帖路径不写作者 inbox
10. category 从 NORMAL 变 BIGV 或 BIGV 变 NORMAL 时，不触发 outbox/timeline 全量重建
11. FOLLOW 读侧同一 postId 同时存在 inbox 和 AuthorTimeline 时，只返回一次
12. `post.updated` 仍为 PUBLISHED 时不移除 AuthorTimeline；变为非 PUBLISHED 时移除
13. `post.deleted` 回表拿不到 authorId 时不误删、不扫描，只记录可观测告警
14. 关注 ACTIVE 事件重复投递时，补偿写入幂等，不产生重复 ZSET 成员
15. 取关/拉黑后，即使 inbox 中仍有旧帖子，FOLLOW 页也不返回这些帖子
16. 空 inbox 回归激活不写 `__NOMORE__`，Redis 中不出现该 member
17. `social.feed` 中 cleanup 只有一个业务队列和一个 DLQ；旧 updated/deleted cleanup 队列不再被 listener 绑定
18. RECOMMEND / POPULAR / PROFILE 相关测试不因 FOLLOW 索引改名而改变接口行为

## 回滚与兼容

1. 新旧两种结构可以共存过渡：先上线新读路径（兼容旧 key），再切换写路径
2. 如果出现问题，可回退到旧架构（旧 key 未被删除，只是不再写入）
3. API 接口签名不变，上游无感知

兼容期规则：
- 新读路径可以短期同时读旧 `feed:outbox:{authorId}` 和新 `feed:timeline:{authorId}`，但必须去重，并以新 key 为主。
- 新 FOLLOW 写路径上线后只写 `feed:timeline:{authorId}`，不双写旧 outbox/pool/latest；如果推荐 latest 被保留，它必须由推荐域维护，不能作为 FOLLOW dispatcher 的副作用。
- 回滚窗口内不删除旧 key、不迁移旧 key、不扫描 Redis；兼容读取依赖 TTL 自然过期。
- 如果需要回退旧架构，必须回退消费者与配置，使旧 outbox/pool/latest 恢复写入；不能只切读路径。

## 自审结论

本设计现在固定了几个容易实现偏差的边界：
- “两种 Redis 结构”只限定 FOLLOW 推送索引，不影响推荐、卡片、关系、可靠消息等其他能力。
- AuthorTimeline 是作者发布索引，不是 HTTP timeline，也不是当前 `IFeedTimelineRepository` 的 inbox 语义。
- 删除清理依赖软删可回表 authorId；事件不带 authorId 且 DB 不可回表时不能伪装成可清理。
- 离线回归是激活，不是重建；不能引回 Lua、哨兵、锁、merge window。
- 关注补偿必须接关系事件链路，但不进入关系写事务。
- 推荐系统如果仍需要 latest 兜底，要在推荐域单独处理，不能保留“FOLLOW dispatcher 顺手写 latest”的隐性依赖。
