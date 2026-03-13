# Nexus 社交推荐融合实施方案

文档日期：2026-03-07  
适用仓库：`project/nexus`  
适用对象：下一位 Codex / 实施工程师  
选型结果：**A 版（默认上线版）**  
推荐引擎：**Gorse 默认启用**  
文档性质：**执行稿，不是讨论稿**。除“可选优化”外，其余设计默认固定，不要再临场发明新方案。

---

## 0. 一句话目标

把 `nexus` 收敛成一套可上线的**社交推荐产品底座**：

1. `COMMENT` 点赞链路做到：**不丢 state、不丢事实、能做点赞列表**。
2. 用户关系改造成固定的“**第三方案**”：`user_relation` 做事实源，`user_follower` 做反向边，Redis 用 **ZSet** 做有序列表缓存，事件一致性走 **Outbox + Inbox**。
3. Feed/推荐继续用 `nexus` 做“**看什么**”的骨架，同时吸收 `zhiguang_be` 的缓存好品味，解决“**怎么快而稳地看出来**”。

---

## 1. 本文基于哪些源文档

本文只基于 `zhiguang文档` 里这 4 份文档的**最后总结/最终建议**，并结合 `nexus` 当前代码现状得出执行方案：

- `zhiguang文档/Feed流全链路与复现方案.md`
- `zhiguang文档/点赞系统全链路与复刻方案.md`
- `zhiguang文档/用户关系系统全链路与复现方案.md`
- `zhiguang文档/计数系统全链路与复刻方案.md`

**明确排除**：

- `zhiguang文档/搜索系统全链路与复现方案.md`

---

## 2. 从 4 份总结里得到的固定结论

### 2.1 Feed 文档给出的固定结论

可直接落成 3 句话：

1. `nexus` 更适合做“**决定看什么**”的骨架：它已经有 `feed:inbox`、`feed:outbox`、`feed:bigv:pool`、`feed:global:latest`、`feed:rec:session` 这些索引层能力。
2. `zhiguang_be` 更适合提供“**怎么快而稳地展示出来**”的方法：共享缓存不存用户态、TTL 抖动、热点续期、single-flight、局部旁路更新。
3. 个性化 Feed 不能做整页缓存；共享缓存里**不能放** `liked/followed/read/blocked` 这类用户态。

一句话收口：

> **`nexus` 决定“看什么”，`zhiguang_be` 优化“怎么快而稳地看出来”。**

### 2.2 点赞文档给出的固定结论

可直接落成 3 句话：

1. 如果产品需要“**点赞列表/谁点过赞**”，底座必须有 MySQL 事实表；这件事 `nexus` 的 `interaction_reaction` 先天更合适。
2. 当前 `nexus` 的 `COMMENT` 点赞存在两个真问题：
   - `state` 完全依赖 Redis bitmap，Redis 丢了就会读错。
   - 事实落库依赖延迟同步窗口，Redis 窗口期丢数据会导致 MySQL 缺事实。
3. 所以 `COMMENT` 点赞要改，但不能再继续搞“Redis 是唯一真相”的路线。

### 2.3 用户关系文档给出的固定结论

可直接落成 4 句话：

1. 不要原样照抄现有任一套方案。
2. 业务语义继续用 `nexus` 的 `user_relation`：因为它能表达关注与屏蔽，并继续作为关系事实源。
3. 列表缓存必须改成 **ZSet + cursor**，不能再用无序 `Set`。
4. 事件一致性必须加 **Outbox**；幂等必须统一成“一个事件只有一个 eventId”。

结论就是这句：

> **第三方案 = `user_relation` + `user_follower` + Redis ZSet + Outbox + 统一 eventId 幂等。**

### 2.4 计数文档给出的固定结论

可直接落成 3 句话：

1. `nexus` 更像“业务内嵌实时计数”，适合先把产品跑起来。
2. 现在不应该去搞一个通用大计数平台；那会把范围做炸。
3. 这轮只做两件真实有用的事：
   - 给 `COMMENT` 点赞补齐可恢复性。
   - 给 Feed 补齐最小必要的卡片统计，不搞大而全。

---

## 3. 最终决策

✅ **值得做**，原因只有 3 条：

1. 这 3 件事本质上共用同一套“真相在 DB、缓存只做加速、用户态不进共享缓存”的设计。
2. 现在的缺口都是真问题，不是纸上讨论：`COMMENT state` 会错、关系列表不能稳定分页、Feed 只有骨架没有成品展示层。
3. `nexus` 已经有足够多的现成骨架，不需要推倒重来，只需要把数据结构改对。

本方案的固定落地顺序是：

1. **先修 COMMENT 真相链路**。
2. **再落用户关系第三方案**。
3. **最后做 Feed 卡片层 + 用户态层 + 推荐融合路线**。

不要反过来做。因为 Feed 依赖前两件事提供稳定 state / list / fact。

---

## 4. Nexus 当前现状扫描（只写和本次需求直接相关的）

### 4.1 COMMENT 点赞现状

当前关键文件：

- `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/InteractionController.java`
- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/ReactionLikeService.java`
- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/ReactionCachePort.java`
- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/ReactionRepository.java`
- `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/CommentLikeChangedConsumer.java`
- `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/RootReplyCountChangedConsumer.java`

已经有的：

- 接口：`POST /api/v1/interact/reaction`、`GET /api/v1/interact/reaction/state`
- 事实表：`interaction_reaction`
- 计数表：`interaction_reaction_count`
- Redis 在线态：`interact:reaction:bm:*`、`interact:reaction:cnt:*`
- 评论派生事件：`CommentLikeChangedConsumer` 会异步更新 `interaction_comment.like_count`

当前真实缺口：

1. `ReactionLikeService.queryState()` 对 `COMMENT` 直接读 `reactionCachePort.getState()`，没有 DB fallback。
2. `ReactionCachePort` 只有 `getState()`，没有“bitmap shard key 是否存在”的接口，无法区分“真没点过”和“缓存丢了”。
3. `syncTarget()` 依赖 Redis `opsKey` 快照落库，意味着 Redis 窗口期丢数据时，MySQL 事实可能永远缺一段。
4. `ReactionCachePort` 的 Lua 在 `REMOVE` 时直接 `SETBIT 0`，即使本来就是 0，也可能制造一堆纯 0 shard key。

### 4.2 用户关系现状

> 2026-03-10 更新：
> 这部分口径已被 Phase B+ 收口结果部分覆盖。
> 当前现行实现是“数据库为唯一真相源，`IRelationAdjacencyCachePort` 只做 DB 读取门面”。
> 关系侧剩余工作只有 3 个：统一分页入口、同请求内复用 following、删除旧 offset 残留。
> 没有监控证据前，不允许恢复 `social:adj:*` 关系邻接缓存。

当前关键文件：

- `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/RelationController.java`
- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/RelationService.java`
- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/RelationAdjacencyCachePort.java`
- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/RelationCachePort.java`
- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/RelationEventPort.java`
- `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/listener/social/RelationEventListener.java`
- `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/config/RelationMqConfig.java`

已经有的：

- 事实表：`user_relation`
- 反向边：`user_follower`
- HTTP：`follow/unfollow/block`
- 关系查询门面：`IRelationAdjacencyCachePort`（当前实现已改为 DB 直读）

当前真实缺口：

1. `RelationAdjacencyCachePort` 现在用的是 **Set**，天然无序，不能稳定分页。
2. 事件现在是事务里直接发 MQ，没有 Outbox，DB 与 MQ 不一致是必然风险。
3. `relation_event_inbox` 的 schema 写的是 `NEW/PROCESSED/FAILED`，实现里却在用 `NEW/DONE/FAIL`，口径撕裂。
4. 当前源码里还残留 friend 相关实现，需要整体删除。
5. `user_follower` 表已经存在，但 Redis rebuild 还会回扫 `user_relation`，没有把反向表用到位。

### 4.3 Feed / 推荐现状

当前关键文件：

- `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/FeedController.java`
- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/FeedService.java`
- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/FeedInboxRebuildService.java`
- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/FeedTimelineRepository.java`
- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/FeedOutboxRepository.java`
- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/FeedBigVPoolRepository.java`
- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/FeedGlobalLatestRepository.java`
- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/FeedRecommendSessionRepository.java`
- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/GorseRecommendationPort.java`

已经有的：

- `FOLLOW`、`RECOMMEND`、`POPULAR`、`NEIGHBORS`、`PROFILE` 五种流入口
- 索引层 Redis key：
  - `feed:inbox:{userId}`
  - `feed:outbox:{authorId}`
  - `feed:bigv:pool:{bucket}`
  - `feed:global:latest`
  - `feed:rec:session:{userId}:{sessionId}`
- 推荐系统对接：`GorseRecommendationPort`
- 已读去重：`feed:follow:seen:{userId}`

当前真实缺口：

1. `FeedItemVO` / `FeedItemDTO` 只有 `postId/authorId/text/summary/publishTime/source`，还是“半成品流”。
2. 没有**共享卡片缓存层**；每次都是直接从内容仓储回表组装。
3. 没有**用户态层**；Feed 接口不返回 `liked/followed/seen`。
4. 没有把 `zhiguang_be` 那套“共享缓存不存用户态、局部失效、不做整页缓存”的经验吃进来。

---

## 5. 固定目标架构（本次直接照这个做）

### 5.1 五条硬规则

1. **真相在 DB，不在 Redis。**
2. **共享缓存不存用户态。**
3. **个性化 Feed 不做整页缓存。**
4. **关系列表必须有序，统一用 ZSet + cursor。**
5. **事件一致性用 Outbox + Inbox，不再事务内直接发 MQ。**

### 5.2 三层 Feed 结构（固定）

#### 第 1 层：索引层（决定看什么）

继续复用现有 `nexus` 结构：

- `feed:inbox:{userId}`
- `feed:outbox:{authorId}`
- `feed:bigv:pool:{bucket}`
- `feed:global:latest`
- `feed:rec:session:{userId}:{sessionId}`

这一层只返回 `postId + publishTime`，不要塞展示字段。

#### 第 2 层：卡片层（决定帖子长什么样）

新增 2 类共享缓存：

- `feed:card:{postId}`：`String(JSON)`，基础卡片
- `feed:card:stat:{postId}`：`Hash`，统计字段

**基础卡片只放稳定字段**：

- `postId`
- `authorId`
- `authorNickname`
- `authorAvatar`
- `text`
- `summary`
- `mediaType`
- `mediaInfo`
- `publishTime`

**统计卡片先只放当前可稳定支持的字段**：

- `likeCount`

说明：

- `commentCount`、`favoriteCount` 这轮**不强行补**。因为 `nexus` 当前没有现成、可靠、低成本的帖子级聚合源。不要为了好看把复杂度做炸。

#### 第 3 层：用户态层（决定“你和这条内容是什么关系”）

用户态单独查，最后拼：

- `liked`
- `followed`
- `seen`

**不进共享卡片缓存。**

### 5.3 主页推荐路线（固定）

首页默认走 `feedType=RECOMMEND`，内部按下面顺序补齐候选：

1. **Gorse 推荐 session 候选**（主源）
2. **关注图最近内容**（社交补位）
3. **BigV pool**（大量关注场景补位）
4. **popular**（热门补位）
5. **global latest**（最后兜底）

规则只有两条：

- 逐段补齐，不做复杂加权打分。
- 每段只负责“补不足”，统一按 `postId` 去重。

原因很简单：**先把可维护性做对，再谈复杂排序。**

---

## 6. COMMENT 点赞链路改造（固定方案）

### 6.1 本次明确选择的方案

**不照抄“Redis 延迟事实落库”方案。**

本次直接选：

> **`COMMENT` 的事实真相直接收回 MySQL；Redis 只做在线加速。**

这是这份文档里最重要的一个拍板。

原因：

1. 用户现在要的是“不丢 state、不丢事实、能做点赞列表”，不是极限吞吐。
2. `nexus` 当前没有 COMMENT 事实 outbox，继续走 Redis 窗口同步，只是把坑保留。
3. `COMMENT` 点赞量通常低于 `POST` 点赞，先换成简单可靠的路径，性价比最高。

### 6.2 COMMENT 目标行为

改完后，必须满足这 5 条：

1. 点赞/取消点赞后，MySQL `interaction_reaction` **立刻**是正确真相。
2. Redis bitmap 丢了时，`GET /interact/reaction/state` 仍然能从 DB 回答正确结果。
3. 点赞列表从 MySQL 可直接查，不依赖 bitmap 扫描。
4. `interaction_reaction_count` 不会因为 Redis 丢失而永久错误。
5. 现有 HTTP 协议不改：`POST /api/v1/interact/reaction`、`GET /api/v1/interact/reaction/state` 继续可用。

### 6.3 COMMENT 写链路（固定算法）

只改 `COMMENT` 分支；`POST` 分支保持现状。

#### 6.3.1 新的主流程

`InteractionController.react()`
→ `InteractionService.react()`
→ `ReactionLikeService.applyReaction()`
→ **新增** `applyCommentLike()`

`applyCommentLike()` 固定按下面顺序做：

1. 校验 target：
   - `targetType=COMMENT`
   - 评论存在
   - `status=1`
   - 只允许根评（保持当前业务约束）
2. 在 DB 中做 **set-state**：
   - `ADD`：`INSERT IGNORE interaction_reaction`
   - `REMOVE`：`DELETE FROM interaction_reaction ...`
3. 根据 DB 实际影响行数得到 `delta`：
   - `ADD` 插入成功 → `delta=+1`
   - `ADD` 重复已存在 → `delta=0`
   - `REMOVE` 删除成功 → `delta=-1`
   - `REMOVE` 原本不存在 → `delta=0`
4. 同事务更新 `interaction_reaction_count`：
   - `count = GREATEST(0, count + delta)`
5. 事务提交后，best-effort 更新 Redis：
   - bitmap 改成目标 state
   - `interact:reaction:cnt:{COMMENT...}` 改成事务后的 count
6. 如果 `delta != 0`：
   - 继续发布现有 `CommentLikeChangedEvent`，驱动 `interaction_comment.like_count` 和热榜更新
7. 返回：
   - `success=true`
   - `currentCount=事务后的 count`

### 6.4 COMMENT 读链路（固定算法）

?????COMMENT ???????? shard key ???????? bit??????? `bit=0` ????? bitmap shard key?

`ReactionLikeService.queryState()` 的 `COMMENT` 分支改成下面 3 步：

1. 先判断 bitmap shard key 是否存在：
   - 新增 `IReactionCachePort.bitmapShardExists(userId, target)`
2. 若 shard key 存在：
   - `GETBIT` 结果直接可信
3. 若 shard key 不存在：
   - 回查 `interaction_reaction exists`
   - 若 DB=true，则回灌 Redis bit=1

计数读取规则：

1. 先读 Redis `cntKey`
2. Redis 缺失时，从 `interaction_reaction_count` 回填
3. 若计数表也缺失，则 `SELECT COUNT(*) FROM interaction_reaction ...` 回填

### 6.5 点赞列表能力（本轮必须交付）

新增接口：

- `GET /api/v1/interact/reaction/likers`

请求参数：

- `targetType`：先只支持 `COMMENT`
- `targetId`
- `cursor`：`{updateTimeMs}:{userId}`
- `limit`：默认 20，最大 50

响应字段：

- `userId`
- `nickname`
- `avatar`
- `likedAt`
- `nextCursor`

排序规则：

- 按 `interaction_reaction.update_time DESC, user_id DESC`

说明：

- 这就是为什么必须新增索引，不能扫主键。

### 6.6 COMMENT 需要新增/修改的表与索引

#### 6.6.1 新增索引

```sql
ALTER TABLE `interaction_reaction`
  ADD KEY `idx_reaction_target_time`
    (`target_type`, `target_id`, `reaction_type`, `update_time`, `user_id`),
  ADD KEY `idx_reaction_user_reaction_time`
    (`user_id`, `target_type`, `reaction_type`, `update_time`, `target_id`);
```

### 6.7 COMMENT 需要改的代码文件

必改旧文件：

- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/ReactionLikeService.java`
- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/adapter/port/IReactionCachePort.java`
- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/ReactionCachePort.java`
- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/adapter/repository/IReactionRepository.java`
- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/ReactionRepository.java`
- `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/InteractionController.java`

新增文件建议：

- `project/nexus/nexus-api/src/main/java/cn/nexus/api/social/interaction/dto/ReactionLikersRequestDTO.java`
- `project/nexus/nexus-api/src/main/java/cn/nexus/api/social/interaction/dto/ReactionLikerDTO.java`
- `project/nexus/nexus-api/src/main/java/cn/nexus/api/social/interaction/dto/ReactionLikersResponseDTO.java`

### 6.8 COMMENT 验收标准

必须全部通过：

1. `COMMENT ADD` 后，`interaction_reaction` 立刻有事实行。
2. `COMMENT REMOVE` 后，事实行立刻消失。
3. 删掉某个 bitmap shard key 后，再查 `/interact/reaction/state`，结果仍正确；第二次查询不再打 DB。
4. `/interact/reaction/likers` 能按时间倒序返回点赞用户列表。
5. 连续 `ADD/ADD/REMOVE/REMOVE`，DB 最终状态正确，计数不会变负。

---

## 7. 用户关系第三方案（固定方案）

### 7.1 本次明确选择的方案（A 版：默认上线版）

补充约束：**关注是单向且无需审批**。用户 A 关注用户 B 后，A 立即成为 B 的粉丝。

> **事实源 = `user_relation`**  
> **反向边 = `user_follower`**  
> **缓存 = Redis ZSet**  
> **事件一致性 = Outbox + Inbox**  
> **发布实现 = Outbox Poller -> 现有 RabbitMQ**

A 版的固定边界：

1. 不上 Canal / Debezium / Kafka。
2. 不做独立关系图平台。
3. 不上 SDS 二进制计数，只做轻量 Hash 计数缓存。
4. 先把“事实正确、列表有序、事件不丢、接口可用”做对，再谈更大规模的优化。

不再保留以下坏味道：

- Redis `Set` 做列表缓存
- 事务里直接发 MQ
- Producer/Consumer 各自拼不同 fingerprint
- friend 整体删除，避免无效分支残留

### 7.2 关系写链路（固定算法）

适用于：

- `follow`
- `unfollow`
- `block`

事务内只做 3 件事：

1. 写 `user_relation`
2. 同步维护 `user_follower`
3. 写 `relation_event_outbox`

提交成功后，**同步 best-effort** 做 2 件事：

1. 立刻更新关系 ZSet 缓存（保证用户刚点完 follow/unfollow，列表和按钮尽快一致）
2. 立刻更新轻量计数缓存（不是事实源，只是加速）

真正异步做的只有：

1. 发 MQ
2. 下游 fanout
3. 非关键读侧派生

说明：

- Outbox 负责“事件不丢”，不负责“按钮立刻变”。
- Redis 更新失败不回滚主事务；后续读链路的 rebuild 负责自愈。
- 这比“所有缓存都等异步消费者来改”更适合当前阶段，因为你现在更需要稳定和直接，不需要把本来很简单的 UX 变成最终一致延迟问题。

### 7.3 关系读取与分页规则（现行）

> 2026-03-10 更新：
> 当前现行实现不再维护 Redis 邻接缓存。
> `followers/following` 统一通过 `IRelationAdjacencyCachePort` 走 DB keyset 分页。
> 唯一允许的优化是“同一次请求内显式复用 following 结果”。
> 不再新增 `social:adj:*` key，不再引入 `rebuildFollowing` / `rebuildFollowers`。
> 没有监控证据前，不允许恢复任何关系邻接缓存协议。

不要复用旧 `Set` key，直接上新 key，避免 Redis 类型冲突。

#### 7.3.1 新 key 设计

- `social:adj:following:z:{sourceId}`
  - member = `targetId`
  - score = `followCreateTimeMs`
- `social:adj:followers:z:{targetId}`
  - member = `followerId`
  - score = `followCreateTimeMs`

#### 7.3.2 cursor ??

?????

```text
{score}:{userId}
```

?????

- `score DESC`
- ??? `userId DESC`

?????B ????

- `followers/following` ??????????? **DB**?
- Redis `ZSet` ??????????
  - ?? best-effort ??
  - rebuild / ??
  - ???????
- ???????? Redis????? DB????????
- ?????????????????????????????? `ZSet` ??????

#### 7.3.3 关系计数策略（当前条件下最优，不用 SDS）

这轮**不要**引入 `zhiguang` 那套 SDS 二进制计数。

原因很简单：

1. `nexus` 当前最缺的是“关系事实、列表有序、事件一致性”，不是极限计数吞吐。
2. SDS 会把实现、排障、重建、运维都变复杂，不符合当前阶段“简单可控”的目标。
3. 大厂会在超大规模才上更重的计数结构；你现在没必要先背这个包袱。

本轮固定方案：

- Redis Key：`social:relation:count:{userId}`
- 类型：`Hash`
- 字段：
  - `followingCount`
  - `followerCount`
- TTL：`30 分钟 + 0~5 分钟随机抖动`

写策略：

- `follow ACTIVE`：`followingCount(source)+1`、`followerCount(target)+1`
- `unfollow`：对应 `-1`
- `block`：根据被清掉的边数做对应递减

读策略：

1. 先读 Redis Hash
2. 缺失时回源：
   - `followingCount` 从 `user_relation(type=FOLLOW,status=ACTIVE)` 统计
   - `followerCount` 从 `user_follower` 统计
3. 回填 Redis Hash

注意：

- 这是**缓存**，不是事实源。
- 缓存写失败不影响主事务。
- 计数出现负数时直接触发 DB 回填，不做“猜测修复”。

### 7.4 关系列表接口（本轮必须交付）

新增接口：

- `GET /api/v1/relation/following`
- `GET /api/v1/relation/followers`

请求参数：

- `userId`
- `cursor`
- `limit`（默认 20，最大 50）

响应字段：

- `userId`
- `nickname`
- `avatar`
- `followTime`
- `nextCursor`

### 7.5 关系状态批量接口（给 Feed 用户态层用）

?????`batchState` ??????????????? target ??????????????????? N ????

新增接口：

- `POST /api/v1/relation/state/batch`

请求：

```json
{
  "targetUserIds": [1001, 1002, 1003]
}
```

响应：

```json
{
  "followingUserIds": [1001, 1003],
  "blockedUserIds": [1002],
}
```

Feed 只需要 `followingUserIds`，但这里一次性把关系态查全，避免后面再开新接口。

### 7.6 Outbox 固定设计

新增表：`relation_event_outbox`

```sql
CREATE TABLE IF NOT EXISTS `relation_event_outbox` (
  `event_id` BIGINT NOT NULL,
  `event_type` VARCHAR(32) NOT NULL,
  `payload` TEXT NOT NULL,
  `status` VARCHAR(16) NOT NULL DEFAULT 'NEW',
  `retry_count` INT NOT NULL DEFAULT 0,
  `next_retry_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`event_id`),
  KEY `idx_relation_outbox_status_retry` (`status`, `next_retry_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关系事件 Outbox';
```

发布方式固定为：

- 在 `nexus-trigger` 增加一个 Poller
- 每次扫 `status=NEW` 且 `next_retry_time <= NOW()` 的记录
- 发到现有 `RelationMqConfig` 里对应 exchange/routingKey
- 成功后标 `DONE`
- 失败后 `retry_count+1`，并推进 `next_retry_time`

### 7.7 Inbox 固定规则

`relation_event_inbox` 不新建表，直接修口径：

1. `fingerprint` 不再自己拼，直接写 `String.valueOf(eventId)`
2. 状态统一收敛到 schema 里已经写的值：
   - `NEW`
   - `PROCESSED`
   - `FAILED`

**不要再用 `DONE/FAIL`。**

### 7.8 关系表与索引调整

新增索引：

```sql
ALTER TABLE `user_follower`
  ADD KEY `idx_follower_time` (`follower_id`, `create_time`, `user_id`);
```

friend 整体删除：

- 删除 `/relation/friend/request`、`/relation/friend/decision`
- 删除 `friend_request` 表、DAO、DTO、VO、事件与 MQ 分支
- 删除用户主页里的 `friendCount` 字段

### 7.9 关系需要改的代码文件

必改旧文件：

- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/RelationService.java`
- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/adapter/port/IRelationAdjacencyCachePort.java`
- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/RelationAdjacencyCachePort.java`
- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/RelationEventPort.java`
- `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/listener/social/RelationEventListener.java`
- `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/RelationController.java`

新增文件建议：

- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/adapter/repository/IRelationEventOutboxRepository.java`
- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/RelationEventOutboxRepository.java`
- `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/job/social/RelationEventOutboxPublishJob.java`
- `project/nexus/nexus-api/src/main/java/cn/nexus/api/social/relation/dto/RelationListRequestDTO.java`
- `project/nexus/nexus-api/src/main/java/cn/nexus/api/social/relation/dto/RelationUserDTO.java`
- `project/nexus/nexus-api/src/main/java/cn/nexus/api/social/relation/dto/RelationListResponseDTO.java`
- `project/nexus/nexus-api/src/main/java/cn/nexus/api/social/relation/dto/RelationStateBatchRequestDTO.java`
- `project/nexus/nexus-api/src/main/java/cn/nexus/api/social/relation/dto/RelationStateBatchResponseDTO.java`

### 7.10 关系验收标准

必须全部通过：

1. `follow` 后：`user_relation`、`user_follower`、`relation_event_outbox` 同事务落地。
2. `follow/unfollow` 提交后，关系 ZSet 与计数缓存会 best-effort 立即更新；即使失败，后续读链路也能自愈。
3. `followers/following` 列表可以稳定 cursor 翻页，不重复、不乱序。
4. `block` 后，双向 follow 都清理干净。
5. MQ 宕机期间，Outbox 会积压但不会丢；MQ 恢复后能继续发送。
6. Inbox 只按一个 `eventId` 去重，不再出现两种 fingerprint 语义。

---

## 8. Feed 卡片层 + 用户态层 + 推荐融合路线

### 8.1 这轮 Feed 要交付到什么程度

不要把目标说大。只交付这 4 件事：

1. `FeedItem` 升级成可直接上产品页的卡片。
2. 引入共享卡片缓存和卡片统计缓存。
3. 引入用户态层：`liked/followed/seen`。
4. 把 `RECOMMEND` 固定成“推荐主源 + 社交补位 + latest 兜底”的融合路线。

### 8.2 FeedItem 固定字段（本轮就按这个）

`FeedItemVO` / `FeedItemDTO` 统一升级为：

- `postId`
- `authorId`
- `authorNickname`
- `authorAvatar`
- `text`
- `summary`
- `mediaType`
- `mediaInfo`
- `publishTime`
- `source`
- `likeCount`
- `liked`
- `followed`
- `seen`

说明：

- `blocked` 不返回，直接在服务层过滤掉。
- `commentCount`、`favoriteCount` 先不做。

### 8.3 新增缓存契约

#### 8.3.1 基础卡片缓存

```text
feed:card:{postId}
```

- 类型：`String(JSON)`
- TTL：`30 分钟 + 0~10 分钟随机抖动`

#### 8.3.2 卡片统计缓存

```text
feed:card:stat:{postId}
```

- 类型：`Hash`
- 字段：
  - `likeCount`
- TTL：`10 分钟 + 0~3 分钟随机抖动`

#### 8.3.3 本地热点缓存（L1）

- 类型：Caffeine
- 对象：`feed:card:{postId}` 和 `feed:card:stat:{postId}`
- TTL：`2 秒`

目的不是长期缓存，而是挡住瞬时热点。

### 8.4 卡片组装固定流程

新增一个专用组装服务，建议命名：

- `FeedCardAssembleService`

固定流程：

1. Feed 索引层先拿到 `postId` 列表
2. 批量读 `feed:card:{postId}`
3. miss 的帖子：
   - `ContentRepository.listPostsByIds()`
   - `IUserBaseRepository.listByUserIds()`
   - 组基础卡片并回写 Redis
4. 批量读 `feed:card:stat:{postId}`
5. miss 的统计：
   - 从 `interaction_reaction_count(targetType=POST,reactionType=LIKE)` 读取
   - 组统计并回写 Redis
6. 批量补用户态：
   - `liked`：批量查当前用户是否点赞这些 `POST`
   - `followed`：批量查当前用户是否关注这些 author
   - `seen`：查 `feed:follow:seen:{userId}`
7. 组合成最终 `FeedItemVO`

### 8.5 用户态查询固定规则

#### 8.5.1 liked

新增内部批量查询方法：

- `IReactionRepository.batchExists(targetType=POST, reactionType=LIKE, userId, postIds)`

规则：

- 不去扫 Redis bitmap
- 直接走 DB + 必要缓存
- 因为 Feed 需要的是**可靠批量态**，不是单个按钮瞬时极限性能

#### 8.5.2 followed

优先走关系 ZSet / DB 批量判断：

- `IRelationQueryService.batchFollowing(sourceId, targetUserIds)`

#### 8.5.3 seen

继续复用：

- `feed:follow:seen:{userId}`

### 8.6 RECOMMEND 融合路线（固定，Gorse 默认启用）

`FeedService.recommendTimeline()` 改成“分段补齐”，不要再写成一个大杂烩循环。

固定顺序：

1. `feedRecommendSessionRepository` 读取当前 session 候选
2. session 不足时，调用 `recommendationPort.recommend(userId, n)` 追加个性化候选
3. 还不足时，调用 `recommendationPort.nonPersonalized("trending", n, offset)` 追加热门/趋势候选
4. 再不足时，追加“关注图最近内容”
5. 如果关注数过大，再追加 `feed:bigv:pool`
6. 最后不足，追加 `feed:global:latest`

游客或无稳定 userId 的会话，固定顺序改成：

1. `recommendationPort.sessionRecommend(sessionId, currentItems, n)`
2. `recommendationPort.nonPersonalized("latest", n, offset)`
3. `feed:global:latest`

相关推荐固定走：

1. `recommendationPort.itemToItem("similar", seedPostId, n)`
2. 同类内容兜底
3. `feed:global:latest`

统一过滤：

- 去重 `postId`
- 过滤已 block 作者
- 过滤不存在/已删除帖子

关键约束：

- 不再把 Gorse 写成“可有可无”的外挂；A 版里它是默认推荐主引擎。
- 不能继续硬编码旧的 `/api/popular` 接口；热门/趋势统一走 Gorse 的 non-personalized 推荐器。
- Gorse 不可用时，系统必须自动退回“关注图补位 + global latest”，不能空白。

### 8.7 Gorse 接口契约（下一位 Codex 必须先改这里）

当前 `IRecommendationPort` / `GorseRecommendationPort` 只保留了旧抽象：

- `recommend(userId, n)`
- `popular(userId, n, offset)`
- `neighbors(postId, n)`

A 版 + Gorse 默认启用版，必须升级成下面这组固定接口：

- `recommend(Long userId, int n)`
  - 个性化推荐
- `nonPersonalized(String name, Long userId, int n, int offset)`
  - 热门 / 趋势 / latest / 榜单
- `sessionRecommend(String sessionId, List<Long> currentItemIds, int n)`
  - 游客 / 会话推荐
- `itemToItem(String name, Long itemId, int n)`
  - 相似内容 / 相关推荐
- `upsertItem(...)`
- `insertFeedback(...)`
- `deleteItem(...)`

固定映射规则：

- 旧 `popular()` 不再继续保留为 HTTP `/api/popular` 直连抽象。
- 旧 `neighbors()` 改名为更清楚的 `itemToItem()`。
- `RECOMMEND`、`POPULAR`、`NEIGHBORS` 这些 feedType 是上层业务名字，不应该反过来绑死 Gorse 的 HTTP 路径名字。

### 8.8 Gorse 逐文件执行清单

必须按这个顺序改：

1. 改接口：
   - `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/adapter/port/IRecommendationPort.java`
2. 改 Gorse 适配器：
   - `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/GorseRecommendationPort.java`
3. 改推荐流调用点：
   - `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/FeedService.java`
4. 补配置：
   - `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/config/FeedRecommendProperties.java`
   - 增加 non-personalized 名称配置，例如：`trendingRecommenderName`、`latestRecommenderName`、`similarRecommenderName`
5. 补启动检查：
   - 启动时校验 Gorse baseUrl 不为空；为空则打印明确 warning，并自动切到 fallback 路线
6. 补测试：
   - `recommend` 命中测试
   - `nonPersonalized("trending")` 命中测试
   - `itemToItem("similar")` 命中测试
   - `sessionRecommend()` 命中测试
   - Gorse 不可用时 fallback 测试

### 8.9 Follow 流保留的规则

`FOLLOW` 流继续保留：

- inbox 首页 miss 时重建
- 关注图内容优先
- 已读过滤 `feed:follow:seen:{userId}`

但要加一条：

- 最终组卡时，必须走新的卡片层 + 用户态层，不能继续返回“裸 FeedItem”。

### 8.10 Feed 需要改的代码文件

必改旧文件：

- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/model/valobj/FeedItemVO.java`
- `project/nexus/nexus-api/src/main/java/cn/nexus/api/social/feed/dto/FeedItemDTO.java`
- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/FeedService.java`
- `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/FeedController.java`

新增文件建议：

- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/FeedCardAssembleService.java`
- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/adapter/repository/IFeedCardRepository.java`
- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/adapter/repository/IFeedCardStatRepository.java`
- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/FeedCardRepository.java`
- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/FeedCardStatRepository.java`
- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/RelationQueryService.java`

### 8.11 Feed 验收标准

必须全部通过：

1. `FOLLOW`、`RECOMMEND`、`PROFILE` 都返回完整卡片字段，而不是旧的裸字段。
2. Redis 共享卡片 JSON 里没有 `liked/followed/seen`。
3. 点赞/关注状态变化后，不需要整页失效；最多失效单张 stat 或用户态查询结果。
4. `RECOMMEND` 在推荐源为空时，仍能按既定顺序补出内容，不会空白页。

---

## 9. 里程碑与执行顺序（下一位 Codex 必须照这个顺序做）

### M1：COMMENT 可靠点赞

目标：先把“state/事实/点赞列表”做对。

交付物：

- COMMENT 改成 DB 真相
- state DB fallback + 回灌
- likers 列表接口
- 相关索引迁移脚本

完成标志：第 6 章全部验收通过。

### M2：用户关系第三方案

目标：把“无序 Set + 事务内直发 MQ”换成“ZSet + Outbox”。

交付物：

- ZSet 列表缓存
- following/followers cursor 列表接口
- relation state batch 接口
- relation outbox poller
- requestId 改成 snowflake

完成标志：第 7 章全部验收通过。

### M3：Feed 卡片层

目标：把 Feed 从骨架升级成产品卡片。

交付物：

- FeedItem 字段升级
- card/card:stat 缓存
- 组卡服务

完成标志：`/api/v1/feed/timeline` 可以直接用于前端卡片渲染。

### M4：RECOMMEND 融合路线

目标：把推荐流固定成“推荐主源 + 社交补位 + latest 兜底”。

交付物：

- 新的 recommend append 顺序
- 批量用户态拼装
- block 过滤

完成标志：推荐服务挂掉时，首页仍可回退到社交/全站兜底内容。

---

## 10. 明确不做的事（防止下一位 Codex 自己加戏）

这轮**不要做**：

1. 不做统一大计数平台。
2. 不做 Feed 整页缓存。
3. 不做把 `liked/followed/read` 塞进共享 Redis JSON。
4. 不做关系列表的 offset 分页，统一 cursor 即可。
5. 不修搜索系统。
6. 不扩 `COMMENT` 点赞到二级回复；继续只允许根评点赞。
7. 不保留任何 friend 相关历史兼容逻辑。
8. 不上 Canal / Debezium / Kafka 版关系事件链路。
9. 不做独立关系图平台或图数据库改造。

---

## 11. 开发时的验证命令

在 `project/nexus` 下执行：

```bash
mvn -pl nexus-domain,nexus-infrastructure,nexus-trigger,nexus-api -am test
```

如果没有足够测试，再至少补下面 3 类集成测试：

1. COMMENT 点赞可靠性测试
2. 关系 cursor 列表测试
3. Feed 组卡与回退链路测试

---

## 12. 最终给决策者的话

如果你要的是“社交推荐产品”，这轮最正确的路不是再找一套新系统，而是把 `nexus` 现有骨架的三处关键数据结构改对：

1. **COMMENT 点赞真相回到 DB**。
2. **关系列表回到 ZSet + Outbox**。
3. **Feed 展示拆成索引层 / 卡片层 / 用户态层**。

这样做完后：

- `nexus` 继续负责“看什么”
- `zhiguang_be` 的经验负责“怎么又快又稳”

这才是你要的融合路线。

