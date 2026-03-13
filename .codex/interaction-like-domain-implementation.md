# 点赞领域（Reaction LIKE）技术文档（基于当前代码实现）

这份文档只讲“现在项目里已经写出来的东西”，不讲脑补。
目标是：新同学按这份文档，看代码就能把“点赞从入口到落库、到通知/旁路”的整条链路吃透。

---

## 0. 这套点赞到底在解决什么问题？

一句话：**把“点赞/取消点赞”变成一个高吞吐、可并发、可重放、最终落库的流程**。

我们把“写请求”拆成两段：

1) **在线写（HTTP 主链路）**：只写 Redis（Lua 原子），很快返回给用户  
2) **延迟落库（MQ 旁路）**：把这段时间里攒下来的变更批量写进 MySQL

对应代码：

- 在线写：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/ReactionLikeService.java`
- 延迟落库：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/ReactionSyncConsumer.java`
- Redis 原子更新：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/ReactionCachePort.java`
- MySQL 写入：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/ReactionRepository.java`

---

## 1) 领域边界：这里的“点赞”具体指什么？

在代码里，点赞被抽象成 **Reaction（态势）**，但目前业务只开放 `LIKE`。

- ReactionType：`LIKE / LOVE / ANGRY`（枚举已预留，但 `LIKE` 才能走通）
  - `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/model/valobj/ReactionTypeEnumVO.java`
- TargetType：`POST / COMMENT`
  - `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/model/valobj/ReactionTargetTypeEnumVO.java`

核心约束：

- 只支持 `LIKE`：在 domain 层强校验，避免“接口随便传个字符串就进来”
  - `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/ReactionLikeService.java`（`requireLikeOnly`）

---

## 2) 核心数据结构（你要先记住这几个名词）

### 2.1 ReactionTargetVO：点赞目标三元组（唯一键）

它把“我在对什么点赞”固定成三件事：

- `targetType`：POST/COMMENT
- `targetId`
- `reactionType`：LIKE（当前只允许这个）

实现：

- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/model/valobj/ReactionTargetVO.java`

最关键的方法是 `hashTag()`：

```java
// project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/model/valobj/ReactionTargetVO.java
public String hashTag() {
  return "{" + targetType + ":" + targetId + ":" + reactionType + "}";
}
```

它的作用很简单：**强制同一个 target 的所有 Redis key 落在同一个 slot**，这样 Redis Cluster 才能安全执行 Lua/RENAME。

### 2.2 ReactionActionEnumVO：动作是“设状态”，不是“切换”

`ADD` 代表“我想要最终是点赞状态”，`REMOVE` 代表“我想要最终是取消状态”。

实现：

- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/model/valobj/ReactionActionEnumVO.java`

关键点：这样天生幂等（同一个动作重复来，不会越点越多次）。

### 2.3 在线写结果：ReactionResultVO / ReactionApplyResultVO

- `ReactionApplyResultVO`：Redis Lua 返回的“真实变更”
  - `currentCount`：Redis 里的近实时计数
  - `delta`：这次是否真的改变了集合（+1/-1/0）
  - `firstPending`：是否第一次把这个 target 标记为“需要同步”
  - `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/model/valobj/ReactionApplyResultVO.java`

- `ReactionResultVO`：HTTP 返回给上层的结果（注意语义）
  - `success=true` **只代表 Redis 接住了**，不代表 DB 已经一致
  - `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/model/valobj/ReactionResultVO.java`
  - `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/ReactionLikeService.java`（类注释有明确说明）

---

## 3) 对外接口（HTTP）：入口在哪里？怎么调用？

### 3.1 用户身份：从 Header 注入 userId

约定：网关注入 `X-User-Id`，服务端直接使用。

实现：

- `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/support/UserContext.java`
- `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/support/UserContextInterceptor.java`

### 3.2 写接口：点赞/取消点赞

入口：

- `POST /api/v1/interact/reaction`
- `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/InteractionController.java`

请求 DTO：

- `project/nexus/nexus-api/src/main/java/cn/nexus/api/social/interaction/dto/ReactionRequestDTO.java`

字段含义（按实际代码）：

- `requestId`：可选，只用于串日志/对账；**不参与幂等**
- `targetId`
- `targetType`：POST/COMMENT
- `type`：LIKE（虽然枚举预留了其他，但会被 domain 拒绝）
- `action`：ADD/REMOVE

调用链：

```text
InteractionController.react
  -> InteractionService.react
     -> parseTarget(...) (业务约束校验)
     -> ReactionLikeService.applyReaction(...)
        -> ReactionCachePort.applyAtomic(...) (Redis Lua)
        -> (firstPending 才) ReactionSyncProducer.sendDelay(...) (Rabbit 延迟消息)
```

关键实现位置：

- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/InteractionService.java`（`react` + `parseTarget`）
- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/ReactionLikeService.java`（`applyReaction`）

### 3.3 读接口：查询我是否点过赞 + 当前计数

入口：

- `GET /api/v1/interact/reaction/state`
- `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/InteractionController.java`

请求/响应 DTO：

- `project/nexus/nexus-api/src/main/java/cn/nexus/api/social/interaction/dto/ReactionStateRequestDTO.java`
- `project/nexus/nexus-api/src/main/java/cn/nexus/api/social/interaction/dto/ReactionStateResponseDTO.java`

实现位置：

- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/ReactionLikeService.java`（`queryState`）
- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/ReactionCachePort.java`（`getState/getCount`）

---

## 4) 在线写链路：为什么只写 Redis？写了什么？

在线写的“真相”在一行代码里：

```java
// project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/ReactionLikeService.java
ReactionApplyResultVO res = reactionCachePort.applyAtomic(userId, target, desiredState, SYNC_TTL_SEC);
```

### 4.1 applyAtomic 做了 4 件事（同一段 Lua，一次性完成）

1) 用 `SADD/SREM` 维护“谁点过赞”的集合（Set 去重）
2) 用 `INCR/DECR` 维护“点赞数”（Count）
3) 用 `HSET` 记录这段时间里发生过的变更（ops）
4) 用 `SET NX EX` 打一个“需要同步”的标记（syncKey），并告诉你是否是第一次 pending

Lua 实现在：

- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/ReactionCachePort.java`（`LUA_APPLY_ATOMIC`）

你在阅读 Lua 时，重点看两段：

- delta 怎么算（决定幂等与通知）
- firstPending 怎么算（决定 MQ 是否投递）

### 4.2 为什么通知只在 delta=+1 时发？

因为我们只想在“真的新增点赞”时通知目标作者，重复点赞（幂等）不应该刷通知。

实现：

```java
// project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/ReactionLikeService.java
if (delta == 1) {
  publishNotifyLikeAdded(rid, userId, target);
}
```

---

## 5) Redis 设计：有哪些 key？长什么样？

所有 key 都带同一个 `{POST:90001:LIKE}` 这种 hash-tag（来自 `ReactionTargetVO.hashTag()`），避免跨 slot。

实现：

- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/ReactionCachePort.java`

### 5.1 Key 列表（按当前实现）

```text
interact:reaction:set:{target}            // Redis SET：当前点赞用户集合
interact:reaction:cnt:{target}            // Redis String：近实时计数
interact:reaction:ops:{target}            // Redis HASH：userId -> desiredState（待同步变更）
interact:reaction:ops:processing:{target} // Redis HASH：同步中的快照（rename 过来）
interact:reaction:sync:{target}           // Redis String：是否已投递同步（NX+TTL）
interact:reaction:last_sync:{target}      // Redis String：最后同步时间戳（仅留痕）
interact:reaction:window_ms:{target}      // Redis String：可选，动态窗口（不设置则用默认值）
```

### 5.2 计数自愈：cntKey 丢了/坏了怎么办？

两个地方都做了防御：

1) Lua 里：`GET cntKey` 为空就用 `SCARD setKey` 重建（避免 DECR 到负数）
   - `ReactionCachePort.LUA_APPLY_ATOMIC`
2) Java 里：读 count 时发现 cnt 不合法，也会用 `SCARD` 重建并回填
   - `ReactionCachePort.redisGetCntOrRebuild`

### 5.3 热点优化：只有热点 key 才走 L1（Caffeine）

逻辑在：

- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/ReactionCachePort.java`（`getCount`）

它用 JD hotkey 判断热点：

```java
// project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/ReactionCachePort.java
boolean hot = JdHotKeyStore.isHotKey(hotkey);
```

热点才把 count 放进本地缓存（TTL=2 秒），否则直接读 Redis。

---

## 6) 延迟落库：消息怎么投？谁来消费？怎么防并发/重复？

### 6.1 什么时候投递延迟消息？

只有当 `syncKey` 第一次从“无”变成 “PENDING” 时才投递。

实现：

- Redis 侧：Lua `SET syncKey 'PENDING' NX EX syncTtlSec`
  - `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/ReactionCachePort.java`（`LUA_APPLY_ATOMIC`）
- domain 侧：`firstPending==true` 才 `sendDelay`
  - `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/ReactionLikeService.java`

### 6.2 RabbitMQ 延迟队列配置（依赖 x-delayed-message 插件）

实现：

- `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/config/ReactionSyncDelayConfig.java`

注意：`ReactionSyncProducer` 用消息头 `x-delay` 来设置延迟：

- `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/producer/ReactionSyncProducer.java`

### 6.3 延迟消息内容长什么样？

当前实现是最简单可用的 JSON 字符串：

```json
{"targetType":"POST","targetId":90001,"reactionType":"LIKE","attempt":0}
```

构造位置：

- `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/producer/ReactionSyncProducer.java`（`buildMessage`）

### 6.4 消费者怎么做“单 target 串行”？

消费者在处理前用 Redis 加锁：

- lockKey：`interact:reaction:lock:` + `target.hashTag()`
- TTL：60 秒

实现：

- `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/ReactionSyncConsumer.java`

拿不到锁就“再投一次”（重试），最多 30 次，超了进 DLQ：

- `MAX_ATTEMPT = 30`
- `RETRY_DELAY_MS = 1000`

---

## 7) syncTarget：延迟落库具体做什么？（最核心的第二段链路）

同步的目标是：**把 Redis 里这段窗口内积累的 ops，批量写进 MySQL**。

实现入口：

- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/ReactionLikeService.java`（`syncTarget`）
- `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/ReactionSyncConsumer.java`（调用它）

### 7.1 ops 快照：为什么要 snapshot？怎么做？

问题：同步过程中如果有人继续点赞，会不会丢？

答案：不会。因为同步先把 `opsKey` **原子 rename** 到 `processingKey`，把“本次要处理的一批”冻结住。

实现：

- Lua：`LUA_SNAPSHOT_OPS`
  - `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/ReactionCachePort.java`
- Java：`snapshotOps/readOpsSnapshot/clearOpsSnapshot`
  - 同一个文件 `ReactionCachePort.java`

关键语义：如果 `processingKey` 还在，说明上次没清干净，Lua 会直接复用，不覆盖。

### 7.2 批量落库：写哪两张表？

两张表：

1) **事实表**（真相）：谁对哪个目标点过赞  
2) **计数表**（派生）：某目标的 likeCount

DDL 在：

- `project/nexus/docs/social_schema.sql`（`interaction_reaction` / `interaction_reaction_count`）

写入实现：

- 仓储：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/ReactionRepository.java`
- Mapper：
  - `project/nexus/nexus-infrastructure/src/main/resources/mapper/social/InteractionReactionMapper.xml`
  - `project/nexus/nexus-infrastructure/src/main/resources/mapper/social/InteractionReactionCountMapper.xml`

事实表 upsert 用 `ON DUPLICATE KEY`，天然幂等：

```xml
<!-- project/nexus/nexus-infrastructure/src/main/resources/mapper/social/InteractionReactionMapper.xml -->
INSERT INTO interaction_reaction(...)
...
ON DUPLICATE KEY UPDATE update_time = VALUES(update_time)
```

计数表也是覆盖写（派生值，不做累加）：

```xml
<!-- project/nexus/nexus-infrastructure/src/main/resources/mapper/social/InteractionReactionCountMapper.xml -->
ON DUPLICATE KEY UPDATE count = VALUES(count)
```

### 7.3 为什么计数是“覆盖写”，不是 “count += delta”？

因为 delta 累加在“至少一次投递”的 MQ 世界里很容易重复。
覆盖写的语义更简单：**以 Redis 的当前 count 为准，DB 对齐它**。

实现：

- `ReactionLikeService.syncTarget` 里先 `getCountFromRedis`，再 `reactionRepository.upsertCount`
  - `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/ReactionLikeService.java`

### 7.4 同步期间又产生新 ops 怎么办？

`syncTarget` 最后会检查 `existsOps(target)`：

- 没有新 ops：结束
- 有新 ops：重新 set pending + 再投递一次延迟消息（避免丢更新）

实现：

- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/ReactionLikeService.java`

---

## 8) 读链路：state 和 count 以谁为准？

当前实现里：

- **state**：以 Redis set 为准（`SISMEMBER`）
  - `ReactionCachePort.getState`
- **count**：以 Redis cntKey 为准（热点可能命中 L1），必要时用 set 基数重建
  - `ReactionCachePort.getCount` / `redisGetCntOrRebuild`

实现：

- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/ReactionLikeService.java`（`queryState`）
- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/ReactionCachePort.java`

注意：DB 的 `interaction_reaction_count` 是最终一致对账用（也可以给离线/运营查询用）。

---

## 9) 特殊业务规则（你最容易踩坑的 if/else）

### 9.1 只允许点赞一级评论，不允许点赞楼内回复

这是明确业务约束，不是补丁。

实现位置：

- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/InteractionService.java`（`parseTarget`）

代码会回表拿 `CommentBriefVO`，如果 `rootId != null` 直接拒绝：

```java
// project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/InteractionService.java
if (c.getRootId() != null) {
  throw new AppException(..., "楼内回复不允许点赞");
}
```

### 9.2 只支持 LIKE

实现：

- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/ReactionLikeService.java`（`requireLikeOnly`）

---

## 10) 幂等/并发/一致性：这套设计靠什么站住脚？

你只要记住 4 个“硬点”：

1) **动作是 set-state（ADD/REMOVE）**，重复请求不会抖动  
   - `ReactionActionEnumVO.desiredState()`  
   - `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/model/valobj/ReactionActionEnumVO.java`

2) **Redis 用 Lua 一次性做完 set+count+ops+sync**（原子性）  
   - `ReactionCachePort.LUA_APPLY_ATOMIC`  
   - `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/ReactionCachePort.java`

3) **落库用 rename 快照**，同步时不丢并发写入  
   - `ReactionCachePort.LUA_SNAPSHOT_OPS`

4) **MQ 至少一次投递**：靠“覆盖写计数 + DB upsert + inbox 去重”抵抗重复消费  
   - Reaction 同步链路：覆盖写 `interaction_reaction_count`  
   - 通知链路：`interaction_notify_inbox`（insert ignore）  
     - `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/InteractionNotifyConsumer.java`
     - `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/InteractionNotifyInboxPort.java`

---

## 11) 点赞带来的旁路业务（新同学必须知道会“顺带发生什么”）

### 11.1 站内通知：只吃 “LIKE_ADDED（delta=+1）”

点赞成功且 `delta==1` 时，domain 会发布 `InteractionNotifyEvent`：

- 生产端（domain -> port）：`ReactionLikeService.publishNotifyLikeAdded`
  - `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/ReactionLikeService.java`
- MQ 发布实现：`InteractionNotifyEventPort`
  - `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/InteractionNotifyEventPort.java`
- 消费端：`InteractionNotifyConsumer`
  - `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/InteractionNotifyConsumer.java`

通知消费者做三件事：

1) inbox 去重（`interaction_notify_inbox`）
2) 解析目标归属（POST 就查 post，COMMENT 就查 comment）
3) 对 `interaction_notification` 做一条 upsert 累加 unread

### 11.2 评论点赞：额外回写 comment.like_count + 刷热榜

当 targetType=COMMENT 时，`InteractionService.react` 会把 `delta` 发给评论派生链路：

- 触发位置：`InteractionService.react`
  - `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/InteractionService.java`
- 事件消费者：`CommentLikeChangedConsumer`
  - `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/CommentLikeChangedConsumer.java`

这条链路也有 inbox 去重（`interaction_comment_inbox`），避免重复累加：

- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/InteractionCommentInboxPort.java`

### 11.3 Feed 铁粉集合：复用 LIKE_ADDED（仅 POST）

这也是“点赞会触发的副作用”，但它只关心“点赞帖子”：

- `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FeedCoreFansConsumer.java`

---

## 12) 本地跑通（最小冒烟验证）

### 12.1 依赖准备（dev 配置）

看 dev 配置你就知道要起哪些东西：

- `project/nexus/nexus-app/src/main/resources/application-dev.yml`

最小集合：

- MySQL（schema 需要手动执行，`spring.sql.init.mode: never`）
  - DDL：`project/nexus/docs/social_schema.sql`
- Redis
- RabbitMQ（要装 `x-delayed-message` 插件，否则延迟队列不生效）

JD hotkey / etcd：没有也能跑，只是热点 L1 优化会退化（代码里有 try/catch）。

### 12.2 手动请求示例

1) 点赞帖子（POST）

```bash
curl -X POST "http://127.0.0.1:8080/api/v1/interact/reaction" ^
  -H "Content-Type: application/json" ^
  -H "X-User-Id: 10001" ^
  -d "{\"targetId\":90001,\"targetType\":\"POST\",\"type\":\"LIKE\",\"action\":\"ADD\"}"
```

2) 查询状态

```bash
curl "http://127.0.0.1:8080/api/v1/interact/reaction/state?targetId=90001&targetType=POST&type=LIKE" ^
  -H "X-User-Id: 10001"
```

### 12.3 验收点（你要看什么才算“跑通”）

1) HTTP 返回 `success=true`，并且 `currentCount` 随点赞变化  
   - `InteractionController.react` 会把 `ReactionResultVO` 回给前端
2) Redis 出现这些 key（以 `{POST:90001:LIKE}` 为例）
   - `interact:reaction:set:{POST:90001:LIKE}`
   - `interact:reaction:cnt:{POST:90001:LIKE}`
   - `interact:reaction:ops:{POST:90001:LIKE}`（窗口内会有）
3) 过一段时间（默认 5 分钟窗口）后，MySQL 两张表有数据
   - `interaction_reaction`（事实）
   - `interaction_reaction_count`（派生）

如果 MQ/同步没跑起来，优先看：

- `ReactionSyncDelayConfig` 的交换机/队列是否声明成功
- RabbitMQ 是否支持 `x-delayed-message`
- `ReactionSyncConsumer` 是否在消费队列 `reaction.sync.delay.queue`

---

## 13) 排障与对账（定位问题最快的办法）

### 13.1 在线写日志（结构化 JSON）

`ReactionLikeService.applyReaction` 会打印一条 `event=reaction_like` 的 JSON：

- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/ReactionLikeService.java`（`buildEventJson` + `log.info(...)`）

排障时你重点盯：

- `requestId`（链路串起来）
- `delta`（到底有没有真的变更）
- `firstPending`（为什么没投递同步）

### 13.2 数据对账的“真相顺序”

1) 真实 state：Redis set（`interact:reaction:set:*`）
2) 近实时 count：Redis cnt（`interact:reaction:cnt:*`，坏了可重建）
3) 最终落库事实：MySQL `interaction_reaction`
4) 最终落库计数：MySQL `interaction_reaction_count`（派生）

---

## 14) 未来扩展（如果要支持 LOVE/ANGRY，需要改哪里？）

现在只支持 LIKE，是在 domain 层硬卡住的：

- `ReactionLikeService.requireLikeOnly`

如果未来要扩展：

1) 扩展 API 层允许传 `type=LOVE/ANGRY`
2) domain 层不再 “like only”，而是把服务抽成 `ReactionService` 或者按 type 路由
3) Redis key 的 hashTag 已经包含 reactionType，不会串
4) DB 表结构也已经包含 `reaction_type`，事实与计数都能复用

对应代码起点：

- `ReactionTypeEnumVO`
- `ReactionTargetVO.hashTag()`
- `interaction_reaction` / `interaction_reaction_count` 的主键设计（含 reaction_type）

