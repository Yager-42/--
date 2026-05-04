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

### 砍掉的存储

| 砍掉 | 理由 |
|------|------|
| `feed:outbox:{authorId}` | 被 `feed:timeline:{authorId}` 替代 |
| `feed:bigv:pool:{bucket}` | 大 V 粉丝直接读大 V 的 timeline |
| `feed:global:latest` | 推荐系统降级兜底，不属于关注推送核心 |

### 不需要的机制

- `__NOMORE__` 哨兵：取消重建机制后不再需要
- Lua 原子重建 + merge window：取消重建后不再需要
- 重建锁：取消重建后不再需要

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
- 砍掉 `addToLatest`
- 砍掉 `addToPool`
- 砍掉 `addToInbox(author)` —— 读路径会合并 timeline
- `addToOutbox` → `addToTimeline`

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

### 关注

```
onFollow(followerId, followeeId)
│
├─ 读 feed:timeline:{followeeId} 最近 N 条
└─ ZADD feed:inbox:{followerId} 每条
```

必须接入到关注事件处理链路（当前 `FeedFollowCompensationService` 定义但未接入）。

### 取关

不操作 Redis。读路径过滤即可。

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
├─ 4. 归并排序（多路归并，publishTimeMs 降序）
│
├─ 5. 过滤
│   ├─ block 过滤
│   ├─ unfollow 过滤
│   └─ 脏帖清理：post 不存在或非 PUBLISHED → ZREM feed:inbox:{userId} postId
│
└─ 6. 组装 → FeedCardAssembleService
```

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

## MQ 拓扑

`social.feed` exchange 上的队列精简为：

| 队列 | routing key | 用途 |
|------|------------|------|
| `feed.post.published.queue` | `post.published` | 发帖 fanout 入口 |
| `feed.fanout.task.queue` | `feed.fanout.task` | fanout 切片执行 |
| `feed.index.cleanup.queue` | `post.updated`, `post.deleted` | 单一队列，处理更新和删除 |

每个队列配 1 个 DLQ。`post.deleted` 继续被推荐系统消费者同时消费。

### 砍掉的队列

| 砍掉 | 理由 |
|------|------|
| `feed.index.cleanup.updated.queue` | 合并为一个 cleanup 队列 |
| `feed.index.cleanup.deleted.queue` | 合并为一个 cleanup 队列 |

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
| Global Latest | 有 | 无 |
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

## 不做的事

1. 不引入新的存储系统或消息系统
2. 不修改推荐系统（Gorse）的 item 管理
3. 不修改 Count 系统
4. 不改变 HTTP API 接口签名
5. 不主动扫描粉丝 inbox（继续懒清理）
6. 不引入 Redis Stream / CDC / 分布式事务

## 验收用例

1. 普通作者发帖 → fanout 写入关注者 inbox，作者 timeline 包含该帖
2. 大 V 发帖 → 不 fanout，仅写入 timeline；粉丝读时从 timeline 拉取
3. 帖子删除 → timeline 中移除，inbox 中懒清理
4. 帖子更新为非 PUBLISHED → timeline 中移除
5. 新用户关注 → 被关注者最近 N 条写入关注者 inbox
6. 取关 → 读时过滤，不操作 Redis
7. 用户离线 30+ 天后回归 → inbox 不存在，从关注者 timeline 拉取并写回
8. 并发发帖期间 → 无 Lua 重建竞态（因不重建）

## 回滚与兼容

1. 新旧两种结构可以共存过渡：先上线新读路径（兼容旧 key），再切换写路径
2. 如果出现问题，可回退到旧架构（旧 key 未被删除，只是不再写入）
3. API 接口签名不变，上游无感知
