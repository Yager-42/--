# 点赞业务实现方案（严格对齐原文：解法 2 + 解法 3）

日期：2026-01-16  
执行者：Codex（Linus-mode）  
原文摘录：`.codex/source-wechat-like-solutions-2-3.md`

本方案的目标很简单：把原文的“解法 2（WAL + 智能聚合）”和“解法 3（热点探测 + 本地缓存 + Redis + TaiShan KV + TiDB + TiCDC）”按原意落成一份可直接参考实现的规格文档。之前版本对解法 3 做了改写（去掉热点探测、把 L3 改成普通 DB），这不符合你的要求；这版已修正为“按原文意思”。

## 1. 组件清单（按原文角色命名）

必选（对应原文解法 2 + 解法 3）：

- HotKey Detector：京东 hotkey detector（热点探测；实时统计流量，识别热 Key）
- L1 本地缓存：Caffeine（开启 Window-TinyLfu 淘汰策略；只对“已识别热点 Key”启用）
- L2 分布式缓存：Redis Cluster
- L3 核心存储：TaiShan KV（原文角色；如果你没有 TaiShan，就用“等价的高性能 KV 存储”替代，但语义保持一致）
- L3 关系型存储：TiDB（原文角色；用于承载聚合结果/关系数据）
- TiCDC：用于“数据库与缓存一致性同步”，避免业务层双写
- MQ：用于“异步落库通道”（Kafka / RocketMQ / Pulsar 任选其一都行；注意它的角色是 MQ，不是本地 WAL）
- Like Service：对外提供点赞 API 的无状态服务
- Like Aggregator：应用内/独立进程均可，负责内存聚合 + 本地 WAL + 动态窗口 flush
- Like Persist Worker：消费 MQ 的落库服务（写 TiDB + 写 TaiShan KV）

## 2. 核心数据（按原文含义拆分）

原文里其实隐含两类“必须被持久化”的数据：

1) 点赞状态（用于 Exists 查询：某用户是否点过赞）  
2) 点赞数（用于展示：某对象有多少赞）

### 2.1 LikeState（点赞状态，Exists 查询）

存储：L3 TaiShan KV（原文给了 Key 设计）

- Key 设计（原文）：`{业务类型}{实体ID}{用户ID}`  
  - 推荐加分隔符：`{targetType}:{targetId}:{userId}`
- Value：可只存 `1`（表示点赞），也可存 `{state, updatedAt}`（实现选择，不影响原文语义）

用途：

- `GET /like/state`：用 Exists 查询即可（吞吐高、延迟低）

### 2.2 LikeCount（点赞数，展示用）

存储：L3 TiDB（原文写的是 “TaiShan KV + TiDB”，这里把“计数”放 TiDB 是最贴近原文的落地方式）

- 主键：`(targetType, targetId)`
- 字段：`count`（非负整数）、`updatedAt`

用途：

- `GET /like/count`：优先走缓存；必要时回源 TiDB
- TiCDC：从 TiDB 把变更同步到 Redis（避免业务层双写）

### 2.3 本地 WAL（解法 2 的关键：防止宕机丢内存聚合数据）

存储：每个 Like Aggregator 实例的本地磁盘（append-only 文件即可）

- 记录内容：最小要包含 `userId、targetType、targetId、desiredState、ts`  
- 写入顺序：先写入内存聚合态（hold），再写 WAL 成功，最后对外返回成功（否则“防丢”就是假的）

## 3. 对外接口（只写必须的）

### 3.1 设置点赞状态（写接口）

`POST /like/set`

请求：

- `userId`
- `targetType`
- `targetId`
- `desiredState`：1=点赞，0=取消点赞

响应：

- `accepted`：true/false（表示本地 WAL 写入成功并进入聚合流程）
- `serverTs`

注意：这里的成功语义严格对齐原文——成功不是“DB 已更新”，而是“写入链路已可靠接住（WAL）”。

### 3.2 查询点赞状态（读接口）

`GET /like/state?userId=...&targetType=...&targetId=...`

响应：

- `state`：0/1（Exists 即 1）

### 3.3 查询点赞数（读接口）

`GET /like/count?targetType=...&targetId=...`

响应：

- `count`

## 4. 解法 2：写入链路（内存聚合 + 本地 WAL + 动态窗口 + MQ 异步落库）

原文逻辑顺序必须保持一致：

1) 写内存（hold）  
2) WAL 本地日志（关键）  
3) 动态窗口聚合（1s - 10s）  
4) 异步落库（MQ）

### 4.1 写内存（应用层 hold）

聚合器维护窗口内状态（按对象聚合）：

- `buffer[targetKey][userId] = desiredState`（同一用户窗口内多次操作，以最后一次为准）

这等价于原文的“记录用户 ID 用于去重”。

### 4.2 WAL 本地日志（关键）

每次请求：

- 先写入内存 buffer（hold）
- 再 append 一条 WAL（本地日志）
- WAL 写入成功后立即返回 `accepted=true`

WAL 的作用（对齐原文）：服务宕机时，内存里未合并/未 flush 的数据可通过 WAL 回放恢复，避免丢失。

### 4.3 动态窗口聚合（1s - 10s）

窗口大小由“当前流量水位”决定（原文范围 1s - 10s）：

- 流量高：窗口更小（更实时）
- 流量低：窗口更大（更省 IO）

### 4.4 flush 生成聚合消息（发送 MQ）

flush 时把窗口内的数据打包成“聚合写入消息”发送到 MQ：

- `targetType, targetId`
- `userStateChanges`：窗口内去重后的 `(userId -> desiredState)` 集合
- `windowEndTs`

高层伪代码（保持原文语义，不纠结具体实现语言）：

```pseudocode
onLikeSet(req):
  buffer[target(req)][req.userId] = req.desiredState
  wal.append(req)             // 本地 WAL（关键）
  return accepted=true

onWindowFlush(target):
  changes = buffer[target]    // userId -> desiredState（已去重）
  MQ.publish({target, changes, windowEndTs})
  clear buffer[target]
```

### 4.5 异步落库（Persist Worker）

Persist Worker 消费 MQ 消息，完成持久化：

- 写 LikeState 到 TaiShan KV（Exists 查询底座）
- 更新 LikeCount 到 TiDB（聚合结果）

关键点：去重/幂等一定要以“userId”为维度做，否则计数会悄悄算错。

## 5. 解法 3：读取 + 存储（热点探测 → L1 本地缓存 → L2 Redis → L3 TaiShan KV/TiDB）

原文给出的顺序是：

- 热点探测（HotKey Detector）识别热 Key  
- 识别到热点后，才启用 L1 本地缓存（Caffeine，Window-TinyLfu）并“直接返回，连 Redis 都不用查”  
- 常规流量走 L2 Redis Cluster  
- 海量数据落在 L3 TaiShan KV + TiDB  
- 一致性用 TiCDC 做同步，而不是业务双写  
- 体验兜底：前端乐观更新

### 5.1 热点探测（HotKey Detector）

实现选型：京东 hotkey detector。  
本方案只依赖它的“输出语义”：应用节点能够判断 `targetKey` 是否热点（例如提供 `isHotKey(targetKey)` 或等价的本地热点集合）。

输出：一份“热点 key 列表/集合”（例如 `targetType:targetId`）。

应用节点行为（严格按原文）：

- 只有当 `targetKey` 被标记为热点时，才允许走 L1 本地缓存直返

### 5.2 点赞数读取（count）

读取顺序（对齐原文）：

1) 如果 `targetKey` 是热点：读 L1 Caffeine，命中直接返回（绕过 Redis）  
2) 否则（或 L1 未命中）：读 L2 Redis  
3) Redis 未命中：回源 L3 TiDB 读 LikeCount  

回填：

- 回填 Redis：只由 TiCDC 同步（避免业务层双写，按原文）
- 热点 key 可回填 L1（让后续请求直返）

### 5.3 点赞状态读取（state）

原文给的核心能力是：L3 TaiShan KV 支持高吞吐 Exists 查询（判断用户是否点赞）。

因此 `GET /like/state` 的默认实现就是：

- 去 TaiShan KV 做 Exists（key：`{targetType}:{targetId}:{userId}`）

### 5.4 TiCDC 同步（避免业务双写）

原文明确要求：

- “数据库与缓存之间的数据一致性，通过 TiCDC 来实现，而不是业务层双发”

落地方式：

- LikeCount 写入 TiDB 后，由 TiCDC 订阅变更并同步更新 Redis 的 `like:cnt:*`

### 5.5 乐观更新（Optimistic UI）

原文含义：

- 用户点赞后，前端按钮立即变红  
- 后端只要“可靠接住（WAL）”，就可以让用户先看到成功；真正持久化与缓存更新在后台完成

## 6. 最小验收测试（让另一个 agent 不踩坑）

### 6.1 解法 2（写入）

- WAL 防丢：写入后立刻 kill 服务进程，重启后能从 WAL 回放并继续 flush，最终落库不丢  
- 去重：同一用户窗口内多次操作，只以最后一次为准（窗口 flush 后状态正确）  
- 动态窗口：能在 1s~10s 间调整（策略可简化，但范围要覆盖）

### 6.2 解法 3（读取/热点）

- 热点探测生效：key 被标记为热点后，请求命中 L1 并绕过 Redis（可通过埋点/日志验证）  
- 常规流量：未标记热点时不走 L1，直接走 Redis  
- 回源正确：Redis miss 时能回源 TiDB（count）/TaiShan KV（state）
