# zhiguang 计数系统详解

## 1. 总体定位

`zhiguang_be` 的计数系统是一个“事实层与汇总层分离”的设计。

- 事实层：Redis 位图，记录某个用户是否对某个实体执行过某个动作。
- 汇总层：Redis SDS 风格固定长度二进制字符串，保存各指标聚合值。
- 桥梁层：Kafka 事件，把位图状态变化转成异步增量。
- 补偿层：读时位图重建、关系计数重建、灾难回放、抽样校验。

它不是单纯的 `HINCRBY` 计数，而是把“是否发生过动作”和“聚合结果”拆开处理，核心目标是同时满足：

- 写入幂等
- 读性能稳定
- 异常可重建
- 支持高并发点赞/收藏/关注类操作

核心实现集中在：

- `zhiguang_be/src/main/java/com/tongji/counter/service/impl/CounterServiceImpl.java`
- `zhiguang_be/src/main/java/com/tongji/counter/event/CounterAggregationConsumer.java`
- `zhiguang_be/src/main/java/com/tongji/counter/service/impl/UserCounterServiceImpl.java`
- `zhiguang_be/src/main/java/com/tongji/relation/processor/RelationEventProcessor.java`
- `zhiguang_be/src/main/java/com/tongji/knowpost/listener/FeedCacheInvalidationListener.java`
- `zhiguang_be/docs/计数系统设计方案.md`

## 2. 计数对象与数据模型

### 2.1 内容维度计数

当前内容维度主要覆盖：

- `like`
- `fav`

对应实体可按 `entityType + entityId` 维度建模，例如 `knowpost + 123456`。

涉及 3 类 Redis Key：

1. 位图事实键

- 格式：`bm:{metric}:{etype}:{eid}:{chunk}`
- 例子：`bm:like:knowpost:123456:0`
- 作用：记录“某个 userId 是否点赞/收藏过该实体”

2. 聚合桶键

- 格式：`agg:{schema}:{etype}:{eid}`
- 例子：`agg:v1:knowpost:123456`
- 类型：Redis Hash
- 字段：`idx`
- 值：增量 `delta`

3. 汇总快照键

- 格式：`cnt:{schema}:{etype}:{eid}`
- 例子：`cnt:v1:knowpost:123456`
- 类型：固定长度二进制字符串
- 作用：保存所有指标的汇总值

Schema 定义在：

- `zhiguang_be/src/main/java/com/tongji/counter/schema/CounterSchema.java`
- `zhiguang_be/src/main/java/com/tongji/counter/schema/CounterKeys.java`

### 2.2 用户维度计数

用户维度计数单独维护一份 SDS：

- Key：`ucnt:{userId}`

字段共 5 段：

1. `followings`
2. `followers`
3. `posts`
4. `likesReceived`
5. `favsReceived`

实现文件：

- `zhiguang_be/src/main/java/com/tongji/counter/service/impl/UserCounterServiceImpl.java`
- `zhiguang_be/src/main/java/com/tongji/counter/schema/UserCounterKeys.java`

## 3. 内容计数写链路

### 3.1 入口接口

行为入口在：

- `zhiguang_be/src/main/java/com/tongji/counter/api/ActionController.java`

对外暴露：

- `POST /api/v1/action/like`
- `POST /api/v1/action/unlike`
- `POST /api/v1/action/fav`
- `POST /api/v1/action/unfav`

控制器本身不做聚合，只负责：

- 解析登录用户
- 调用 `CounterService`
- 返回是否状态变更，以及当前 `liked/faved`

### 3.2 位图切换是第一事实来源

核心逻辑在 `CounterServiceImpl.toggle(...)`。

处理方式：

1. 根据 `userId` 计算位图分片
2. 执行 Lua 脚本 `TOGGLE_LUA`
3. 如果状态没有变化，直接返回，不发事件
4. 如果状态发生变化，构造 `CounterEvent`
5. 把事件发送到 Kafka `counter-events`
6. 同时发布本地 Spring 事件

这里最关键的一点是：

- 幂等不是靠“业务层判断”
- 而是靠 Redis `GETBIT/SETBIT` 原子切换来保证

也就是说：

- 重复点赞不会重复加 1
- 重复取消不会重复减 1

### 3.3 Kafka 聚合桶

Kafka 消费者在：

- `zhiguang_be/src/main/java/com/tongji/counter/event/CounterAggregationConsumer.java`

处理逻辑：

1. 消费 `CounterEvent`
2. 取出 `entityType/entityId/idx/delta`
3. 执行 `HINCRBY agg:{...} idx delta`
4. 聚合桶写成功后才 `ack`

这一步的意义是把大量离散事件先变成“可重试的增量堆积”，避免每个动作都直接改快照。

### 3.4 定时刷写到 SDS

同一个 `CounterAggregationConsumer` 每 1 秒执行一次 `flush()`。

逻辑是：

1. 扫描 `agg:v1:*`
2. 逐桶读取所有 `idx -> delta`
3. 对每个字段执行 Lua，把 delta 原子折叠进 `cnt:v1:{etype}:{eid}`
4. 折叠成功后再从 Hash 中扣减对应 delta
5. 扣减为 0 的字段删除
6. 桶空了就删 key

这个设计的效果：

- 写路径只追加事实和增量
- 汇总快照以秒级批量方式更新
- 避免每次行为都直接改大对象

## 4. 内容计数读链路

### 4.1 常规读取

入口在：

- `zhiguang_be/src/main/java/com/tongji/counter/api/CounterController.java`

接口：

- `GET /api/v1/counter/{etype}/{eid}?metrics=like,fav`

正常情况下，读取逻辑是：

1. 直接读取 `cnt:v1:{etype}:{eid}`
2. 按 `CounterSchema` 的偏移解析各字段
3. 返回指标值

这是 O(1) 读。

### 4.2 异常读触发重建

如果出现以下情况：

- SDS 不存在
- SDS 长度异常

`CounterServiceImpl.getCounts(...)` 会走重建逻辑：

1. 获取分布式锁 `lock:sds-rebuild:{etype}:{eid}`
2. 对请求的指标扫描所有位图分片
3. 管道化执行 `BITCOUNT`
4. 求和后拼成新的 SDS
5. 回写 `cnt:*`
6. 清理对应聚合桶字段，防止重复加算

这说明 `zhiguang` 的一致性基线不是汇总快照，而是位图事实。

### 4.3 批量读取

`CounterServiceImpl.getCountsBatch(...)` 支持批量读取多个实体的 SDS 快照，主要给 Feed 场景使用。

实现方式：

- 管道批量 `GET cnt:*`
- 逐条解析
- 缺失时按 0 处理

这也是它比“读时每条都去 `BITCOUNT`”更适合 Feed 页的关键原因。

## 5. 用户维度计数链路

### 5.1 关注/粉丝

关系事件通过 Outbox/Canal 进入：

- `zhiguang_be/src/main/java/com/tongji/relation/outbox/CanalOutboxConsumer.java`
- `zhiguang_be/src/main/java/com/tongji/relation/processor/RelationEventProcessor.java`

`RelationEventProcessor` 处理 `FollowCreated` / `FollowCanceled` 时会：

1. 通过 `dedup:rel:*` 去重
2. 更新 follower 表
3. 更新关注/粉丝 ZSet 缓存
4. 调用 `userCounterService.incrementFollowings(...)`
5. 调用 `userCounterService.incrementFollowers(...)`

这里用户计数不是走 Kafka 聚合桶，而是直接对用户 SDS 做 Lua 增减。

### 5.2 发文数

发文数在用户 SDS 的第 3 段。

维护入口在 `UserCounterServiceImpl.incrementPosts(...)`，通常由笔记发布/删除链路触发。

### 5.3 获赞数和获收藏数

这一部分不是在点赞接口里直接更新作者计数，而是在内容计数事件监听器里旁路更新。

实现文件：

- `zhiguang_be/src/main/java/com/tongji/knowpost/listener/FeedCacheInvalidationListener.java`

处理逻辑：

1. 监听本地 `CounterEvent`
2. 只处理 `entityType == knowpost`
3. 查询被操作内容的作者
4. 若是 `like`，执行 `incrementLikesReceived(owner, delta)`
5. 若是 `fav`，执行 `incrementFavsReceived(owner, delta)`

这说明：

- 内容全局计数和作者收到的计数是解耦的
- 作者维度不是主写路径的一部分，而是事件衍生结果

## 6. 用户维度读链路与自愈

接口在：

- `zhiguang_be/src/main/java/com/tongji/relation/api/RelationController.java`

对外：

- `GET /api/v1/relation/counter?userId=...`

读逻辑：

1. 直接读取 `ucnt:{userId}`
2. 若缺失或长度异常，调用 `userCounterService.rebuildAllCounters(userId)`
3. 重建后再读一次
4. 仍失败则返回 0，保证接口可用

另外有采样校验：

1. 每个用户 300 秒最多触发一次
2. 对比 SDS 里的 `followings/followers`
3. 与数据库 `following/follower` 的有效数对比
4. 不一致则执行全量重建

### 6.1 用户全量重建逻辑

`UserCounterServiceImpl.rebuildAllCounters(...)` 的重建来源：

- `following/follower`：查关系表
- `posts`：查作者已发布内容 ID 列表
- `likesReceived/favsReceived`：批量读取作者所有内容的内容计数，再累加

它的重建不是简单查 `t_user_count`，而是从各个事实来源逆推回来。

## 7. 与 Feed/缓存链路的耦合

`FeedCacheInvalidationListener` 除了更新作者收到的计数，还会：

1. 根据反向索引找到受影响的 Feed 页面缓存
2. 在本地 Caffeine 中更新对应内容的 like/fav 数
3. 在 Redis 页面缓存里更新对应内容的 like/fav 数
4. 保持原 TTL 不变

所以在 `zhiguang` 中，计数系统不仅是“存数字”，还是页面缓存更新的触发中心。

## 8. 一致性策略总结

`zhiguang` 的一致性可以概括为：

- 写入事实强幂等：位图切换
- 汇总结果最终一致：Kafka 聚合 + 定时刷写
- 读取可纠偏：位图重建
- 用户维度可自愈：抽样校验 + 全量重建
- 灾难恢复可回放：`CounterRebuildConsumer` earliest 回放历史事件

优点：

- 高并发下不容易重复计数
- 汇总层坏了仍能从事实层重建
- 批量读性能高

代价：

- 实现复杂
- 秒级最终一致
- 依赖较多后台链路：Kafka、调度、分布式锁、重建逻辑

## 9. 业务链路汇总

### 9.1 点赞/收藏

1. 用户调用 `/api/v1/action/*`
2. `CounterServiceImpl` 用 Lua 切换位图
3. 状态变化才发 `CounterEvent`
4. Kafka 消费后写入聚合桶
5. 定时刷写进 SDS
6. 读接口或 Feed 页读取 SDS
7. 本地监听器同步作者收到的点赞/收藏数
8. 若 SDS 异常，读时回扫位图重建

### 9.2 关注/取关

1. 用户调用关系接口
2. 主链路写 following + outbox
3. Canal/事件处理器消费
4. 更新 follower 表和 ZSet 缓存
5. 直接更新用户 SDS 中 followings/followers
6. `/api/v1/relation/counter` 读取用户计数
7. 定时抽样校验，不一致时重建

## 10. 结论

`zhiguang` 的计数系统本质上是：

- 位图保存行为事实
- SDS 保存聚合快照
- Kafka 保存中间增量
- 读路径负责兜底纠偏

它更像一个专门设计过的数据基础设施，而不是普通业务模块里的附属计数逻辑。
