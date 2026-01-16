# 点赞业务实现说明书（可照抄实施）

日期：2026-01-16  
执行者：Codex（Linus-mode）  
输入：点赞链路流程图（用户截图）+ `社交接口.md` + `.codex/DDD-ARCHITECTURE-SPECIFICATION.md` + 现有代码  
目标：让另一个不了解项目的 Codex agent 按步骤照抄也能把点赞链路完整实现。

---

## 0. 一句话目标（给 12 岁也能懂）

用户点“赞/取消赞”时：

1) **立刻返回**：按钮马上变红/变灰，并拿到“当前点赞数”。
2) **后台慢慢对齐**：过一会儿把数据写进数据库，最终一致。
3) **还能发现热点**：哪个帖子突然爆火，系统要能看见并报警。

---

## 1. 需求理解确认（实现不得偏离）

基于现有信息，我理解你的需求是：

- 你已经有一张“点赞/取消点赞”的链路流程图。
- 你要我把它落成一个 **DDD 分层** 的实现方案（Java Spring Boot + Maven 多模块）。
- 方案要和现有接口对齐：`POST /api/v1/interact/reaction`（见 `社交接口.md` / 代码）。
- 你允许我对接口做小幅调整（但不能意外破坏现有调用）。
- 你允许使用标准生态组件（例如 JD HotKey Detector）。
- 你要的交付物是一份“像说明书一样”的新文档：步骤细、可照抄、无关内容不写。

请确认我的理解是否准确？（你已经说“继续”，我将默认你认可并直接开干。）

---

## 2. Linus 五层分析（只说关键点）

### 2.1 数据结构分析（核心）

点赞系统里只有两份核心数据：

- **事实（Like/Reaction State）**：`(userId, targetType, targetId, reactionType)` 是否存在。
- **派生（Like/Reaction Count）**：`(targetType, targetId, reactionType)` 的总数。

关系：计数 = 对事实求和。  
工程现实：计数不能每次现算，所以要维护一份近实时聚合值。

### 2.2 特殊情况识别（把 if/else 砍掉）

最容易写烂的地方：

- “toggle 反转状态” = 制造垃圾边界情况（重复请求/重试会把计数算飞）。

解决：

- 只接受 **set state**：ADD=1 / REMOVE=0。
- 幂等靠数据结构：Redis Set 的 `SADD/SREM` 返回值就是天然幂等。

### 2.3 复杂度审查（别发明新概念）

本质一句话：

- **把高频写先写进 Redis（很快），再用延迟队列把“脏数据”批量落库（很省）。**

多余的概念不要加：

- 不做强一致。
- 不做复杂回退。
- 不做安全相关东西（鉴权/限流/风控）。

### 2.4 破坏性分析（Never break userspace）

不能破坏的用户可见行为：

- 现有接口 `POST /api/v1/interact/reaction` 仍可用。
- 请求字段 `target_id/target_type/type/action` 语义不变。
- 返回 `current_count` 仍然返回一个数字（来自 Redis 近实时计数）。

### 2.5 实用性验证（流程图就是生产现实）

流程图里的组件链路（Redis + 延迟队列 + DB + 日志/实时/离线/监控）是典型生产套路：

- ✅ 值得做：这是“高并发写 + 热点聚集”的标准难题。

---

## 3. 结论：✅ 值得做

原因（不超过 3 条）：

1) 点赞是最高频交互之一，写入/读取都容易爆。
2) 热点集中，必须有“写轻 + 异步聚合 + 监控告警”。
3) 现有代码点赞还只是占位，实现空间清晰。

---

## 4. 现状对齐（你必须先看懂现在是什么）

### 4.1 已存在的接口

- 文档：`社交接口.md`（4.3 核心接口定义）
- 代码入口：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/InteractionController.java`

当前接口：

- `POST /api/v1/interact/reaction`
  - 请求：`target_id, target_type, type, action`
  - 响应：`current_count, success`

### 4.2 已存在的占位实现（你要替换掉）

- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/InteractionService.java`
  - `react()` 目前只是在 action=ADD 时返回 1，否则返回 0 —— 这当然是垃圾占位。

### 4.3 已存在的延迟队列范式（直接复用）

- RabbitMQ 延迟交换机（x-delayed-message）：
  - `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/config/ContentScheduleDelayConfig.java`
- Producer：`ContentScheduleProducer`
- Consumer：`ContentScheduleConsumer`

点赞链路会照抄同样模式，只是换一套 exchange/queue/routing。

---

## 5. 核心数据结构（别把数据结构搞错）

### 5.1 LikeTarget（被点赞对象）

一个“点赞目标”由 3 个维度唯一确定：

- `targetType`：目标类型（`POST` / `COMMENT`）
- `targetId`：目标 ID
- `reactionType`：态势类型（本说明书把 `LIKE` 当“点赞”，其余 `LOVE/ANGRY` 先不做）

### 5.2 事实（真相）：`interaction_reaction`

回答的问题：**“某用户有没有对某对象点过赞？”**

- 唯一键：`(target_type, target_id, reaction_type, user_id)`
- 这张表一旦写对，后面所有统计都只是派生。

### 5.3 派生（聚合）：`interaction_reaction_count`

回答的问题：**“这个对象一共有多少赞？”**

- 主键：`(target_type, target_id, reaction_type)`
- `count` 是派生值，允许短时间不准（最终一致）。

### 5.4 Redis 与 DB 的分工（必须分清）

- Redis：负责“快”和“抗压”
  - 近实时计数（给接口立即返回）
  - 去重（幂等）
  - 脏数据缓冲（等延迟队列来结算）
- DB：负责“最终正确”和“可追溯”
  - 最终的事实表与聚合表

### 5.5 数据不变量（写错这里，系统就会悄悄算错）

- `action=ADD` 表示 **desiredState=1**；`action=REMOVE` 表示 **desiredState=0**。
- 重复 ADD 不得让计数一直 +1；重复 REMOVE 不得让计数一直 -1。
- `count` 不得小于 0（Redis 侧必须避免 DECR 到负数）。

---

## 6. Redis Key 设计（Redis Cluster 必须这么写）

### 6.1 为什么必须用 hash-tag

你的流程里需要两件事：

- 在 Redis 里用 Lua 做“多 key 原子更新”（计数 + 去重 + 记录脏数据）。
- 在延迟同步时用 `RENAME` 把脏数据快照出来（避免并发丢更新）。

在 Redis Cluster 下：**跨 slot 的 Lua/RENAME 会直接报错。**

所以我们强制所有与某个 target 相关的 key 共享同一个 hash-tag：

- `tag = {<targetType>:<targetId>:<reactionType>}`

### 6.2 Key 列表（实现照抄）

- 去重集合（事实的 Redis 版本）：`interact:reaction:set:{tag}`（Redis Set，成员=userId）
- 近实时计数：`interact:reaction:cnt:{tag}`（Redis String，值=count）
- 脏操作缓冲：`interact:reaction:ops:{tag}`（Redis Hash，field=userId，value=0/1）
- 脏操作快照：`interact:reaction:ops:processing:{tag}`（Redis Hash）
- 同步标记：`interact:reaction:sync:{tag}`（String：PENDING/SYNCING）
- 最后同步时间：`interact:reaction:last_sync:{tag}`（String：epochMillis）
- 动态窗口（可选）：`interact:reaction:window_ms:{tag}`（String：毫秒）
- 同步互斥锁：`interact:reaction:lock:{tag}`（String：uuid，带 TTL）

建议 TTL（按默认 5min 窗口）：

- `sync`：10 分钟（>= 延迟时间，防止 backlog 误删）
- `lock`：60 秒（够一次批处理）

---

## 7. 数据库表结构（DDL 已追加到仓库文档）

已追加到：`project/nexus/docs/social_schema.sql`

### 7.1 表：`interaction_reaction`（事实）

- 主键：`(target_type, target_id, reaction_type, user_id)`
- 作用：DB 里的真相（用户是否点赞）。

### 7.2 表：`interaction_reaction_count`（派生）

- 主键：`(target_type, target_id, reaction_type)`
- 字段：`count`（最终一致的聚合数）

注意：

- 写入链路不要同步更新 DB（会炸），DB 更新交给“延迟队列结算”。
- count 表直接用 Redis 的 count 覆盖即可（流程图第 13 步）。

---

## 8. 全链路总览（把流程图翻译成人话）

你给的流程图可以拆成 4 条链路：

1) **在线写入链路（用户立刻看到结果）**
2) **延迟落库链路（最终一致）**
3) **实时监控链路（发现热点并告警）**
4) **离线分析链路（日报/周报/月报）**

对应流程图编号（对齐用）：

- 1-5：在线写入（Redis 原子更新 + sync_flag + 延迟消息）
- 6-12：实时监控（结构化日志 -> Kafka -> Flink -> 告警/热榜/Hive）
- 13-17：延迟落库（把 Redis 的“脏数据”批量写入 DB）
- 18-21：离线分析（Hive -> Spark -> 热点推荐/趋势报表）

---

## 9. DDD 分层落地（按现有项目结构照抄）

> 目标：不改现有对外接口，只把占位实现替换成真正的点赞链路。

### 9.1 你要改/新增的模块

- `nexus-api`：保持 `ReactionRequestDTO/ReactionResponseDTO` 不变（可选新增 requestId，但必须可选）。
- `nexus-domain`：新增“点赞子域”服务 + 端口接口（domain 不直接依赖 Redis/Rabbit/MyBatis）。
- `nexus-infrastructure`：实现 Redis 端口 + MyBatis 仓储（落库）。
- `nexus-trigger`：实现 RabbitMQ 延迟队列（config/producer/consumer）。

### 9.2 建议新增的包与类（照抄文件名）

domain（建议放在 `cn.nexus.domain.social.like` 下，保持短类/低缩进）：

- `adapter/port/IReactionCachePort`：Redis 原子写 + 快照读取
- `adapter/port/IReactionDelayPort`：投递延迟消息
- `adapter/repository/IReactionRepository`：批量 upsert/delete
- `model/valobj/ReactionTargetVO`：targetType/targetId/reactionType
- `service/IReactionLikeService` + `ReactionLikeService`：在线写 + sync 聚合

trigger（建议放在 `cn.nexus.trigger.mq.*` 下）：

- `mq/config/ReactionSyncDelayConfig`
- `mq/producer/ReactionSyncProducer`
- `mq/consumer/ReactionSyncConsumer`

---

## 10. 接口契约（保持现有接口不破坏）

### 10.1 写接口：`POST /api/v1/interact/reaction`

请求（现状）：

- `targetId`：Long
- `targetType`：String（`POST` / `COMMENT`）
- `type`：String（本方案只处理 `LIKE`）
- `action`：String（`ADD` / `REMOVE`）

响应（现状）：

- `currentCount`：Long（来自 Redis 近实时计数）
- `success`：boolean

### 10.2 语义约束（强制）

- 这是 **set state**，不是 toggle：
  - `ADD` 等价 `desiredState=1`
  - `REMOVE` 等价 `desiredState=0`
- `currentCount` 是“体验值”，允许与 DB 短暂不一致。

### 10.3 可选增强（不破坏用户）

如果你想要更好排查问题，可以在请求里加一个可选字段：

- `requestId`（String，可选）

注意：必须可选，旧客户端不传也能跑。

---

## 11. 在线写入链路（API -> Redis 原子更新 -> 延迟消息）

### 11.1 目标

做到两件事：

- 用户请求到达后 **立即返回**（只做 Redis + 可选 MQ 投递）。
- 同一个用户重复请求不产生副作用（幂等）。

### 11.2 写入的最小步骤（对应流程图 1-5）

1) 计算 `tag={targetType:targetId:reactionType}`。
2) 调用 Redis Lua：
   - Set 去重（SADD/SREM）
   - Count 计数（INCR/DECR，只在集合变化时）
   - ops 记录（HSET userId->desiredState）
   - sync_flag（SETNX：仅首次置 pending）
3) 如果本次 **首次置 pending**：发送 RabbitMQ 延迟消息（默认 5 分钟）。
4) 打一条结构化日志（给实时监控链路用）。
5) 返回 `currentCount`。

### 11.3 领域服务伪代码（照抄就能写）

> 建议实现：`ReactionLikeService.applyReaction()`，由 `InteractionService.react()` 委托调用。

```pseudocode
applyReaction(userId, targetId, targetType, reactionType, action):
  assert reactionType == 'LIKE'           // 先只做 LIKE
  desiredState = (action == 'ADD') ? 1 : 0

  target = ReactionTargetVO(targetType, targetId, reactionType)

  // 1) Redis 原子更新
  res = reactionCachePort.applyAtomic(userId, target, desiredState)
  // res: {currentCount, delta, firstPending}

  // 2) 首次 pending 才投递延迟消息
  if res.firstPending:
    delayMs = reactionCachePort.getWindowMs(target, default=300000)
    reactionDelayPort.sendDelay(target, delayMs)

  // 3) 结构化日志（给 Logstash/Kafka/Flink 用）
  logJson({
    event: 'reaction_like',
    userId, targetType, targetId, reactionType,
    action, desiredState,
    delta: res.delta,
    currentCount: res.currentCount,
    firstPending: res.firstPending,
    ts: now()
  })

  return ReactionResultVO(currentCount=res.currentCount, success=true)
```

### 11.4 Redis Lua 伪代码（原子更新，必须同 slot）

> 实现位置建议：infrastructure 的 `ReactionCachePort` 里，用 `StringRedisTemplate.execute()` 执行 Lua。

输入：`userId, desiredState(0/1), syncTtlSec`  
Keys（全部必须带同一个 `{tag}`）：

- `setKey = interact:reaction:set:{tag}`
- `cntKey = interact:reaction:cnt:{tag}`
- `opsKey = interact:reaction:ops:{tag}`
- `syncKey = interact:reaction:sync:{tag}`

```pseudocode
luaApplyAtomic(keys, argv):
  userId = argv[1]
  desiredState = argv[2]  // '1' or '0'
  syncTtlSec = argv[3]

  delta = 0

  if desiredState == '1':
    added = SADD(setKey, userId)
    if added == 1:
      INCR(cntKey)
      delta = +1
  else:
    removed = SREM(setKey, userId)
    if removed == 1:
      DECR(cntKey)
      delta = -1

  // 记录“最后状态”，延迟落库时按 userId 覆盖即可
  HSET(opsKey, userId, desiredState)

  // 只在首次置 pending 时返回 true，避免重复投递延迟消息
  firstPending = SET(syncKey, 'PENDING', NX, EX=syncTtlSec)

  current = GET(cntKey)
  if current is null: current = '0'

  return {currentCount=current, delta=delta, firstPending=(firstPending == 'OK')}
```

注意：

- 幂等来自 `SADD/SREM` 的返回值，不要自己发明 if/else。
- 这里不写 DB，不写 MQ，只做 Redis。

---

## 12. 延迟落库链路（延迟队列 -> 批量写 DB，最终一致）

### 12.1 目标（对应流程图 13-17）

- 把 Redis 里累计的“脏操作”（ops）批量落库到 `interaction_reaction`。
- 把 Redis 里的 `cnt` 同步到 `interaction_reaction_count`。
- 清理 sync_flag，记录 last_sync_time。

### 12.2 延迟消息模型（照抄 ContentSchedule）

你需要一套新的 RabbitMQ 延迟队列配置，结构与 `ContentScheduleDelayConfig` 一模一样：

- Exchange：`reaction.sync.exchange`
- Queue：`reaction.sync.delay.queue`
- RoutingKey：`reaction.sync.delay`
- DLX Exchange：`reaction.sync.dlx.exchange`
- DLX Queue：`reaction.sync.dlx.queue`
- DLX RoutingKey：`reaction.sync.dlx`

消息 payload（最简单可用）：

- JSON：`{targetType, targetId, reactionType}`

### 12.3 Consumer 入口伪代码（触发同步）

> 位置建议：`cn.nexus.trigger.mq.consumer.ReactionSyncConsumer`

照抄现有 `ContentScheduleConsumer` 的三件套：

- Redis 分布式锁（避免重复消费并发写 DB）
- try/catch + DLQ（失败就丢到死信，别吞）
- finally 解锁

```pseudocode
onMessage(targetJson):
  target = parse(targetJson)
  lockKey = 'interact:reaction:lock:' + tag(target)
  lockVal = uuid()

  if !SETNX(lockKey, lockVal, ttl=60s):
    return // 已有同步在跑

  try:
    reactionLikeService.syncTarget(target)
  catch e:
    sendToDLQ(targetJson)
    throw
  finally:
    if GET(lockKey) == lockVal: DEL(lockKey)
```

### 12.4 领域同步伪代码（消除并发丢数据：RENAME 快照）

> 关键点：不能“读 opsKey -> 处理 -> DEL opsKey”。并发写会被你删掉。
> 正确姿势：`opsKey -> processingKey` 的原子快照（同一个 `{tag}` 下才能做）。

```pseudocode
syncTarget(target):
  tag = tag(target)

  opsKey = 'interact:reaction:ops:' + tag
  processingKey = 'interact:reaction:ops:processing:' + tag
  cntKey = 'interact:reaction:cnt:' + tag
  syncKey = 'interact:reaction:sync:' + tag
  lastSyncKey = 'interact:reaction:last_sync:' + tag

  // 1) 快照：把 opsKey 原子挪走
  moved = reactionCachePort.renameOpsIfExists(opsKey, processingKey)
  if !moved:
    DEL(syncKey)
    return

  // 2) 读取快照
  ops = HGETALL(processingKey)        // userId -> '0'/'1'

  addUserIds = []
  removeUserIds = []
  for each (userId, desiredState) in ops:
    if desiredState == '1': addUserIds.add(userId)
    else: removeUserIds.add(userId)

  // 3) 批量落库（事实）
  reactionRepository.batchUpsert(target, addUserIds)
  reactionRepository.batchDelete(target, removeUserIds)

  // 4) 同步计数（派生）
  count = GET(cntKey) or 0
  reactionRepository.upsertCount(target, count)

  // 5) 清理标记
  DEL(processingKey)
  SET(lastSyncKey, nowMillis())
  DEL(syncKey)

  // 6) 如果又有新 ops（同步期间有人继续点赞），重新投递
  if EXISTS(opsKey):
    delayMs = reactionCachePort.getWindowMs(target, default=300000)
    SET(syncKey, 'PENDING', EX=600s)
    reactionDelayPort.sendDelay(target, delayMs)
```

### 12.5 renameOpsIfExists 的 Lua 伪代码（必须原子）

```pseudocode
luaRenameOps(keys):
  opsKey = keys[1]
  processingKey = keys[2]

  if EXISTS(opsKey) == 0:
    return 0

  // 保护：如果 processingKey 还在，说明上次同步没清干净，直接拒绝覆盖
  if EXISTS(processingKey) == 1:
    return 0

  RENAME(opsKey, processingKey)
  return 1
```

这段必须在 Lua 里做：不然你会在 EXISTS/RENAME 之间被并发插队。

### 12.6 DB 批量写入的 SQL 形态（MyBatis 直接照抄）

> 重点：**批量**。别一条条写，DB 会被你打爆。

Upsert（ADD=1）：

```sql
INSERT INTO interaction_reaction(target_type, target_id, reaction_type, user_id, create_time, update_time)
VALUES
  (?, ?, ?, ?, NOW(), NOW()),
  ...
ON DUPLICATE KEY UPDATE update_time = VALUES(update_time);
```

Delete（REMOVE=0）：

```sql
DELETE FROM interaction_reaction
WHERE target_type = ? AND target_id = ? AND reaction_type = ?
  AND user_id IN ( ... );
```

Upsert Count（直接覆盖 Redis count）：

```sql
INSERT INTO interaction_reaction_count(target_type, target_id, reaction_type, count, update_time)
VALUES (?, ?, ?, ?, NOW())
ON DUPLICATE KEY UPDATE
  count = VALUES(count),
  update_time = VALUES(update_time);
```

建议：每次批量最多 500 个 userId，超了就分批（别折磨 MySQL）。

---

## 13. 实时监控链路（Logstash -> Kafka -> Flink -> 告警/热榜/Hive）

> 这条链路是“旁路”：不影响点赞主链路的响应时间。

### 13.1 结构化日志契约（对应流程图 6）

在在线写入成功后，必须打印一条 JSON 日志（建议 event 固定为 `reaction_like`）：

```json
{
  "event": "reaction_like",
  "ts": 1736990000000,
  "userId": 10001,
  "targetType": "POST",
  "targetId": 90001,
  "reactionType": "LIKE",
  "action": "ADD",
  "desiredState": 1,
  "delta": 1,
  "currentCount": 1234,
  "firstPending": true
}
```

字段解释：

- `delta`：本次是否真的改变了集合（1/-1/0），用于 Flink 聚合。
- `currentCount`：Redis 的近实时计数，用于监控对齐与排查。

### 13.2 Logstash -> Kafka（对应流程图 7）

目标：把应用日志里的 `event=reaction_like` 抽出来，写入 Kafka：

- topic：`topic_like_monitor`

Logstash 思路（伪配置，照着写即可）：

```pseudocode
input: tail application log
filter:
  parse json
  if event != 'reaction_like': drop
output:
  kafka { topic => 'topic_like_monitor' }
```

注意：

- 主链路不直接写 Kafka（会把接口延迟拖死）。
- Kafka 只是监控/分析链路的 WAL，与业务 DB 无关。

### 13.3 Flink 实时计算（对应流程图 8-12）

Flink 输入：Kafka `topic_like_monitor` 里的 JSON 事件。

计算（最小可用）：

- key：`(targetType, targetId, reactionType)`
- window：5 分钟滚动窗口（tumbling 5m）
- 指标：`like_add_count = sum(delta == 1 ? 1 : 0)`

输出 3 份结果：

1) **热点告警**（流程图 10）
   - 规则：`like_add_count > 2000`（阈值你可调）
   - 动作：写 Prometheus 指标 + Grafana 告警

2) **实时热点榜**（流程图 11）
   - 写 Redis ZSET：`hot:like:5m:{targetType}`
   - member：`targetId`
   - score：`like_add_count`

3) **离线明细入湖**（流程图 12）
   - 写 Hive（按小时分区）：`dwd_like_monitor_hourly(dt, hour)`
   - 字段：`target_type, target_id, reaction_type, like_add_count`

你不需要在本项目里实现 Flink/Hive，只要把“日志字段契约”和“输出 key/表结构”写死即可。

---

## 14. 离线分析链路（Hive -> Spark SQL -> 趋势报表/热点推荐）

对应流程图 18-21。

### 14.1 Hive 输入表（来自实时链路落地）

- `dwd_like_monitor_hourly(dt, hour)`
  - `target_type, target_id, reaction_type, like_add_count`

### 14.2 Spark 每日作业（最小可用）

每日跑一次：

- 计算昨日 TopN 热点：`sum(like_add_count)` 排序取前 10
- 计算“持续时长”：一个 target 连续多小时进入 TopN 的小时数
- 生成报表：日/周/月热点变化（趋势）

输出（例）：

- `ads_like_hot_daily(dt)`：`target_type, target_id, score,持续时长`

### 14.3 结果回写（给业务侧直接用）

- Redis ZSET：`hot:like:daily:{targetType}`
  - member：`targetId`
  - score：`score`

业务侧（Feed/推荐）只需要读这个 ZSET，就能拿到“昨日热点”。

---

## 15. （可选）热点治理：HotKey Detector + 本地缓存

你说可以用“京东 HotKey Detector”，那它的正确用途只有一个：

- **把超级热点 key 识别出来，让应用节点用本地缓存（Caffeine）扛读流量。**

最小落地方式：

1) Flink 写出的热点榜（Redis ZSET）就是一份“热点名单”。
2) 应用读到“热点名单”里的 target 时：
   - count 查询优先走 Caffeine（短 TTL，例如 1-3 秒）
   - miss 再查 Redis

注意：这不影响写链路，只是省 Redis 读。

---

## 16. 逐步实现清单（按这个顺序做，每一步都能验收）

> 你不需要一次写完。按步骤做，每一步都能跑通并验证。

### 16.1 四条链路 <-> 步骤对照（先对齐，你就不会觉得“对照不起来”）

| 链路 | 你要实现什么 | 对应步骤 | 这些步骤在哪里实现 |
| --- | --- | --- | --- |
| 链路 1：在线写入 | API -> Redis 原子去重+计数+写入 ops + 返回 currentCount | Step 0、Step 2-5、Step 8 | 本仓库（Java 代码） |
| 链路 2：延迟落库 | RabbitMQ 延迟触发 -> ops 快照 -> 批量写 DB -> 清标记 | Step 0、Step 1、Step 3-7 | 本仓库（Java 代码） |
| 链路 3：实时监控 | 日志 -> Logstash -> Kafka -> Flink -> 告警/热榜/Hive | Step 9-11 | Step 9 在本仓库；Step 10-11 属于外部系统 |
| 链路 4：离线分析 | Hive -> Spark SQL -> 报表/热点推荐 -> 回写 Redis | Step 12 | 外部系统（数据平台作业） |

一句话总结：

- **Step 0-8 = 把业务链路（链路 1 + 链路 2）做成可运行。**
- **Step 9-12 = 把数据平台链路（链路 3 + 链路 4）补齐为可交付契约 + 可验收项。**

### Step 0：准备本地依赖（最小可跑）

需要 3 个本地服务（因为主链路只依赖它们）：

- MySQL（用于最终落库）
- Redis（用于在线写入与缓冲）
- RabbitMQ（用于延迟队列）

项目已有占位配置：`project/nexus/nexus-app/src/main/resources/application-dev.yml`

验收：

- 应用能启动（不要求业务全通）。

### Step 1：创建 DB 表（事实 + 计数）

改动文件：

- `project/nexus/docs/social_schema.sql`（已追加）

你要做的事：

1) 在本地 MySQL 执行新增的两张表 DDL：
   - `interaction_reaction`
   - `interaction_reaction_count`

注意：`application-dev.yml` 里 `spring.sql.init.mode: never`，所以你必须手动执行 SQL。

验收：

- 两张表存在，主键/索引与 DDL 一致。

### Step 2：补齐领域值对象（把字符串变成可控枚举）

新增文件（建议位置与现有风格一致）：

- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/model/valobj/ReactionTargetVO.java`
- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/model/valobj/ReactionTypeEnumVO.java`
- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/model/valobj/ReactionActionEnumVO.java`
- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/model/valobj/ReactionTargetTypeEnumVO.java`

实现要点：

- 外部 API 仍然收 String（不破坏用户）。
- 进入 domain 后第一件事：把 String 解析成 EnumVO；解析失败直接返回参数错误。
- `ReactionTargetVO` 负责三元组：`targetType + targetId + reactionType`，并提供 `hashTag()`：
  - 输出 `{targetType:targetId:reactionType}`

验收：

- 任何非法的 `targetType/type/action` 都被拒绝（返回参数错误）。

### Step 3：定义 domain 端口接口（domain 不碰 Redis/Rabbit/MyBatis）

新增文件：

- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/adapter/port/IReactionCachePort.java`
- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/adapter/port/IReactionDelayPort.java`
- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/adapter/repository/IReactionRepository.java`
- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/model/valobj/ReactionApplyResultVO.java`

接口建议（伪代码签名）：

```pseudocode
IReactionCachePort:
  applyAtomic(userId, target, desiredState, syncTtlSec) -> ReactionApplyResultVO
  renameOpsIfExists(target) -> boolean
  readOpsSnapshot(target) -> map<userId, desiredState>
  getCount(target) -> long
  getWindowMs(target, defaultMs) -> long
  setSyncPending(target, ttlSec)
  clearSyncFlag(target)
  setLastSyncTime(target, epochMillis)

IReactionDelayPort:
  sendDelay(target, delayMs)
  sendToDLQ(rawMessage)

IReactionRepository:
  batchUpsert(target, userIds)
  batchDelete(target, userIds)
  upsertCount(target, count)
```

验收：

- domain 层代码里看不到 `StringRedisTemplate/RabbitTemplate/MyBatis` 这些实现细节。

### Step 4：实现 domain 服务（在线写 + 延迟同步）

新增文件：

- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/IReactionLikeService.java`
- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/ReactionLikeService.java`

必须实现 2 个方法：

1) `applyReaction(...)`：给 HTTP 写接口用（在线链路）
2) `syncTarget(target)`：给 MQ consumer 用（延迟落库链路）

实现要点：

- `applyReaction` 只做：Redis 原子更新 +（必要时）投递延迟消息 + 打日志。
- `syncTarget` 只做：ops 快照 + 批量落库 + 同步 count + 清标记 +（若又脏了）再投递。

验收：

- `applyReaction` 的耗时只跟 Redis/MQ 有关，不碰 DB。
- `syncTarget` 在并发点赞下不会丢更新（靠 rename 快照）。

### Step 5：实现 Redis 端口（Lua 原子更新 + rename 快照）

新增文件（infrastructure）：

- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/ReactionCachePort.java`

依赖：

- 复用项目已有 `spring-boot-starter-data-redis`（已在 infrastructure pom 里）

实现清单：

1) KeyBuilder：
   - 输入 `ReactionTargetVO`
   - 输出所有 key（必须带同一个 `{tag}`）
2) Lua#1：`applyAtomic`（见 11.4）
3) Lua#2：`renameOpsIfExists`（见 12.5）
4) 简单读写：`getCount/getWindowMs/setSyncPending/clearSyncFlag/setLastSyncTime`

验收：

- 在 Redis Cluster 环境下也能执行（所有 key 同 slot）。
- 重复 ADD/REMOVE 返回的 `delta` 正确（0/±1）。

### Step 6：实现 MyBatis 仓储（批量 upsert/delete + upsert count）

新增文件（DAO + PO + Mapper XML）：

- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/dao/social/IInteractionReactionDao.java`
- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/dao/social/IInteractionReactionCountDao.java`
- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/dao/social/po/InteractionReactionPO.java`
- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/dao/social/po/InteractionReactionCountPO.java`
- `project/nexus/nexus-infrastructure/src/main/resources/mapper/social/InteractionReactionMapper.xml`
- `project/nexus/nexus-infrastructure/src/main/resources/mapper/social/InteractionReactionCountMapper.xml`

新增文件（仓储实现）：

- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/ReactionRepository.java`

实现要点：

- 参考 `RelationRepository` 的风格：domain 实体/VO <-> PO 转换在 repository 内完成。
- 批量 SQL 形态见 12.6（foreach + 分批）。

验收：

- `batchUpsert/batchDelete/upsertCount` 在本地 MySQL 可执行。

### Step 7：实现 RabbitMQ 延迟队列（点赞同步触发器）

新增文件（trigger）：

- `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/config/ReactionSyncDelayConfig.java`
- `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/producer/ReactionSyncProducer.java`
- `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/ReactionSyncConsumer.java`

实现要点：

- 配置照抄 `ContentScheduleDelayConfig`（同样依赖 x-delayed-message 插件）。
- Producer 照抄 `ContentScheduleProducer`：用 header `x-delay` 设置延迟。
- Consumer 照抄 `ContentScheduleConsumer`：Redis 锁 + DLQ。

验收：

- 发一条延迟消息，能在延迟后被消费并触发 `syncTarget`。

### Step 8：把占位实现替换成真实链路（不改 Controller）

改动文件：

- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/InteractionService.java`

你要做的事：

- 在 `InteractionService` 注入 `IReactionLikeService`。
- `react()` 里：
  1) 把请求的 String 参数解析成 EnumVO/TargetVO
  2) 调 `reactionLikeService.applyReaction(...)`
  3) 把结果塞进 `ReactionResultVO` 返回

注意：

- `InteractionController` 不需要改（它只负责 HTTP 入口）。

验收：

- 调用 `POST /api/v1/interact/reaction` 返回的 `currentCount` 是 Redis 真实计数，而不是 0/1 占位。

### Step 9：把结构化日志“打出来”（链路 3 的入口，项目内可验收）

改动文件（任选其一做埋点即可）：

- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/ReactionLikeService.java`
- 或 `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/InteractionService.java`

你要做的事：

- 在 `applyReaction` 成功后打印一条 JSON 日志（字段对齐本文 13.1 的示例，至少要有 `event/ts/targetType/targetId/reactionType/delta/currentCount`）。

验收：

- 本地调用一次点赞接口，在 `logs/nexus.log`（或控制台）能看到 `event=reaction_like` 的 JSON 行。

### Step 10：把日志送进 Kafka（链路 3，外部系统交付物）

交付物（不在本仓库写 Java 代码）：

- Logstash pipeline：从应用日志中筛选 `event=reaction_like`，写入 Kafka topic `topic_like_monitor`。
- 配置模板：`project/nexus/docs/analytics/like-pipeline/logstash/topic_like_monitor.conf`
  - 你只要替换：`PATH_TO_NEXUS_LOG` / `SINCE_DB_PATH` / `KAFKA_BOOTSTRAP_SERVERS`
- 配置流程说明书：`project/nexus/docs/analytics/like-pipeline/README.md`

验收：

- Kafka 的 `topic_like_monitor` 能消费到 JSON 事件。

### Step 11：Flink 5min 窗口聚合 + 告警 + 热榜（链路 3，外部系统交付物）

交付物：

- Flink 作业：按 `(targetType,targetId,reactionType)` 分组，5 分钟窗口聚合 `sum(delta==1)`。
- 配置模板（不写 Java）：`project/nexus/docs/analytics/like-pipeline/flink/reaction_like_5m_agg.sql`
  - 先跑最小版本：Kafka 输入 `topic_like_monitor` -> Kafka 输出 `topic_like_5m_agg` / `topic_like_hot_alert`
- 输出（完整版/生产版）：
  - Prometheus 指标 + Grafana 告警（热点阈值）
  - Redis 热榜 ZSET：`hot:like:5m:{targetType}`
  - Hive 小时分区表：`dwd_like_monitor_hourly(dt,hour)`

验收：

- 最小版本：消费 `topic_like_5m_agg` 能看到聚合结果。
- 完整版：人为刷一个热点 target，能看到：告警触发 + Redis 热榜出现 + Hive 有小时记录。

### Step 12：Hive -> Spark 离线分析与回写（链路 4，外部系统交付物）

交付物：

- Hive DDL 模板：`project/nexus/docs/analytics/like-pipeline/hive/dwd_like_monitor_hourly.sql`
- Spark SQL 每日作业：基于 `dwd_like_monitor_hourly` 生成昨日 TopN 热点与趋势。
  - 模板：`project/nexus/docs/analytics/like-pipeline/spark/ads_like_hot_daily.sql`
- 回写 Redis：`hot:like:daily:{targetType}`（ZSET）。

验收：

- Hive 表能建出来（按 dt/hour 分区）。
- Spark SQL 跑完能得到昨日 TopN。
- （如果你做了回写）Redis 能查到昨日热点榜；业务侧（Feed/推荐）可直接消费。

---

## 17. 最小验证清单（本地 AI 自测用）

> 你只要验证 3 件事：幂等、最终一致、并发不丢。

### 17.1 功能正确性（幂等）

1) 对同一 `(userId, target)` 连续调用两次 `ADD`：
   - 第一次 `currentCount` +1
   - 第二次 `currentCount` 不变
2) 再调用一次 `REMOVE`：
   - `currentCount` -1
3) 再调用一次 `REMOVE`：
   - `currentCount` 不变

### 17.2 最终一致（延迟落库）

为了本地别等 5 分钟，建议开发环境把默认 delay 改成 3-5 秒：

- `reactionCachePort.getWindowMs(..., default=3000)`

验证：

- 延迟后 DB 表出现/消失对应行：`interaction_reaction`
- `interaction_reaction_count.count` 等于 Redis `cntKey`
- Redis 的 `syncKey` 被清理，`last_sync` 更新

### 17.3 并发不丢（核心）

模拟：在延迟同步进行时继续发点赞请求。

验证：

- 最终 DB 状态与 Redis set 一致（不会因为 DEL opsKey 丢数据）。
- 如果同步期间又产生新 ops，会自动再投递一次延迟消息。

---

## 18. 常见坑（踩了就会变垃圾）

- 你没用 hash-tag：Redis Cluster 下 Lua/RENAME 会直接报错。
- 你用了 toggle：重试/重复请求会把计数算飞。
- 你做了“读 opsKey 然后 DEL opsKey”：并发写会被你删掉（丢数据）。
- 你在 HTTP 主链路写 DB：高峰时 DB 会先死，你的接口也跟着死。
- 你让 count 当真相：计数永远只是派生值，真相是事实表/集合。

---

## 19. 交付物与下一步

- 新文档：`.codex/interaction-like-pipeline-implementation.md`
- 已更新：`.codex/context-scan.json`、`.codex/operations-log.md`、`project/nexus/docs/social_schema.sql`

如果你希望我下一步直接把代码也落地（把占位实现变成可运行），你只要回复：

- “继续落地代码”
