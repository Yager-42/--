# Nexus Redis 缓存统一实施与审计修复方案

- 日期：2026-03-09
- 执行者：Codex
- 合并来源：
  - `缓存/nexus-redis-cache-implementation-plan-2026-03-09.md`
  - `缓存/nexus-redis作为mysql缓存审计-20260309.md`
- 范围：`project/nexus`

## 一句话结论

这两份文档本质上不是两套互相冲突的方案，而是同一件事的两面：**一份告诉我们“哪些高频读链路值得继续加缓存”，另一份告诉我们“现有 Redis 缓存哪里在高并发下不够稳，必须先补强”**。合并后的统一做法很简单：**先修现有缓存的稳定性，再扩新增量缓存覆盖；全程复用现有 Repository / Port / QueryService 模式，不新造框架，不把个性化字段塞进公共缓存。**

---

## 一、这次合并后的统一判断

### 1）两份文档的关系

- `实现方案` 侧重：哪里继续加缓存最值钱，怎么按现有风格落地。
- `审计方案` 侧重：现有缓存在哪些点位存在击穿、重建不原子、失效不完整的问题，怎么修。

所以统一后的方案不是二选一，而是拆成两条线顺序执行：

1. **基础修复线**：先把当前缓存的并发稳定性、一致性、失效边界修好。
2. **增量建设线**：再把缓存扩到还没覆盖的高频读链路。

### 2）本次没有发现必须停下来让你二选一的硬冲突

我对两份文档做了比对，当前没有发现“同一个问题要求保留完全相反方案”的硬冲突。大部分内容是互补关系，不是打架关系。

唯一需要写死的合并策略只有一句话：

> **先稳住已有缓存，再继续扩大缓存覆盖面。**

原因很简单：如果底层重建、失效、原子切换没处理好，继续加更多缓存，只会把问题放大。

---

## 二、统一设计原则

下面这些原则以两份文档的共同结论为准，后续实现都按这个来。

### 1）不新造缓存框架

直接复用项目里已经验证过的做法：

- `StringRedisTemplate`
- `ObjectMapper`
- `Caffeine`
- 现有 `Repository / Port / QueryService`

禁止为了“统一”再封一层新的大而全 `CacheService`。那样只会把简单问题搞复杂。

### 2）缓存放在读路径内部

调用方只关心“拿到结果”，不关心结果来自 Redis、MySQL、Cassandra 还是下游服务。

所以缓存只放在这三层：

- `Repository`：单对象、批量对象、布尔值
- `Port`：外部结果、计数、关系邻接
- `QueryService`：聚合页、首屏页、搜索建议、通知首页

### 3）公共缓存和个性化字段必须拆开

下面这些字段不能进公共缓存：

- `isFollow`
- `liked`
- `seen`
- `isRead`
- `isBlocked`

原因：它们跟“谁在看”有关。混进公共缓存会让 key 暴涨、失效变脏、命中率变差。

统一做法：

- **公共部分单独缓存**
- **个性化部分单独查，或用短 TTL 的轻缓存补最后一层**

### 4）列表只优先做首屏，不做深分页泛缓存

最值钱的是第一页、首屏 10～20 条，因为复用最高。

统一策略：

- 优先缓存第一页 / 首屏
- 深分页保留原有 DB / 索引 / 邻接表路径
- 关系分页优先复用现有 `ZSET` 游标方案

### 5）统一防护策略

- **防穿透**：不存在对象写短 TTL `NULL` 哨兵
- **防击穿**：热点 key 做 single-flight 或互斥重建
- **防雪崩**：TTL 统一加随机抖动
- **防污染**：写路径优先删 key 或覆盖，不做复杂双写事务
- **防拖垮**：Redis 出问题直接降级回源，不能让缓存变成单点

---

## 三、统一架构：分两条线执行

## A 线：先修现有 Redis 缓存的高并发缺陷

这一条线解决的是“现在已经有缓存，但高并发下还不够稳”的问题。

### A1. `ContentRepository.listPostsByIds`

问题：

- 已经能挡掉部分回表，但对同一批 `postIds` 的并发 miss 还会重复打 DB
- 正常值 TTL 固定，雪崩防护不统一

统一做法：

- 增加 **进程内 single-flight**，避免同一批 key 并发回源
- 继续保留负缓存
- 正常值 TTL 改成带抖动区间
- 在完成 single-flight 和统一失效链路后，对 `interact:content:post:{postId}` 增加 **热点 TTL 自增长**，但只对白名单正向 key 生效

目标：

- 同一批帖子并发 miss 时，DB 只查一次

### A2. `ContentDetailQueryService.query`

问题：

- 现有方案已经比较成熟：`L1 Caffeine + L2 Redis + NULL + TTL 抖动`
- 但 miss 后详情装配链路长，并发 miss 会把多路下游一起打热

统一做法：

- 保留现有 `L1/L2/NULL/jitter` 设计
- 在 miss 重建阶段增加 **single-flight**
- 对 `NOT_FOUND` 也要让 waiter 拿到同样结果，而不是重复装配

目标：

- 并发 miss 同一个 `postId` 时，只允许一条装配链路真正回源

### A3. `CommentRepository`

问题：

- 评论批量读取在高并发 miss 下会重复打库
- `reply preview` 容易出现写后未完全失效，用户会看到短暂旧数据

统一做法：

- 批量读加 **single-flight**
- `insert(reply)`、`softDeleteByRootId(rootId)` 后，统一删除 `limit=1..10` 的 reply preview key
- `addLikeCount/addReplyCount` 成功后，删除对应 comment view key

目标：

- 评论相关缓存不仅扛 QPS，还要减少“用户看到评论不对”的直接感知问题

### A4. `RelationAdjacencyCachePort`

问题：

- 现有“发现不完整就重建”的方向是对的
- 但如果用“先删正式 key，再慢慢重建正式 key”，高并发下会出现空窗、半成品、重复重建

统一做法：

- **禁止**继续使用“delete 正式 key 后直接 rebuild 正式 key”
- 必须改成：
  1. 先拿 **Redis 分布式锁**
  2. 在 `tmpKey` 构建完整数据
  3. rebuild 期间把新增/删除操作同步镜像到 `tmpKey`
  4. 构建完整后再 **原子切换** 到正式 key
  5. 正式 key 切换后确保无 TTL 或符合预期持久化策略

目标：

- rebuild 期间不能给用户暴露空数据或半成品

### A5. `ReactionCachePort`

问题：

- 热点计数 key 高并发 miss 时，仍可能重复回表

统一做法：

- `getCount` 读路径增加 **future-based single-flight**
- Redis 命中时不走 DB

目标：

- 同一热点计数 miss 时，只让一次 DB 查询真正发生

### A6. `PostAuthorPort`

问题：

- 简单读缓存收益高，但对不存在对象缺少负缓存

统一做法：

- 补 `NULL` 负缓存
- TTL 统一带抖动
- 保持写后删 key 的简单策略

目标：

- 不存在作者信息时，不反复回表

### A7. `FeedCard` 相关装配链路

问题：

- 卡片基础数据和统计装配在并发 miss 下会重复做整轮组装

统一做法：

- 对 **装配阶段** 加 single-flight
- 不把这层 single-flight 错放到 `FeedCardRepository` 仓储层
- 对 `feed:card:{postId}` 的 **base 卡片 key** 增加热点 TTL 自增长；`feed:card:stat:{postId}` 默认不参与

目标：

- 同一批 base cards / stat cards 并发 miss 时，只做一次完整装配循环

---

## B 线：继续补齐高价值缓存覆盖点

这一条线解决的是“项目已经有 Redis，但高频读链路还没形成完整闭环”的问题。

### B1. 用户资料读取

统一策略：

- 不新造两份缓存
- 直接对齐现有 `social:userbase` 模式

适用：

- 用户基础信息
- 头像、昵称、基础状态类只读资料

### B2. 用户主页 / 聚合页

统一策略：

- 缓存公共聚合结果
- 个性化字段单独查或短 TTL 缓存
- 返回前再拼装

适用：

- 用户主页聚合
- 通知首页
- 评论首屏聚合

### B3. 首屏列表缓存

统一策略：

- Redis 存首屏 ID 列表或精简响应
- 详情字段继续复用已有对象缓存
- 只缓存第一页
- 点赞、删除、新增评论等写操作删除第一页相关 key

适用：

- 个人主页 feed 首页
- 评论首屏
- 点赞人列表首页

### B4. 搜索建议 / 推荐上游结果 / 外部结果读缓存

统一策略：

- 放在 `Port` 或 `QueryService` 层
- 给外部结果加短中 TTL
- 失败可降级，不让缓存影响主流程

适用：

- 搜索建议
- 推荐服务上游结果
- 风控规则 / 提示词 / 只读配置类结果

### B5. KV 内容缓存（仅在读频率真实够高时做）

适用对象：

- `PostContentKvPort.find/findBatch`
- `CommentContentKvPort.batchFind`

统一策略：

- 如果 `/kv/*` 被高频业务直接调用，就在 Cassandra 前面加 Redis 很划算
- 如果只是低频内部接口，就先不做，避免无意义复杂化

这部分保持实用主义：**不是所有能缓存的点都要马上缓存。**

---

## 四、统一通用模板

### 模板 1：单对象 cache-aside

适用：用户资料、作者资料、风控规则、提示词。

流程：

1. 先查 L1（仅极热点对象）
2. 再查 Redis
3. 命中 `NULL` 哨兵直接返回不存在 / 默认值
4. miss 后回源 DB / 配置表 / 外部服务
5. 查到结果写 Redis，没查到写短 TTL `NULL`
6. 写路径成功后删 key

### 模板 2：聚合页拆层缓存

适用：用户主页、评论首屏、通知首页。

流程：

1. 先缓存公共聚合结果
2. 个性化字段单独查或短 TTL 缓存
3. 返回前把两层结果拼起来
4. 写路径只删公共 key 和少量个性化 key

### 模板 3：列表首屏缓存

适用：个人主页 feed 首页、评论首屏、点赞列表首页。

流程：

1. Redis 保存首屏 ID 列表或精简聚合结果
2. 详情数据继续复用对象缓存
3. 只缓存第一页
4. 相关写操作删除第一页 key

### 模板 4：热点重建去重

适用：批量对象读取、详情装配、热点计数、卡片组装。

流程：

1. 读 Redis miss
2. 用 `ConcurrentHashMap<String, CompletableFuture<T>>` 做进程内 single-flight
3. leader 回源并回写缓存
4. waiter 直接复用 future 结果
5. finally 清理 inflight map

### 模板 5：共享大 key 原子重建

适用：关系邻接、共享列表、需跨实例一致的重建任务。

流程：

1. 先拿 Redis 分布式锁
2. 构建 `tmpKey`
3. 重建期间把写操作同步镜像到 `tmpKey`
4. 校验完整后原子切换
5. 切换成功后释放锁

---

## 五、执行契约（实现时不允许自行发挥）

这一节是合并后的硬约束。后续实现必须遵守。

### 1）固定技术选择

- 不新增 Maven 依赖
- 不新增模块
- 不引入外部分布式任务框架
- 不修改任何现有 public interface 方法签名
- 除 `RelationAdjacencyCachePort` 外，所有并发 miss 去重都统一用 **进程内 single-flight**
- 进程内 single-flight 的固定实现方式：`ConcurrentHashMap<String, CompletableFuture<T>>`
- 不新增通用 shared util 类；每个类自己维护自己的 inflight map 和 private helper
- `RelationAdjacencyCachePort` 的重建锁固定使用 **Redis 分布式锁**，不用本地锁
- `CommentRepository` 的 `reply preview` 失效范围固定为 `limit=1..10` 全删
- 热点 TTL 自增长只允许作为 **现有 TTL 抖动策略的增强层**，不允许替换写后删除或延时双删
- 本次重构里，热点 TTL 自增长只允许作用在白名单 key 家族：`interact:content:post:{postId}`、`feed:card:{postId}`
- 本次重构里，热点 TTL **禁止**作用在：`NULL` 负缓存、`comment:view:*`、`comment:reply:preview:*`、`social:adj:*`、`interact:reaction:*`、`interact:content:detail:*`、`interact:content:author:*`、`feed:card:stat:*`
- 测试框架固定为现有项目风格：`JUnit 5 + Mockito`
- 不引入 `Testcontainers`
- 不引入嵌入式 Redis

### 2）固定行为边界

- 不允许把 `single-flight` 改成 `synchronized(this)` 或方法级大锁
- 不允许把 batch key 改成无序 key
- 不允许去掉“锁内二次检查缓存”
- 不允许把 `RelationAdjacencyCachePort` 简化回“delete 正式 key 再 rebuild 正式 key”
- 不允许只删某一个 reply preview limit
- 不允许把 `FeedCard` 的装配去重错放到仓储层
- 不允许为了迁就实现去改 public interface 签名

---

## 六、统一优先级

### Phase 1：先止血，修高并发风险

1. `ContentRepository.listPostsByIds`
2. `RelationAdjacencyCachePort`
3. `CommentRepository` reply preview 失效与批量 single-flight
4. `ContentDetailQueryService.query`
5. `ReactionCachePort`
6. `PostAuthorPort`
7. `FeedCard` 装配去重

### Phase 2：再扩缓存覆盖

1. 用户资料读取统一对齐 `social:userbase`
2. 用户主页 / 评论首屏 / 通知首页聚合缓存
3. 首屏列表缓存
4. 搜索建议 / 推荐上游结果 / 外部结果缓存
5. KV 内容缓存（仅在真实高频时进入）

这套顺序的意思很直接：

- **先解决“已有缓存会不会在高并发下出错”**
- **再解决“哪些新链路值得继续加缓存”**

---

## 七、测试与验收标准

下面这些场景是合并后必须保留的验证标准。

### `ContentRepositoryTest`

1. 两个线程同时 miss 同一批 `postIds`，`contentPostDao.selectByIds` 只允许调用 1 次
2. leader 回写 Redis 后，waiter 直接拿 future 结果，不允许再打 DB
3. 正常值 TTL 使用抖动区间；空值 TTL 继续短 TTL

### `ContentDetailQueryServiceTest`

1. 两个线程同时 miss 同一 `postId`，`findPostMeta` 只允许调用 1 次
2. `post == null` 时，waiter 也必须拿到相同 `NOT_FOUND` 结果
3. leader 写入 Redis 后，第二个请求命中 L2，不再重复装配

### `CommentRepositoryTest`

1. 两个线程同时 miss 同一批评论，`commentDao.selectByIds` 只允许 1 次
2. `insert(reply)` 成功后，`limit=1..10` reply preview key 全部被删除
3. `softDeleteByRootId(rootId)` 成功后，`limit=1..10` reply preview key 全部被删除
4. `addLikeCount/addReplyCount` 成功后，对应 comment view key 被删除

### `RelationAdjacencyCachePortTest`

1. `ensureFollowingCache` 发现不完整时，只允许一个线程拿到 rebuild lock
2. rebuild 期间 `addFollow/removeFollow` 会同步镜像到 `tmpKey`
3. 原子切换完成后正式 key 存在且状态正确
4. build 不完整时禁止切换正式 key

### `ReactionCachePortTest`

1. 两个线程同时 miss 同一 `cntKey`，`selectCount` 只允许被调用 1 次
2. Redis 已命中时不走 DB

### `FeedCardAssembleServiceTest`

1. 两个线程同时 miss 同一批 base cards，只允许走 1 次 `contentRepository.listPostsByIds`
2. 两个线程同时 miss 同一批 stat cards，只允许走 1 次完整统计装配循环

### 新增覆盖点验收标准

- 用户资料读取命中 Redis 后，不再回源数据库
- 聚合页缓存命中后，公共结果可复用，个性化字段不串用户
- 首屏列表缓存只覆盖第一页，不污染深分页
- 写操作后首屏 key 会被正确删除
- Redis 故障时主流程仍能回源，不影响可用性

---

## 八、只有这些情况才允许中断并询问选择

当前合并文档已经消除了普通层面的冲突。后续真正实现时，只有下面这些情况才需要停下来问：

1. `StringRedisTemplate` 在当前项目版本里无法安全完成 `rename / persist / Lua` 等原子切换能力，导致 `RelationAdjacencyCachePort` 无法按这里的方案落地
2. `ReactionCachePort` 的现有单测或调用语义表明，future-based single-flight 会破坏已有同步行为
3. `FeedCard` 调用方强依赖“同一次 assemble 必须立刻看到用户态实时字段变化”，而结果共享会破坏这个前提

除这几类硬阻塞外，不需要反复中断做技术选择。

---

## 九、最终执行建议

如果现在要开始真正落地，这份合并文档对应的最小可执行顺序是：

1. 先修 `ContentRepository`
2. 再修 `RelationAdjacencyCachePort`
3. 再修 `CommentRepository`
4. 再给 `ContentDetailQueryService`、`ReactionCachePort`、`PostAuthorPort`、`FeedCard` 补统一去重策略
5. 基础稳定后，再进入用户资料、聚合页、首屏列表、搜索建议、推荐上游结果等新增缓存建设

一句话说完：**这次不是“继续乱加缓存”，而是“先把已有缓存修成能扛并发的样子，再把缓存补到真正高频、真正值钱的地方”。**

---

## 十、无歧义执行补充（本节优先级最高）

这一节的作用只有一个：**把前文里所有“方向是对的，但写得还不够死”的地方彻底写死。**

后续实现时，若本节与前文任何泛化表述冲突，**一律以本节为准**。

### 1）先把范围说死：哪些能直接开工，哪些还不能

- **A 线（修现有缓存）是可以直接编码的执行契约。**
- **B 线（补新缓存覆盖）目前只保留优先级和准入规则，不允许直接照着这一份文档开工。**
- 原因很简单：B 线很多点位还缺少“接口级业务规则、key 级失效矩阵、字段边界、回退语义”，如果现在直接动手，只会把模糊性带进代码。

所以这份文档从现在开始有两个层次：

1. **Phase 1 / A 线：硬执行契约，可以直接实现**
2. **Phase 2 / B 线：候选池，只有补完独立子文档后才能实现**

这不是退缩，这是避免假完整。

### 2）业务真相与数据所有权

后面所有缓存设计都基于下面这组“真相”，不允许改口：

- **帖子元信息**（标题、摘要、媒体信息、状态、可见性、版本等）的真相源是 MySQL `content_post` 相关表。
- **帖子正文 / 评论正文** 的真相源是 KV/Cassandra，对 Nexus 当前实现来说分别由 `PostContentKvPort`、`CommentContentKvPort` 负责读取。
- **点赞总数** 的真相源是 MySQL 聚合表 `interaction_reaction_count`，Redis `cntKey` 只是高性能前置层。
- **关注/粉丝关系** 的真相源是 MySQL 关系表；Redis ZSET 只是读优化邻接表。
- **用户昵称/头像** 的真相源是用户域 `user_base`；内容域和 Feed 域都不应该自己长期持有第二份“作者展示真相”。
- **Redis 永远不是业务真相源。** Redis 写失败、删失败、解析失败，都不能回滚主事务，更不能把“缓存异常”翻译成“业务失败”。

一句话说：**先把谁是 owner 说清楚，缓存才不会越做越乱。**

### 3）不存在、失败、脏数据三件事必须分开

后续所有实现都按这个语义，不允许混：

- **不存在**：只有在 DB/KV 明确返回空时，才能写 `NULL` 哨兵。
- **失败**：Redis 超时、Redis 宕机、JSON 解析异常、下游偶发报错，都只能算“失败”，不能算“不存在”。
- **脏数据**：Redis 里有值但解析失败，说明 key 已损坏；处理方式固定为：**删掉这个坏 key，然后按 miss 回源**。

固定规则：

1. 只有“真相源明确为空”时，允许写 `NULL`
2. 任何基础设施异常都只当 miss，不写 `NULL`
3. 解析失败时先删坏 key，再回源
4. 不缓存异常对象，不缓存半成品对象

### 4）共享缓存字段边界：哪些字段能进，哪些字段绝不能进

这是整份文档里最重要的一条边界。

#### 4.1 `ContentRepository` 的帖子对象缓存

允许进入共享缓存：

- `postId`
- `userId`
- `title`
- `summary`
- `summaryStatus`
- `mediaType`
- `mediaInfo`
- `locationInfo`
- `status`
- `visibility`
- `versionNum`
- `edited`
- `createTime`
- `postTypes`
- `contentText`（如果当前对象快照已包含正文）

不允许进入共享缓存：

- 任何 viewer 相关字段
- 任何只在最后一层展示时才需要的用户态字段

#### 4.2 `ContentDetailQueryService` 的详情缓存

**从这一版开始，详情缓存不再缓存完整 `ContentDetailResponseDTO`。**

原因：当前实现把 `likeCount`、`authorNickname`、`authorAvatarUrl` 和内容稳定字段一起放进 `1d` 缓存，这会直接造成两个业务问题：

1. 点赞数在 1 天内可能明显过旧
2. 昵称/头像变更后，详情页可能长期不刷新

所以固定改法如下：

- Redis `interact:content:detail:{postId}` 里只存 **稳定快照**
- 稳定快照只包含：
  - `postId`
  - `authorId`
  - `title`
  - `content`
  - `summary`
  - `summaryStatus`
  - `mediaType`
  - `mediaInfo`
  - `locationInfo`
  - `status`
  - `visibility`
  - `versionNum`
  - `edited`
  - `createTime`
- **不存**：
  - `likeCount`
  - `authorNickname`
  - `authorAvatarUrl`
  - 任何 viewer 相关字段

返回给前端时再做最后拼装：

- `authorNickname / authorAvatarUrl`：每次通过 `IUserBaseRepository` 读取
- `likeCount`：每次通过 `IReactionCachePort.getCount` 读取

这样做的好处很简单：

- 详情稳定字段仍然能高命中
- 点赞数走现成高频计数缓存，不会被 1 天缓存锁死
- 昵称/头像的 freshness 交给用户域自己的缓存策略，不在内容详情里再复制一份长期真相

#### 4.3 `FeedCard` 两层缓存的字段边界

固定拆成三层：

1. **基础卡片层**：共享稳定字段
2. **统计层**：共享高频公共计数
3. **用户态层**：当前用户和卡片的关系

固定边界：

- `feed:card:{postId}` 只放稳定字段：
  - `postId`
  - `authorId`
  - `text`
  - `summary`
  - `mediaType`
  - `mediaInfo`
  - `publishTime`
- `feed:card:stat:{postId}` 只放：
  - `likeCount`
- 不允许进入共享缓存：
  - `liked`
  - `followed`
  - `seen`
  - `isBlocked`
  - `authorNickname`
  - `authorAvatar`

`authorNickname / authorAvatar` 固定在 assemble 阶段通过 `IUserBaseRepository` 批量补。

这条必须写死。否则用户改个头像，你的 Feed 卡片缓存就会变成第二份用户资料库。

#### 4.4 `CommentView` 的特殊说明

`CommentViewVO` 目前带 `nickname/avatarUrl`，这里**允许保留**，原因只有一个：

- 现在它的 L2 TTL 只有 `5.0~5.5s`

这意味着它的陈旧窗口很短，业务上还能接受。

但这里也写死一个边界：

- **如果以后把 `CommentView` 的 L2 TTL 提高到 30 秒以上，就必须把 `nickname/avatarUrl` 拆出去，不允许继续长期共存。**

### 5）统一 key 与 TTL：全部写死，不允许临场发明

#### 5.1 帖子对象缓存 `ContentRepository`

- Redis key：`interact:content:post:{postId}`
- 空值哨兵：value 固定 `"NULL"`
- L1：只给热点 key，`Caffeine`，TTL 固定 `2s`
- L2 正常值 TTL：`60s + 0~15s`，也就是 `60~75s`
- L2 空值 TTL：`30s + 0~10s`，也就是 `30~40s`

#### 5.2 详情稳定快照缓存 `ContentDetailQueryService`

- Redis key：`interact:content:detail:{postId}`
- 空值哨兵：value 固定 `"NULL"`
- L1：`Caffeine`，TTL 固定 `1h`
- L2 正常值 TTL：固定为 `24h + 0~1h`，也就是 `24~25h`
- L2 空值 TTL：固定 `90s`
- Redis value：**稳定快照 JSON**，不是完整 `ContentDetailResponseDTO`

#### 5.3 评论缓存 `CommentRepository`

- 评论详情 key：`comment:view:{commentId}`
- 回复预览 key：`comment:reply:preview:{rootId}:{limit}`
- 空值哨兵：value 固定 `"NULL"`
- L1：
  - `commentViewCache` TTL `2s`
  - `replyPreviewIdsCache` TTL `2s`
- L2 正常值 TTL：`5000ms + 0~500ms`，也就是 `5.0~5.5s`
- L2 空值 TTL：`2000ms + 0~500ms`，也就是 `2.0~2.5s`
- 回复预览只允许缓存：
  - `cursor == null || cursor.isBlank()`
  - 且 `normalizedLimit <= 10`

#### 5.4 关系邻接缓存 `RelationAdjacencyCachePort`

- 正式 following key：`social:adj:following:z:{userId}`
- 正式 followers key：`social:adj:followers:z:{userId}`
- rebuild lock key：
  - `social:adj:following:lock:{userId}`
  - `social:adj:followers:lock:{userId}`
- rebuild route key（记录当前 tmpKey）：
  - `social:adj:following:route:{userId}`
  - `social:adj:followers:route:{userId}`
- tmpKey：
  - `social:adj:following:tmp:{userId}:{token}`
  - `social:adj:followers:tmp:{userId}:{token}`
- lock TTL：`30s`
- lock renew interval：`10s`
- tmpKey TTL：`10min`（防止异常中断后遗留垃圾 key）
- 正式 key：**不设置 TTL**

#### 5.5 点赞计数缓存 `ReactionCachePort`

- 计数 key：`interact:reaction:cnt:{tag}`
- bitmap key：`interact:reaction:bm:{tag}:{shard}`
- L1 热点缓存 key：`like__{tag}`
- L1：`Caffeine`，TTL 固定 `2s`，最大 `100_000`
- `cntKey`：**不设置 TTL**，因为它是点赞读路径的长期热点计数层
- 当 `cntKey` 丢失时，回表 `interaction_reaction_count` 重建，不允许用 `SCARD` 之类的近似替代

#### 5.6 帖子作者缓存 `PostAuthorPort`

- key：`interact:content:author:{postId}`
- 正常值 TTL：`1d + 0~1h`
- 空值 TTL：固定 `30~40s`
- 空值哨兵：value 固定 `"NULL"`

#### 5.7 Feed 卡片缓存

- base key：`feed:card:{postId}`
- stat key：`feed:card:stat:{postId}`
- base L1：`2s`
- base L2 TTL：`1800s + 0~600s`，也就是 `30~40m`
- stat L1：`2s`
- stat L2 TTL：`600s + 0~180s`，也就是 `10~13m`

#### 5.8 热点 TTL 自增长白名单矩阵

这次把热点 TTL 正式并入当前缓存重构方案里，但只按白名单启用，不搞一刀切。

| key 家族 | 是否启用 | 原因 |
|---|---|---|
| `interact:content:post:{postId}` | 是 | 公共对象、稳定字段多、写后失效链路已统一到 `evictPost` |
| `feed:card:{postId}` | 是 | 只缓存基础稳定卡片，适合热读延长寿命 |
| `feed:card:stat:{postId}` | 否 | `likeCount` 属于高频变化公共计数，默认不拉长 TTL |
| `interact:content:detail:{postId}` | 否 | 基线 TTL 已经是 `24~25h`，再增长收益小且容易放大脏数据 |
| `interact:content:author:{postId}` | 否 | 基线 TTL 已经是 `1d+`，收益太小 |
| `comment:view:*` / `comment:reply:preview:*` | 否 | 这些 key 故意短 TTL，目的是控制新鲜度窗口 |
| `social:adj:*` | 否 | 重点是原子重建正确性，不是 TTL |
| `interact:reaction:*` | 否 | 已有热点 L1 与原子写逻辑，再加会重叠 |

固定结论：**这次重构把热点 TTL 当成“旧缓存方案的定向增强”，不是“全缓存框架统一升级”。**

#### 5.9 热点 TTL 自增长固定算法

本节是执行契约，后续实现不得自行发挥。

##### A. 固定目标

- 目标不是把热点 key 默认变成“永不过期”
- 目标是：**在基础 TTL 抖动之上，给白名单 key 增加按热度分级的 TTL 上限**
- 本次重构不启用 `persist/no-expire` 模式；最高只到 `HIGH` 档

##### B. 固定热度窗口

- 使用 `60s` 滑窗
- 切成 `6` 个 bucket
- 每个 bucket 长度固定 `10s`
- 每次成功返回**正向结果**时记录一次访问

这里“成功返回正向结果”固定指：

- L1 命中并返回
- L2 命中并返回
- DB / 下游回源成功后写入正向缓存并返回

这里**不计数**：

- `NULL` 负缓存命中
- 参数非法
- JSON 解析失败
- Redis 失败
- 下游失败
- 任何异常返回

##### C. 固定热度分级

- `NONE`：`< 50`
- `LOW`：`>= 50`
- `MEDIUM`：`>= 200`
- `HIGH`：`>= 500`

##### D. 固定扩容规则

每次成功返回白名单 key 时，都按下面步骤执行：

1. `recordHeat(key)`
2. `level = currentHeatLevel(key)`
3. 若 `level == NONE`，直接结束
4. 读取 Redis 当前 TTL
5. 计算该家族该等级的 `targetTtl`
6. 只有当 `currentTtl > 0` 且 `currentTtl < targetTtl` 时，才执行 `EXPIRE`
7. 若 key 不存在，直接结束，不额外创建 key
8. 若 TTL 为 `-1`，直接结束；本次重构不主动把白名单 key 变成永久 key

##### E. 固定家族上限

`interact:content:post:{postId}`：

- 基线：`60~75s`
- `LOW`：`180~210s`
- `MEDIUM`：`600~660s`
- `HIGH`：`1800~1920s`

`feed:card:{postId}`：

- 基线：`1800~2400s`
- `LOW`：`7200~7800s`
- `MEDIUM`：`21600~22800s`
- `HIGH`：`43200~45000s`

##### F. 固定禁止项

- `NULL` 负缓存永不参与热点 TTL 自增长
- 写路径删除逻辑完全不变
- 延时双删逻辑完全不变
- 不允许因为热点 TTL 存在，就跳过 `evictPost`、评论失效、或其它写后删 key 逻辑
- 不允许对白名单以外的 key 家族偷偷复用这套逻辑

### 6）single-flight 统一规则：避免同一 miss 重复回源

除了 `RelationAdjacencyCachePort` 用 Redis 分布式锁，其它并发去重全部按下面做，不允许各写一套。

#### 6.1 固定实现

- 每个类自己持有：`ConcurrentHashMap<String, CompletableFuture<T>> inflight`
- 不抽公共 util
- 只新增 private helper，不改 public interface

#### 6.2 固定 key 归一化规则

- `ContentRepository.listPostsByIds`：
  - 先去掉 `null`
  - 再去重
  - 再按升序排序
  - 最后用逗号拼成字符串
- `CommentRepository.listByIds`：同上
- `ContentDetailQueryService.query`：key 固定为 `String.valueOf(postId)`
- `ReactionCachePort.getCount`：key 固定为 `target.hashTag()`
- `FeedCard` base/stat 装配：
  - 先去掉 `null`
  - 再去重
  - 再按升序排序
  - 最后用逗号拼成字符串

#### 6.3 固定行为语义

1. waiter 必须复用 leader 的同一个 future
2. leader 成功，waiter 拿同样结果
3. leader 返回 NOT_FOUND，waiter 也拿同样 NOT_FOUND
4. leader 抛异常，waiter 也看到同样异常
5. `whenComplete` 必须清理 inflight map
6. 不额外引入第二套 future 超时；沿用 DAO/Redis/下游客户端自己的超时
7. 进入 future 后必须再查一次 Redis，避免 leader 启动后别的线程已经回填了缓存

### 7）各组件的无歧义实现步骤与 corner case 处理

#### 7.1 `ContentRepository.listPostsByIds`

固定流程：

1. 输入 `postIds == null || isEmpty`，直接返回空列表
2. 过滤 `null`，但**最终返回顺序仍按原输入顺序**
3. L1 只对热点 key 生效；非热点直接跳过 L1
4. L2 `multiGet`
5. 命中 `"NULL"` 的 id，直接视为不存在，不回 DB
6. 命中 JSON 但解析失败：删坏 key，放入 DB miss 集合
7. 对 DB miss 集合做 single-flight
8. leader 锁内再次查 Redis
9. 仍 miss 才调用 `contentPostDao.selectByIds`
10. 找到的对象写正缓存；没找到的对象写 `NULL`
11. 最终返回时必须按原输入顺序组装；输入里有重复 id，输出也保留重复顺序

固定热点 TTL 自增长规则：

1. 只对 `interact:content:post:{postId}` 的正向缓存生效
2. 只在“成功返回正向对象”后记录热度
3. 若当前热度达到 `LOW/MEDIUM/HIGH`，则尝试提升 Redis TTL 到对应上限
4. 若当前 TTL 已经大于等于目标 TTL，不做任何事
5. 若当前 value 是 `NULL`，禁止提升 TTL
6. 若 key 当前不存在或 TTL 非正值，不额外创建 key
7. 任何写路径成功后仍然必须调用 `IContentCacheEvictPort.evictPost(postId)`；热点 TTL 不能替代失效

固定失效规则：

- `savePost`
- `replacePostTypes`
- `softDelete`
- `softDeleteIfMatchStatusAndVersion`
- 任何改变帖子元信息、可见性、状态、正文版本的写路径

以上都**不能只删本类 L1**，必须调用 `IContentCacheEvictPort.evictPost(postId)`，统一完成：

- 本机 `ContentRepository` L1 删除
- 本机 `ContentDetailQueryService` L1 删除
- Redis 删除 `interact:content:post:{postId}`
- Redis 删除 `interact:content:detail:{postId}`
- 1 秒后延时双删 Redis
- MQ 广播删其它实例本地缓存

固定边界：

- 点赞数变化**不触发** `evictPost`，因为详情页点赞数不再放进长 TTL 详情缓存
- 用户昵称/头像变化**不触发** `evictPost`，因为详情页和 Feed 卡片改为实时从用户缓存补作者展示信息

#### 7.2 `ContentDetailQueryService.query`

固定流程：

1. `postId == null`：直接抛 `ILLEGAL_PARAMETER`
2. 先查本地 `stableSnapshot` L1
3. 再查 Redis `stableSnapshot` L2
4. 命中 `"NULL"`：直接抛 `NOT_FOUND`
5. 命中坏 JSON：删坏 key，当 miss 处理
6. miss 时进入 single-flight
7. leader 锁内二次查 Redis
8. 仍 miss 时：
   - 调 `contentRepository.findPostMeta(postId)`
   - 调 `postContentKvPort.find(contentUuid)` 读正文
   - 组装稳定快照并写缓存
   - 如果 `findPostMeta` 明确为空，写 `NULL`
9. 无论稳定快照来自 L1、L2 还是新回源，**返回前都固定再做两件事**：
   - 调 `userBaseRepository.listByUserIds(List.of(authorId))` 补 `authorNickname/avatar`
   - 调 `reactionCachePort.getCount(target)` 补 `likeCount`
10. 组装最终 `ContentDetailResponseDTO` 返回

固定降级语义：

- 作者资料读取失败：`authorNickname = ""`，`authorAvatarUrl = ""`
- 点赞数读取失败：`likeCount = 0`
- 正文读取失败：`content = ""`

固定原因：

- 详情页可用性优先，不能因为用户域、KV、点赞域抖一下就整页失败

#### 7.3 `CommentRepository`

这里最容易出现遗漏，所以直接写矩阵。

##### A. `listByIds(List<Long> commentIds)`

固定流程：

1. 输入为空返回空列表
2. 先查 L1，返回 copy，禁止调用方改对象污染缓存
3. 再查 Redis `multiGet`
4. 命中 `"NULL"` 的 commentId 直接视为不存在
5. 解析失败删坏 key
6. DB miss 集合走 single-flight
7. leader 锁内二次查 Redis
8. `commentDao.selectByIds` 后再批量补正文
9. 写 L2 和 L1 时必须写“快照”，返回时必须 copy
10. 最终返回仍按输入顺序组装

##### B. `pageReplyCommentIds(rootId, cursor, limit, viewerId)`

固定 preview 规则：

- 只有 `cursor` 为空且 `normalizedLimit <= 10` 才允许走 preview cache
- 其它情况一律走原路径，不允许生成新 preview key
- preview cache 只缓存 **reply ids 列表**，不缓存 reply 详情对象

##### C. 写后失效矩阵

1. `insert(...)`
   - 总是删 `comment:view:{commentId}`（防守式，无则忽略）
   - 如果 `rootId != null`，删 `comment:reply:preview:{rootId}:{1..10}`
2. `approvePending(commentId, nowMs)`
   - 先 `selectBriefById(commentId)` 取 `rootId`
   - 状态更新成功后，删 `comment:view:{commentId}`
   - 如果 `rootId != null`，删 `comment:reply:preview:{rootId}:{1..10}`
3. `rejectPending(commentId, nowMs)`
   - 规则同 `approvePending`
4. `softDelete(commentId, nowMs)`
   - 先 `selectBriefById(commentId)` 取 `rootId`
   - 删除成功后，删 `comment:view:{commentId}`
   - 如果 `rootId != null`，删 `comment:reply:preview:{rootId}:{1..10}`
5. `softDeleteByRootId(rootId, nowMs)`
   - 删除成功后，删 `comment:view:{rootId}`
   - 删 `comment:reply:preview:{rootId}:{1..10}`
6. `addLikeCount(commentId, delta)`
   - DB 更新成功后，只删 `comment:view:{commentId}`
7. `addReplyCount(rootCommentId, delta)`
   - DB 更新成功后，删 `comment:view:{rootCommentId}`
   - 同时删 `comment:reply:preview:{rootCommentId}:{1..10}`

固定允许的“重复删”：

- 同一个写请求里多次删同一个 key 是允许的
- 这里优先要的是正确，不是把代码省成几行

固定边界：

- `softDeleteByRootId(rootId)` 不要求批量删除整个子树里所有 `comment:view:{childId}`
- 原因：当前没有“rootId -> 所有已缓存 child comment key”的反向索引
- 这一条的业务语义写死为：**子评论详情最多允许保留一个 `5.5s` 内的短暂陈旧窗口**

#### 7.4 `RelationAdjacencyCachePort`

这一块必须完全改成“tmpKey 构建 + route 镜像 + 原子切换”，不允许再删正式 key 后裸 rebuild。

固定流程如下。

##### A. 读路径

1. `pageFollowing/pageFollowers` 先检查正式 key 是否存在
2. 再比 `zCard` 和 DB count
3. 只有当“key 不存在”或“zCard < dbCount”时，才触发 rebuild
4. rebuild 期间，读请求仍然读正式 key，不读 tmpKey

##### B. 获取锁

1. `SET lockKey token NX PX 30000`
2. 抢不到锁：直接返回旧正式 key 结果，不阻塞主流程
3. 抢到锁后再做一次完整性检查；如果此时已经完整，直接释放锁返回
4. 启动每 `10s` 一次的续锁任务，续锁时必须校验 token 一致

##### C. 构建 tmpKey

1. 创建唯一 `tmpKey`
2. `DEL tmpKey`
3. 给 `tmpKey` 设置 `10min` TTL
4. 写 `routeKey -> tmpKey`，TTL 与 lock 同步
5. 分页扫 DB，把数据写入 `tmpKey`

##### D. rebuild 期间写路径镜像

`addFollow/removeFollow` 必须固定这样做：

1. 永远先更新正式 key
2. 再读 `routeKey`
3. 如果 `routeKey` 指向某个 `tmpKey`，则把同样变更镜像到 `tmpKey`
4. 如果读不到 `routeKey`，说明当前没有 rebuild，直接结束

##### E. 切换前校验

1. 构建完成后重新读 DB count
2. 重新读 `tmpKey zCard`
3. **只有 `tmpZCard == latestDbCount` 时才允许切换**
4. 不相等则放弃本次切换：
   - 保留旧正式 key
   - 删除 `tmpKey`
   - 删除 `routeKey`
   - 释放锁

##### F. 原子切换

1. 用 Lua 做 compare-token + `RENAME tmpKey -> formalKey`
2. `PERSIST formalKey`
3. 删除 `routeKey`
4. 释放锁（compare-token delete）
5. 停止续锁任务

固定边界：

- rebuild 失败时，允许旧正式 key 继续服务
- 不允许让用户读到空 key 或半成品 key
- 不允许把 tmpKey 暴露给读路径

#### 7.5 `ReactionCachePort.getCount`

固定流程：

1. 先算 `tag`
2. 再算 `hotkey = like__{tag}`
3. `JdHotKeyStore.isHotKey(hotkey)`
4. 非热点：直接走 `redis -> DB 重建`
5. 热点：
   - 先查 L1 `countCache`
   - miss 后走 single-flight
   - leader 先查 Redis `cntKey`
   - Redis miss 才回表 `interactionReactionCountDao`
   - 回填 `cntKey`
   - 回填 L1
6. 任何时候都**不允许**用 bitmap、set、ops 日志去临时拼 count
7. `applyAtomic` 成功后，只做 `countCache.invalidate(hotkey)`，不删 `cntKey`

固定原因：

- `cntKey` 是点赞读路径的长期热点层
- `interaction_reaction_count` 才是回源时的真相聚合表
- bitmap 和 ops 是写链路机制，不是 count 真相源

#### 7.6 `PostAuthorPort`

固定流程：

1. `postId == null` 返回 `null`
2. 先查 Redis
3. 命中 `"NULL"` 直接返回 `null`
4. 命中正常 userId 直接返回
5. miss 走 single-flight
6. leader 锁内二次查 Redis
7. 仍 miss 才调 `contentPostDao.selectUserId(postId)`
8. 找到 userId：写正缓存
9. 没找到：写 `NULL`

固定边界：

- 这里只缓存 `authorId`，不缓存昵称、头像
- 作者展示资料仍然交给 `IUserBaseRepository`

#### 7.7 `FeedCard` 装配链路

固定组装顺序：

1. 先确定本次需要的 `postIds`
2. 读 `feed:card:{postId}` 批量拿基础稳定卡片
3. 对 base miss 集合做 single-flight
4. base leader 内部调用 `contentRepository.listPostsByIds`
5. base leader 只构建稳定字段并写 `feed:card:{postId}`
6. 再批量调 `IUserBaseRepository.listByUserIds` 补 `authorNickname/avatar`
7. 再读 `feed:card:stat:{postId}` 批量拿 `likeCount`
8. 对 stat miss 集合做 single-flight
9. stat leader 内部调用 `reactionCachePort.getCount`
10. 最后才补 viewer 相关字段：`liked/followed/seen`

固定边界：

- assemble single-flight 只放在组装层，不放仓储层
- `liked/followed/seen` 永远最后补，永远不进共享缓存
- `authorNickname/avatar` 每次 assemble 都覆盖共享缓存里的同名字段；如果 base cache 里还残留旧字段，也以本次用户域结果为准
- 热点 TTL 自增长只作用在 `feed:card:{postId}`，不作用在 `feed:card:stat:{postId}`
- `feed:card:{postId}` 只有在返回的是基础稳定卡片时才记录热度；viewer 态补全不参与热度统计

### 8）内容缓存失效矩阵：哪些变化一定要删，哪些不删

#### 8.1 一定要调用 `IContentCacheEvictPort.evictPost(postId)` 的场景

- 发帖成功
- 编辑帖子成功
- 帖子类型变更成功
- 正文版本变更成功
- 状态变更成功
- 可见性变更成功
- 删除 / 软删除成功

#### 8.2 不需要调用 `evictPost(postId)` 的场景

- 点赞数变化
- 作者昵称变化
- 作者头像变化

原因已经在上面写死：

- 点赞数不再存进 1d 详情稳定缓存
- 作者展示资料不再由内容详情和 Feed base cache 长期持有

### 9）Phase 2 的固定准入门槛：没有这些信息就不准直接实现

前文 B 线保留，但这里只能作为候选池。任何一个 B 线新缓存点要真正进入开发，必须先补齐下面 6 项：

1. **接口级 owner**：真相源是谁
2. **共享字段边界**：哪些字段能进公共缓存，哪些不能
3. **key 设计**：精确到前缀和参数顺序
4. **TTL 设计**：精确到正值、空值、jitter 范围
5. **失效矩阵**：每个写操作删哪些 key
6. **降级语义**：Redis 挂了、下游挂了、JSON 坏了时怎么返回

没这 6 项，就说明方案还没成熟，不准改代码。

### 10）本次文档的最终验收标准

只有同时满足下面 8 条，这份文档才算真的“无歧义”：

1. 每个现有缓存点都说清了 owner
2. 每个现有缓存点都说清了共享字段边界
3. 每个现有缓存点都说清了 key 和 TTL
4. 每个现有缓存点都说清了 NOT_FOUND、失败、脏 key 三种语义
5. 每个现有缓存点都说清了并发 miss 怎么去重
6. 每个现有缓存点都说清了写后删哪些 key
7. 允许最终一致的地方，都写清了“允许陈旧多久、为什么允许”
8. 不能直接编码的 B 线点位，都明确标成“候选池，不是直接实现契约”

到这里，这份方案才配叫“可以交给实现模型照着写”的文档。
