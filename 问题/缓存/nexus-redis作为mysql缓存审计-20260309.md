# Nexus 项目 Redis 作为 MySQL 缓存的审计总结

- 日期：2026-03-09
- 执行者：Codex
- 项目路径：`C:\Users\Administrator\Desktop\文档\project\nexus`

## 先说结论

这次我把项目里的 Redis 用法分成两类：

1. **直接回表型缓存**：先查 Redis，没命中再查 DAO / MySQL，然后回填 Redis。
2. **派生读侧缓存**：先查 Redis，没命中时不是直接查一个 DAO，而是重新装配一份读模型，再写回 Redis。

你要的“Redis 作为 MySQL 缓存来提升 QPS”，核心看第一类；第二类我也单独列出来，因为它确实能减少回表和装配成本，但语义上不该和第一类混写。

## 常见问题翻译成大白话

- **缓存穿透**：一直查一个根本不存在的数据，结果每次都打到 MySQL。
- **缓存击穿**：一个特别热的数据刚好过期，大量请求一起打到 MySQL。
- **缓存雪崩**：很多缓存差不多同一时间过期，数据库瞬间被打爆。
- **热点读**：少数 key 被读得特别猛，数据库扛不住。
- **批量回表放大**：一次查很多 id，如果缓存做得差，就会一次性放大很多 SQL。
- **跨节点不一致**：一台机器删了本地缓存，别的机器还拿旧数据。

## 一、直接回表型缓存

### 1）内容帖子批量缓存 `ContentRepository.listPostsByIds`

- 代码位置：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/ContentRepository.java:195`
- 相关位置：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/ContentRepository.java:232`
- Redis 方案：`StringRedisTemplate + Value.multiGet + JSON + NULL 负缓存`
- 关键细节：先查 L1（热点 key 才进 Caffeine），再对 miss 的 postId 做 Redis `multiGet`；Redis 再 miss 才批量 `selectByIds` 回表；DB 命中后回写 Redis，DB 仍 miss 则写 `NULL` 负缓存。- 回源证据：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/ContentRepository.java:233`
- 回写证据：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/ContentRepository.java:260`
- 负缓存证据：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/ContentRepository.java:266`
- 它主要解决：批量回表放大、缓存穿透、热点读。
- 它对击穿/雪崩的处理：有负缓存，但**没有显式互斥锁/单飞**；Redis 正常值 TTL 是固定 `60s`，这里**没有 TTL 抖动**，所以对雪崩的处理比较弱。

### 2）内容详情缓存 `ContentDetailQueryService.query`

- 代码位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/support/ContentDetailQueryService.java:33`
- 查询入口：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/support/ContentDetailQueryService.java:52`
- Redis 方案：`L1 Caffeine + L2 Redis String(JSON) + NULL 负缓存 + TTL 抖动`
- 关键细节：先查本地 L1，再查 Redis；Redis miss 后回源 `contentRepository.findPostMeta`，再并行补作者、点赞数、正文内容，组装成详情 DTO 后回写 Redis。
- 空值处理：不存在的帖子会写 `NULL`，证据在 `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/support/ContentDetailQueryService.java:69`
- TTL：本地缓存 `1h`，Redis 正常缓存 `1d`，空值缓存 `90s`，并且 Redis 正常值带抖动，证据在 `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/support/ContentDetailQueryService.java:36`
- 它主要解决：缓存穿透、热点读、重复详情装配、部分缓存雪崩。
- 它对一致性的处理：更新后走“双删 + 延迟删 + MQ 广播清本地缓存”，证据在 `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/cache/ContentCacheEvictPort.java:54` 和 `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/ContentCacheEvictConsumer.java:19`
- 它没有真正解决：高并发同 key miss 时的严格击穿问题，因为这里也没有看到单飞锁。

### 3）评论视图缓存 `CommentRepository.listByIds`

- 代码位置：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/CommentRepository.java:83`
- Redis 方案：`L1 Caffeine + L2 Redis multiGet + JSON + NULL 负缓存 + TTL 抖动`
- 关键细节：先查本地 L1；miss 的 commentId 会批量 Redis `multiGet`；Redis miss 再批量查 `commentDao`；命中后回填 L1/L2；数据库仍 miss 就写 `NULL`。
- 证据：L1 命中逻辑在 `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/CommentRepository.java:87`，Redis `multiGet` 在 `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/CommentRepository.java:111`，DB 回源说明在 `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/CommentRepository.java:140`。- 回写证据：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/CommentRepository.java:517`
- 负缓存证据：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/CommentRepository.java:522`
- 它主要解决：批量回表放大、缓存穿透、热点评论读取、部分雪崩。
- 它没有真正解决：同一批 miss 并发回源的单飞问题。

### 4）评论回复预览缓存 `CommentRepository` reply preview

- 代码位置：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/CommentRepository.java:298`
- Redis 方案：`Redis String(JSON 数组) + L1 Caffeine + TTL 抖动`
- 关键细节：按照 `rootId + limit` 作为 key，先查 Redis 里的回复 id 预览列表；miss 再回表 `commentDao.pageReplyIds(...)`；结果回写 Redis。
- DB 回源证据：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/CommentRepository.java:318`
- 回写证据：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/CommentRepository.java:583`
- 它主要解决：评论楼中楼预览的重复分页 SQL、热点根评论的回表压力。
- 它对穿透的处理：能写 `NULL`，但这里更偏“短结果缓存”，不是严格布隆过滤器方案。

### 5）帖子作者映射缓存 `PostAuthorPort.getPostAuthorId`

- 代码位置：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/PostAuthorPort.java:24`
- Redis 方案：`Redis String + cache-aside + TTL 抖动`
- 关键细节：先查 Redis，miss 后走 `contentPostDao.selectUserId(postId)`，再回写 Redis。
- DB 回源证据：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/PostAuthorPort.java:39`
- TTL：`1d + 抖动`，证据在 `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/PostAuthorPort.java:18`
- 它主要解决：同一帖子被反复查作者时的重复 SQL。
- 它没有解决：缓存穿透，因为没有对不存在 postId 写负缓存。

### 6）关系计数缓存 `RelationCachePort`

- 代码位置：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/RelationCachePort.java:12`
- Redis 方案：`Redis Hash + cache-aside + 写时增量修正 + TTL 抖动`
- 关键细节：关注数 / 粉丝数先查 Hash 字段；miss 后分别走 `relationRepository.countActiveRelationsBySource` 和 `relationRepository.countFollowerIds` 回表；写操作时直接对缓存做 `increment` 修正，删关系时支持 `evict`。
- 证据：读取 following 在 `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/RelationCachePort.java:28`，读取 follower 在 `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/RelationCachePort.java:42`。- TTL：`30min + 0~5min 抖动`，证据在 `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/RelationCachePort.java:21`
- 它主要解决：高频计数查询、部分缓存雪崩、写后读立刻不一致。
- 它对击穿的处理：没有互斥锁，但因为 count 可以直接增量修正，实际比纯 cache-aside 稳一点。

### 7）关系邻接分页缓存 `RelationAdjacencyCachePort`

- 代码位置：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/RelationAdjacencyCachePort.java:20`
- Redis 方案：`Redis ZSet（按 followTime 排序） + 缺失/不完整时全量重建`
- 关键细节：关注列表、粉丝列表都放在 ZSet；分页直接走 Redis 有序范围查询；如果 key 不存在，或者 `zsetSize < dbCount`，就触发重建；重建时通过 `followerDao.selectFollowingRows` / `selectFollowerRows` 分页拉 MySQL 再灌回 Redis。
- 重建证据：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/RelationAdjacencyCachePort.java:85`
- 自愈证据：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/RelationAdjacencyCachePort.java:160`
- Redis 分页证据：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/RelationAdjacencyCachePort.java:165`
- 它主要解决：关注/粉丝分页的排序 SQL、深翻页压力、热点用户列表查询。
- 它部分解决：缓存丢失或半丢失后的自愈问题。
- 它没有明显解决：雪崩（没看到 TTL 抖动），也没有单 key 互斥重建锁，所以重建期可能有重复回源。

### 8）点赞计数缓存 `ReactionCachePort.getCount`

- 代码位置：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/ReactionCachePort.java:144`
- Redis 方案：`Redis 计数 Key + 位图状态 + Lua 原子更新 + 热点 L1 Caffeine + DB 重建`
- 关键细节：读计数时先看热点 L1，再查 Redis 计数；如果 Redis 没有计数，就走 `interactionReactionCountDao` 从 MySQL 聚合表重建，再写回 Redis；写点赞/取消点赞则通过 Lua 原子修改 Redis 计数和状态。
- 读重建证据：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/ReactionCachePort.java:158`
- DB 重建证据：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/ReactionCachePort.java:313`
- 热点 L1 证据：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/ReactionCachePort.java:58`
- 它主要解决：热点点赞数读取、并发写计数原子性、重复聚合 SQL。
- 它部分解决：击穿，因为 Redis 里长期维护计数；但严格说仍没有“同一 miss 只允许一个线程重建”的锁。

## 二、派生读侧缓存（补充说明）这些不是最标准的“查 Redis miss 就查一个 MySQL DAO”模式，但它们确实在读侧减少了重复装配和间接回表，所以值得单独记住。

### 9）Feed 卡片基础信息缓存 `FeedCardRepository`

- 代码位置：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/FeedCardRepository.java:18`
- Redis 方案：`L1 Caffeine + Redis String(JSON) + TTL 抖动`
- 调用链证据：miss 后会在 `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/FeedCardAssembleService.java:111` 进入装配，进一步调用 `contentRepository.listPostsByIds`，证据在 `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/FeedCardAssembleService.java:122`
- 它主要解决：Feed 卡片重复装配、间接减少帖子回表和用户信息查询。
- 说明：**我把它归到补充项，不算最纯粹的 MySQL 前置缓存。**

### 10）Feed 卡片统计缓存 `FeedCardStatRepository`

- 代码位置：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/FeedCardStatRepository.java:17`
- Redis 方案：`L1 Caffeine + Redis Hash + TTL 抖动`
- 调用链证据：miss 后在 `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/FeedCardAssembleService.java:165` 重新装配，最终走 `reactionRepository.getCount`，证据在 `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/FeedCardAssembleService.java:179`
- 它主要解决：Feed 场景下重复读点赞统计。
- 说明：它更像“读模型统计缓存”，不是直接绑一个 DAO 的 cache-aside。

## 三、这次没有算进来的 Redis 用法

下面这些我看到了，但**不应该算进“Redis 作为 MySQL 缓存”**：

- `FeedGlobalLatestRepository`：这是 Redis ZSet 主存，用来保存全站最新流，不是 MySQL 前置缓存，位置在 `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/FeedGlobalLatestRepository.java:17`
- `FeedAuthorCategoryRepository`：这是作者分类状态仓库，不是回表缓存，位置在 `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/FeedAuthorCategoryRepository.java:12`
- 搜索回填 checkpoint、推荐 session、已读状态、ID 生成器等 Redis 用法：都更像状态存储或流程控制，不是 MySQL 缓存。

## 四、总体观察

- **做得比较好的地方**
  - 很多核心读路径都用了 `L1 + L2 + 回源` 结构，方向是对的。
  - 多处用了 `NULL 负缓存`，这对防缓存穿透很有价值。
  - 多处用了 `TTL 抖动`，这对缓解雪崩有帮助。
  - 内容详情这条链路还做了“双删 + 延迟删 + MQ 广播清本地缓存”，一致性意识明显比普通项目强。

- **明显缺口**
  - 大多数缓存点**没有显式互斥锁/单飞控制**，所以严格意义上的缓存击穿并没有被彻底解决。
  - 有些缓存点有 TTL 抖动，有些没有，策略不统一。
  - `PostAuthorPort` 这种简单缓存没有负缓存，对不存在数据会反复回表。
  - `RelationAdjacencyCachePort` 走的是“发现不完整就重建”，实用，但重建期间可能重复打库。

## 五、一句话总结

如果只看“Redis 作为 MySQL 前置缓存提升 QPS”的主战场，**最关键的落点**是：`ContentRepository`、`ContentDetailQueryService`、`CommentRepository`、`RelationCachePort`、`RelationAdjacencyCachePort`、`ReactionCachePort`、`PostAuthorPort`。

其中：

- **解决穿透最明显**：`ContentRepository`、`ContentDetailQueryService`、`CommentRepository`
- **解决雪崩最明显**：`ContentDetailQueryService`、`CommentRepository`、`RelationCachePort`
- **缓解热点最明显**：`ReactionCachePort`、`ContentRepository`、`RelationAdjacencyCachePort`
- **一致性处理最完整**：`ContentDetailQueryService + ContentCacheEvictPort`
- **对击穿处理最弱的共性问题**：普遍缺少单飞锁 / rebuild 锁

## 六、高并发缺陷清单

这一节只列**高并发下会出问题**的实现缺陷，不重复写“平时也可能有的小瑕疵”。重点看两件事：

1. **会不会在同一个 miss 上重复回源 / 重复重建**
2. **重建或失效过程是不是原子的**，会不会出现空窗、半成品、脏读

### 1）内容帖子批量缓存 `ContentRepository.listPostsByIds`

- 风险等级：高
- 主要缺陷：**同一批 `postId` miss 时没有 single-flight / 互斥重建控制，会发生批量重复回表**。
- 高并发下的表现：
  - 多个请求同时发现 L1 miss、L2 miss
  - 会同时进入 `selectByIds(dbMissIds)`
  - 然后各自再把同一批帖子写回 Redis
- 缺陷形成的关键点：
  - 读路径是 `L1 -> Redis multiGet -> DB selectByIds -> 回填 Redis`
  - 这条链路里**没有按批次 key 或 postId 做单飞去重**
  - 正常缓存 TTL 是固定 `60s`，空值 TTL 是固定 `30s`，**没有 TTL 抖动**，同批 key 容易在同一时间集中过期
- 本质上属于：**批量击穿 / 惊群式回源**，不是强一致问题，而是 DB QPS 放大问题
- 证据位置：
  - `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/ContentRepository.java:165`
  - `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/ContentRepository.java:202`
  - `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/ContentRepository.java:233`
  - `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/ContentRepository.java:65`

### 2）内容详情缓存 `ContentDetailQueryService.query`

- 风险等级：中高
- 主要缺陷：**详情 miss 后没有 single-flight，同一个 `postId` 会被多个线程同时做整套详情装配**。
- 高并发下的表现：
  - 多个请求同时 miss 后，会同时查 `findPostMeta`
  - 同时并发拉作者信息、点赞数、正文内容
  - 最后重复写同一个详情缓存 key
- 缺陷形成的关键点：
  - 有 L1、本地缓存，有 L2、Redis，有 `NULL` 负缓存，也有 TTL 抖动
  - 但**没有“同一个 postId 只允许一个线程重建详情”的机制**
  - 所以它防了一部分雪崩，却**没有彻底解决详情重建风暴**
- 补充说明：这个点的一致性做得比别的地方强，但那是“写后删缓存”强，不是“并发重建去重”强
- 证据位置：
  - `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/support/ContentDetailQueryService.java:52`
  - `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/support/ContentDetailQueryService.java:80`
  - `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/support/ContentDetailQueryService.java:87`
  - `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/support/ContentDetailQueryService.java:115`

### 3）评论批量缓存 `CommentRepository.listByIds`

- 风险等级：高
- 主要缺陷：**和帖子批量缓存同型：批量 miss 没有 single-flight，会重复查 DB、重复写 Redis**。
- 高并发下的表现：
  - 热评论列表同时 miss 时，多请求一起打 `commentDao.selectByIds(stillMiss)`
  - 然后重复写 L2 和 L1
- 缺陷形成的关键点：
  - `multiGet` 之后直接 `selectByIds`
  - 没有按 `commentId` 或批次 key 做重建互斥
  - L2 TTL 只有几秒，虽然带轻微 jitter，但在热点评论下仍然容易频繁重建
- 本质上属于：**批量评论读模型的击穿 / 重建风暴**
- 证据位置：
  - `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/CommentRepository.java:83`
  - `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/CommentRepository.java:111`
  - `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/CommentRepository.java:141`

### 4）评论回复预览缓存 `CommentRepository.pageReplyCommentIds`

- 风险等级：高
- 主要缺陷一：**预览缓存 miss 没有单飞，会重复查 DB、重复写预览缓存**。
- 主要缺陷二：**写路径没有看到对应的预览缓存失效，高并发读写混合时容易短时间读到旧预览**。
- 高并发下的表现：
  - 多个请求同时发现预览 key 不存在，会一起查 `pageReplyIdsFromDb`
  - 评论新增、审核通过、删除之后，预览缓存可能还保留旧 id 列表，直到 TTL 自然过期
- 缺陷形成的关键点：
  - 读路径是 `L1 -> Redis -> DB -> writeReplyPreviewIdsCache`
  - 但 `insert / approvePending / rejectPending / softDelete` 这些写路径附近，**没有明确删 reply preview 缓存**
  - 也没有 rebuild 锁，所以“重建”和“数据变化”会并发交错
- 这是这份文档里**最像会直接被用户感知到旧数据**的一个点
- 证据位置：
  - `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/CommentRepository.java:287`
  - `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/CommentRepository.java:311`
  - `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/CommentRepository.java:175`
  - `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/CommentRepository.java:223`

### 5）帖子作者缓存 `PostAuthorPort.getPostAuthorId`

- 风险等级：中
- 主要缺陷：**没有负缓存，也没有单飞；查不到时会反复回表**。
- 高并发下的表现：
  - 多个请求同时查一个不存在或暂时取不到作者映射的 `postId`
  - 每次都会重新打 `contentPostDao.selectUserId(postId)`
- 缺陷形成的关键点：
  - 只有“正向命中后写缓存”
  - `userId == null` 直接返回，不写 `NULL`
  - 所以它对“不存在数据”的高并发场景没有缓冲垫
- 这是典型的：**轻量 cache-aside 做了命中优化，但没做穿透防护**
- 证据位置：
  - `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/PostAuthorPort.java:23`
  - `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/PostAuthorPort.java:36`

### 6）关系计数缓存 `RelationCachePort`

- 风险等级：中
- 主要缺陷：**同一用户的关注数 / 粉丝数 miss 时没有统一重建，字段级缓存会重复回源**。
- 高并发下的表现：
  - 多个请求同时读 `followingCount` 或 `followerCount`，可能同时打各自的 DB count
  - 某些时刻 Redis Hash 里可能只有一个字段是热的，另一个字段仍然是 miss
- 缺陷形成的关键点：
  - 两个字段独立 `get -> miss -> DB -> put`
  - 没有按 `userId` 做单飞或整对象重建
  - 写路径的 `increment` 是增量修正，不等于“首次 miss 的并发重建去重”
- 这个点更多是**重复回源和局部半热**问题，不是强一致灾难
- 证据位置：
  - `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/RelationCachePort.java:28`
  - `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/RelationCachePort.java:44`
  - `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/RelationCachePort.java:82`

### 7）关系邻接表缓存 `RelationAdjacencyCachePort`

- 风险等级：最高
- 主要缺陷一：**发现缓存不完整就直接重建，但没有锁，多个请求可能同时重建同一个 ZSet**。
- 主要缺陷二：**重建是“先 delete 再一页页 add 回来”，不是原子替换，存在空窗和半成品窗口**。
- 高并发下的表现：
  - 请求 A 发现 `zCard < dbCount`，开始重建
  - 请求 B 同时也发现不完整，也开始重建
  - 重建期间别的读请求可能读到空集合，或者只读到前几页已经写回的半成品集合
- 缺陷形成的关键点：
  - `ensureFollowingCache / ensureFollowerCache` 的判定条件是“key 不存在或 zsetSize < dbCount”
  - 一旦判定不完整，就直接 `rebuild*`
  - `rebuild*` 里先 `delete`，再分页从 DB 拉取并 `zadd`
  - 全程**没有 rebuild 锁、没有双 buffer、没有最终 rename/swap**
- 这不是单纯的 QPS 问题，**是高并发下可能读到空或半成品数据的问题**
- 证据位置：
  - `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/RelationAdjacencyCachePort.java:145`
  - `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/RelationAdjacencyCachePort.java:85`
  - `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/RelationAdjacencyCachePort.java:89`
  - `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/RelationAdjacencyCachePort.java:151`

### 8）点赞计数缓存 `ReactionCachePort.getCount`

- 风险等级：中高
- 主要缺陷：**计数 key miss 时没有 single-flight，会有重复 DB 重算**。
- 高并发下的表现：
  - 多个请求同时发现 `cntKey` 不存在
  - 会同时打 `interactionReactionCountDao.selectCount(...)`
  - 然后重复把结果写回 Redis
- 缺陷形成的关键点：
  - `redisGetCntOrRebuild` 是 `get -> miss -> DB count -> set`
  - Lua 主要服务于点赞状态写入和增量原子更新，不等于“读 miss 重建去重”
  - 热点 L1 只有在“已经被识别为热点且本地已有值”时才有效，挡不住冷启动第一波 miss
- 这是**热点计数读的击穿问题**，不是写原子性问题
- 证据位置：
  - `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/ReactionCachePort.java:158`
  - `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/ReactionCachePort.java:297`
  - `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/ReactionCachePort.java:313`

### 9）Feed 卡片基础信息缓存 `FeedCardRepository`

- 风险等级：中
- 主要缺陷：**缓存 miss 之后没有重建去重，会把上层卡片装配链路压力放大**。
- 高并发下的表现：
  - 多个 Feed 请求同时 miss 同一批 `postId`
  - 当前仓储层只是“返回 miss，让上层继续 assemble”
  - 结果是同一批卡片会被重复装配、重复保存
- 缺陷形成的关键点：
  - `getBatch` 逐个读 L1/L2，没有负缓存、没有 single-flight
  - `saveBatch` 是事后回填，不是过程去重
- 这类问题通常不会直接把 DB 打爆，但会放大**装配层和下游依赖**的压力
- 证据位置：
  - `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/FeedCardRepository.java:37`
  - `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/FeedCardRepository.java:52`
  - `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/FeedCardRepository.java:69`

### 10）Feed 卡片统计缓存 `FeedCardStatRepository`

- 风险等级：中
- 主要缺陷：**和 `FeedCardRepository` 一样，miss 只会把压力往上抛，没有做并发重建去重**。
- 高并发下的表现：
  - 多个 Feed 请求同时 miss 同一批统计数据
  - 会重复触发上层重新装配统计，再重复写缓存
- 缺陷形成的关键点：
  - `getBatch` 逐个读 Hash 字段
  - miss 后没有负缓存、没有 single-flight、没有共享“正在重建中的结果”
- 这个点更多是**统计读放大和装配放大**，不是数据错乱
- 证据位置：
  - `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/FeedCardStatRepository.java:34`
  - `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/FeedCardStatRepository.java:48`
  - `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/FeedCardStatRepository.java:64`

### 11）这一批缺陷里，最值得优先修的 4 个点

- **第一优先级：`RelationAdjacencyCachePort`**
  - 因为它不只是“重复回源”，而是**可能在重建窗口读到空或半成品数据**。
- **第二优先级：`ContentRepository.listPostsByIds`**
  - 因为它是 Feed / 卡片装配的底层批量回表点，最容易把 QPS 放大到 DB。
- **第三优先级：`CommentRepository` 回复预览缓存**
  - 因为这里不仅有并发重建问题，**还疑似存在写后不失效导致的短暂旧数据问题**。
- **第四优先级：`ContentDetailQueryService.query`**
  - 因为详情页 miss 后的装配链路很长，并发 miss 时会放大多路下游调用成本。

### 12）一句话总结这一节

这一批 Redis 缓存方案**大多数能在平时省 QPS，但没有系统性解决“同 key / 同批 key 的并发 miss 重建”**；少数点还叠加了**重建非原子**或**失效不完整**的问题，所以在高并发下真正的风险不是“缓存没用”，而是：**重复回源、重建风暴、局部脏读、半成品窗口**。

## 七、修复优先级与改造建议

这一节不讲空话，只讲当前代码结构里最值得做、而且能落地的改法。

### 1）总原则：先止血，再优化

- **第一原则：先把“同一个 miss 被重建很多次”止住**
  - 也就是先补 `single-flight / rebuild 锁`
  - 因为这类问题最容易在高并发下把 DB 或装配链路打爆
- **第二原则：重建过程要么原子切换，要么至少不能先删后慢慢建**
  - 否则就会出现空窗、半成品、短暂脏读
- **第三原则：统一 TTL 策略**
  - 热点 key 要么续期，要么加 jitter，不要一半有、一半没有
- **第四原则：写路径必须配套失效路径**
  - 只做读缓存、不做写后删缓存，迟早会在高并发下暴露旧数据

### 2）第一优先级：给批量回源点补 single-flight

适用位置：
- `ContentRepository.listPostsByIds`
- `CommentRepository.listByIds`
- `ContentDetailQueryService.query`
- `ReactionCachePort.redisGetCntOrRebuild`

建议做法：
- 以“缓存 key”作为 single-flight 粒度
- 单条对象就按 `postId/commentId/target.hashTag()` 做
- 批量对象不要直接锁整个方法，而是：
  - 方案 A：按单个 id 分拆重建
  - 方案 B：按排序后的批次 id 列表生成稳定批次 key
- 锁内必须二次检查缓存：
  - `先查一次缓存 -> miss -> 进锁 -> 再查一次缓存 -> 还 miss 才回源`

这样改的好处：
- 不会把所有请求串行化
- 只合并“同一份数据”的重建
- 能直接压住并发击穿和惊群式回源

### 3）第二优先级：把非原子重建改成“构建新副本再切换”

适用位置：
- `RelationAdjacencyCachePort`

建议做法：
- 不要再用“`delete old key -> 分页写回 old key`”
- 改成：
  - 先写临时 key，例如 `following:{userId}:rebuild:{ts}`
  - 全量写完、校验完数量
  - 最后一次性 `rename/swap` 成正式 key
- 重建期间要配 rebuild 锁，避免多个线程同时做同一份重建
- 如果实现成本要低一点，至少也要：
  - 重建时打标记
  - 读请求看到“正在重建”时优先走旧 key，而不是直接读半成品新 key

这样改的好处：
- 不会出现“空集合窗口”
- 不会出现“只写回前几页”的半成品窗口
- 对用户可见行为更稳

### 4）第三优先级：补全写后失效，尤其是评论预览缓存

适用位置：
- `CommentRepository.pageReplyCommentIds` 的 reply preview 缓存

建议做法：
- 在这些写路径后补删缓存：
  - `insert`
  - `approvePending`
  - `rejectPending`
  - `softDelete`
  - `softDeleteByRootId`
- 删除范围至少覆盖：
  - 当前 `rootId` 相关的 reply preview key
  - 本地 L1 预览缓存
- 如果 limit 有多个档位，例如 3、5、10，都要一起删，不要只删一种 limit

这样改的好处：
- 避免评论已经变了，但预览列表还停留在旧版本
- 避免把一致性问题完全甩给 TTL 自然过期

### 5）第四优先级：统一 TTL 抖动和负缓存策略

建议补齐：
- `ContentRepository.listPostsByIds`：正常值 TTL 建议改成“基础 TTL + 抖动”
- `PostAuthorPort`：建议补 `NULL` 负缓存
- `FeedCardRepository / FeedCardStatRepository`：如果 miss 很常见，也应考虑短 TTL 负缓存或更上层的 single-flight

建议原则：
- 热点基础对象：TTL 要带 jitter
- 不存在对象：给短 TTL 负缓存
- 高价值热点对象：必要时配热点续期

### 6）建议的改造顺序

- **第一步**：先改 `ContentRepository.listPostsByIds`
  - 因为它是多个上层读链路的公共底座，收益最大
- **第二步**：改 `RelationAdjacencyCachePort`
  - 因为它风险最高，已经不是单纯的 QPS 问题，而是可能读到空或半成品
- **第三步**：改 `CommentRepository` 的 reply preview 缓存失效
  - 因为这个问题最容易被用户直接感知成“评论展示不对”
- **第四步**：改 `ContentDetailQueryService.query`
  - 让详情装配也有 single-flight，减少多路下游被同时打爆
- **第五步**：补 `PostAuthorPort / ReactionCachePort / FeedCard*` 的统一策略
  - 这些属于第二梯队，适合在第一波止血后统一收口

### 7）一句话总结这一节

如果只允许做最少的改动，那最值钱的动作不是“再加更多缓存”，而是：**给重建过程加去重、给重建结果加原子切换、给写路径补齐失效**。这三件事做好了，当前这批 Redis 缓存方案在高并发下的稳定性会明显上一个台阶。

## 八、无歧义实现计划（执行契约）

这一节不是“建议”，而是后续实现时必须遵守的**执行契约**。为了避免 Codex 在实现时自己做技术选择，下面把关键选择全部写死。

### 1）先把所有不可自由发挥的选择写死

- **不新增任何 Maven 依赖，不新增任何模块，不引入外部中间件。**
- **除 `RelationAdjacencyCachePort` 之外，所有“并发 miss 重建去重”一律使用“进程内 single-flight”。**
- **进程内 single-flight 的唯一实现方式固定为：`ConcurrentHashMap<String, CompletableFuture<T>>`。**
- **不新增通用 shared util 类。每个类各自维护自己的 inflight map 和 private helper。**
- **不修改任何现有 public interface 的方法签名。** 所有改动限定在：新增 private helper、private field、private constant；必要时仅新增 package-private test helper，不能改业务接口。
- **`RelationAdjacencyCachePort` 不允许继续使用“delete 正式 key 后再慢慢重建正式 key”方案。** 必须改成“临时 key 构建完成后原子切换”。
- **`RelationAdjacencyCachePort` 的重建锁固定使用 Redis 分布式锁，不使用本地锁。** 原因：这个类的重建结果对多实例是共享的，本地锁不够。
- **评论 reply preview 缓存的失效范围固定为 `limit=1..10` 全删。** 不能只删某一个 limit。
- **测试框架固定为现有项目风格：JUnit 5 + Mockito。** 不引入 Testcontainers，不引入嵌入式 Redis。
- **`nexus-trigger` 当前没有 test 目录。** 后续实现时，测试目录固定新建为：`project/nexus/nexus-trigger/src/test/java`。

### 2）Phase 1：先改这 5 个点，别跳顺序

固定顺序如下：
1. `ContentRepository.listPostsByIds`
2. `ContentDetailQueryService.query`
3. `CommentRepository`（批量缓存 + reply preview 失效）
4. `RelationAdjacencyCachePort`
5. `ReactionCachePort.getCount`

说明：
- `PostAuthorPort`、`RelationCachePort`、`FeedCardAssembleService` 放到 Phase 2。
- 这样排的原因不是“它们不重要”，而是先把最容易放大 DB/QPS 和最容易产生用户可见脏读的点止住。

### 3）Phase 1 详细计划：`ContentRepository.listPostsByIds`

固定改动文件：
- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/ContentRepository.java`

固定实现方式：
- 新增 field：`private final ConcurrentHashMap<String, CompletableFuture<Map<Long, ContentPostEntity>>> postBatchInflight = new ConcurrentHashMap<>();`
- 删除旧常量：`POST_REDIS_TTL_SECONDS`、`POST_REDIS_NULL_TTL_SECONDS`
- 替换为 4 个新常量，数值固定如下：
  - `POST_REDIS_TTL_BASE_SECONDS = 60L`
  - `POST_REDIS_TTL_JITTER_SECONDS = 15L`
  - `POST_REDIS_NULL_TTL_BASE_SECONDS = 30L`
  - `POST_REDIS_NULL_TTL_JITTER_SECONDS = 10L`
- 新增 private 方法，方法名固定如下：
  - `private Duration postTtl()`
  - `private Duration postNullTtl()`
  - `private String buildPostBatchFlightKey(List<Long> ids)`
  - `private Map<Long, ContentPostEntity> reloadPostsByIdsWithSingleFlight(List<Long> candidateIds, ValueOperations<String, String> valueOps)`
  - `private Map<Long, ContentPostEntity> reloadPostsByIdsInsideFlight(List<Long> candidateIds, ValueOperations<String, String> valueOps)`

`buildPostBatchFlightKey` 的规则固定如下：
- 输入必须是“已去重、非空、升序排序”的 `List<Long>`
- key 格式固定为：`post:batch:` + 逗号拼接后的 id 列表
- 不做 hash，不做 UUID，不做 JSON 序列化

`listPostsByIds` 的改法固定如下：
1. 保留现有的 L1 读取逻辑。
2. 保留现有的 Redis `multiGet` 逻辑。
3. 当 `dbMissIds` 为空时，直接返回，不进入 single-flight。
4. 当 `dbMissIds` 非空时，**不得直接 `selectByIds`**，必须改为调用 `reloadPostsByIdsWithSingleFlight(dbMissIds, valueOps)`。
5. `reloadPostsByIdsWithSingleFlight` 的 leader / waiter 逻辑固定为：
   - `future = new CompletableFuture<>()`
   - `existing = postBatchInflight.putIfAbsent(flightKey, future)`
   - 若 `existing != null`，直接 `return existing.join()`
   - 若自己是 leader，则执行 `reloadPostsByIdsInsideFlight`
   - 执行成功后 `future.complete(result)`
   - 执行失败后 `future.completeExceptionally(ex)`
   - `finally` 中必须执行 `postBatchInflight.remove(flightKey, future)`
6. `reloadPostsByIdsInsideFlight` 内部**必须先再次查一次 Redis**，只对“锁内二次检查后仍 miss 的 id”打 DB。
7. 写回 Redis 时，正常值 TTL 固定使用 `postTtl()`，空值 TTL 固定使用 `postNullTtl()`。
8. 不允许改变现有返回顺序，最终仍按输入 `postIds` 原顺序组装返回。

### 4）Phase 1 详细计划：`ContentDetailQueryService.query`

固定改动文件：
- `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/support/ContentDetailQueryService.java`

固定实现方式：
- 新增 field：`private final ConcurrentHashMap<Long, CompletableFuture<ContentDetailResponseDTO>> detailInflight = new ConcurrentHashMap<>();`
- 不修改现有 TTL 数值：
  - `LOCAL_TTL = 1h`
  - `REDIS_TTL = 1d`
  - `REDIS_NULL_TTL = 90s`
- 新增 private 方法，方法名固定如下：
  - `private ContentDetailResponseDTO loadWithSingleFlight(Long postId)`
  - `private ContentDetailResponseDTO reloadInsideFlight(Long postId, String redisKey)`
  - `private ContentDetailResponseDTO waitDetailFuture(CompletableFuture<ContentDetailResponseDTO> future)`
  - `private RuntimeException unwrapRuntime(Throwable ex)`

`query` 的改法固定如下：
1. 保留现有 `postId == null` 参数校验。
2. 保留现有 L1 查询。
3. 保留现有 Redis 查询。
4. 若 Redis 命中正常值，仍旧 `localCache.put(postId, dto)` 后返回。
5. 若 Redis 命中 `NULL`，仍旧抛 `AppException(NOT_FOUND)`。
6. 只有在 L1 miss 且 L2 miss 时，才调用 `loadWithSingleFlight(postId)`。

`loadWithSingleFlight` 的实现规则固定如下：
- key 直接使用 `postId`
- 仍采用 leader / waiter 模式，与 `ContentRepository` 相同
- waiter 不允许直接 `future.join()` 后把 `CompletionException` 原样往外抛
- waiter 必须调用 `waitDetailFuture`，在其中把 `AppException` 原样抛出，其他异常包装成 `RuntimeException`

`reloadInsideFlight` 的实现规则固定如下：
1. 先再次查 L1；若命中直接返回。
2. 再再次查 Redis；若命中正常值则回填 L1 后返回。
3. 若 Redis 为 `NULL`，直接抛 `AppException(NOT_FOUND)`。
4. 只有锁内二次检查仍然 miss，才执行当前已有的装配逻辑：
   - `findPostMeta`
   - `loadAuthor`
   - `loadLikeCount`
   - `loadContent`
   - 组装 `ContentDetailResponseDTO`
5. 组装完成后，必须先 `cacheValue(redisKey, dto)`，再 `localCache.put(postId, dto)`。
6. `post == null` 场景，必须先 `cacheNull(redisKey)`，再抛 `AppException(NOT_FOUND)`。

### 5）Phase 1 详细计划：`CommentRepository`

固定改动文件：
- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/CommentRepository.java`

这一个类要做三件事，顺序固定：
1. 给 `listByIds` 补 batch single-flight
2. 给 comment view 补写后失效
3. 给 reply preview 补写后失效

`listByIds` 的固定改法：
- 新增 field：`private final ConcurrentHashMap<String, CompletableFuture<Map<Long, CommentViewVO>>> commentBatchInflight = new ConcurrentHashMap<>();`
- 新增 private 方法：
  - `private String buildCommentBatchFlightKey(List<Long> ids)`
  - `private Map<Long, CommentViewVO> reloadCommentsWithSingleFlight(List<Long> stillMiss)`
  - `private Map<Long, CommentViewVO> reloadCommentsInsideFlight(List<Long> stillMiss)`
- key 规则固定为：`comment:batch:` + 升序排序后的 id 逗号串
- `reloadCommentsInsideFlight` 必须先再次查 Redis，再查 DB，再写回 Redis/L1
- 现有 TTL 常量保持不变，不改数值

comment view 写后失效的固定改法：
- 新增 private 方法：`private void evictCommentViewCache(Long commentId)`
- 逻辑固定为：
  - `commentViewCache.invalidate(commentId)`
  - `stringRedisTemplate.delete(commentViewRedisKey(commentId))`
  - 两步都包 `try/catch`，异常吞掉，不影响主链路
- 在以下方法成功后调用 `evictCommentViewCache`：
  - `approvePending(commentId, nowMs)`
  - `rejectPending(commentId, nowMs)`
  - `softDelete(commentId, nowMs)`
  - `addReplyCount(rootCommentId, delta)`
  - `addLikeCount(rootCommentId, delta)`
- `insert` 不需要删 comment view，因为新评论此时旧缓存不存在

reply preview 写后失效的固定改法：
- 新增 private 方法：`private void evictReplyPreviewCaches(Long rootId)`
- `evictReplyPreviewCaches` 的规则固定如下：
  - 若 `rootId == null`，直接返回
  - 循环 `limit = 1..10`
  - 对每个 limit：
    - `replyPreviewIdsCache.invalidate(rootId + ":" + limit)`
    - `stringRedisTemplate.delete(replyPreviewRedisKey(rootId, limit))`
- 在以下方法成功后调用 `evictReplyPreviewCaches`：
  - `insert(...)`：仅当 `rootId != null` 时调用
  - `softDeleteByRootId(rootId, nowMs)`：直接调用 `evictReplyPreviewCaches(rootId)`
  - `approvePending(commentId, nowMs)`：先 `CommentBriefVO brief = getBrief(commentId)`，成功后若 `brief.getRootId() != null` 则删 `brief.getRootId()` 的 preview
  - `rejectPending(commentId, nowMs)`：规则同上
  - `softDelete(commentId, nowMs)`：规则同上
- 这里不允许实现成“只删本次 limit”，必须全删 `1..10`

### 6）Phase 1 详细计划：`RelationAdjacencyCachePort`

固定改动文件：
- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/RelationAdjacencyCachePort.java`

这个类的方案固定为：**Redis 分布式锁 + 临时 key 全量构建 + 写路径双写临时 key + rename 原子切换**。

固定新增常量如下：
- `KEY_REBUILD_LOCK_PREFIX = "social:adj:rebuild:lock:"`
- `KEY_REBUILD_META_PREFIX = "social:adj:rebuild:meta:"`
- `TMP_KEY_TTL_SECONDS = 300L`
- `REBUILD_LOCK_TTL_SECONDS = 30L`

固定新增方法如下：
- `private String rebuildLockKey(String formalKey)`
- `private String rebuildMetaKey(String formalKey)`
- `private boolean tryAcquireRebuildLock(String formalKey, String token)`
- `private void releaseRebuildLock(String formalKey, String token)`
- `private void mirrorAddIfRebuilding(String formalKey, String member, long score)`
- `private void mirrorRemoveIfRebuilding(String formalKey, String member)`
- `private void rebuildFollowingAtomically(Long sourceId)`
- `private void rebuildFollowersAtomically(Long targetId)`

锁实现规则固定如下：
- `tryAcquireRebuildLock` 使用 `setIfAbsent(lockKey, token, Duration.ofSeconds(REBUILD_LOCK_TTL_SECONDS))`
- `releaseRebuildLock` 不允许直接 `delete(lockKey)`
- `releaseRebuildLock` 必须用 Lua 做 compare-and-delete：只有 value 等于 token 才删

重建元数据规则固定如下：
- `rebuildMetaKey(formalKey)` 的 value 固定存放“当前临时 key 的完整名字”
- meta key TTL 固定为 `300s`
- temp key 命名固定为：`formalKey + ":rebuild:" + token`

`addFollow/removeFollow` 的固定改法：
- 保留对正式 key 的写入
- 然后必须执行对临时 key 的镜像写入：
  - `addFollow`：调用 `mirrorAddIfRebuilding(followingKey(sourceId), targetId.toString(), score)` 和 `mirrorAddIfRebuilding(followersKey(targetId), sourceId.toString(), score)`
  - `removeFollow`：调用 `mirrorRemoveIfRebuilding(...)`
- `mirror*IfRebuilding` 的规则固定为：
  - 先读 `rebuildMetaKey(formalKey)`
  - 若没有 meta key，直接返回
  - 若有 tmpKey，则把同样的 `zadd / zrem` 操作同步打一份到 tmpKey

`ensureFollowingCache/ensureFollowerCache` 的固定改法：
- 不再直接调 `rebuildFollowing/rebuildFollowers`
- 改为分别调用 `rebuildFollowingAtomically(sourceId)`、`rebuildFollowersAtomically(targetId)`
- 只有在“正式 key 不存在”或“`zCard < dbCount`”时才进入原子重建

`rebuildFollowingAtomically/rebuildFollowersAtomically` 的固定步骤如下：
1. 生成 `token = UUID.randomUUID().toString()`
2. 尝试拿 Redis rebuild lock；拿不到锁就直接返回，不重建
3. 拿到锁后，再次读取 `hasKey + zCard + dbCount`，若已完整则直接释放锁并返回
4. 计算 `formalKey`
5. 计算 `tmpKey = formalKey + ":rebuild:" + token`
6. 写入 `metaKey(formalKey) = tmpKey`，TTL 300 秒
7. 逐页从 DB 拉数据，全部写入 `tmpKey`
8. 写完后读取 `zCard(tmpKey)`，再读一次最新 `dbCountAfterLoad`
9. 若 `tmpSize < dbCountAfterLoad`，删除 `tmpKey`、删除 `metaKey`、释放锁、直接返回，不切换
10. 若数据完整，执行 `redisTemplate.rename(tmpKey, formalKey)`
11. `rename` 后立刻执行 `redisTemplate.persist(formalKey)`，去掉 temp TTL
12. 删除 `metaKey`
13. 用 Lua compare-and-delete 释放锁

保留规则：
- `rebuildFollowing(Long sourceId)` 和 `rebuildFollowers(Long targetId)` 这两个 public 方法保留，但内部实现必须只委托给新的 `rebuildFollowingAtomically` / `rebuildFollowersAtomically`，不能再保留旧逻辑。

### 7）Phase 1 详细计划：`ReactionCachePort.getCount`

固定改动文件：
- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/ReactionCachePort.java`

固定实现方式：
- 新增 field：`private final ConcurrentHashMap<String, CompletableFuture<Long>> reactionCountInflight = new ConcurrentHashMap<>();`
- 新增 private 方法：
  - `private long redisGetCntOrRebuildWithSingleFlight(ReactionTargetVO target)`
  - `private long redisGetCntOrRebuildInsideFlight(ReactionTargetVO target)`
- `getCount` 和 `getCountFromRedis` 都必须改为调用 `redisGetCntOrRebuildWithSingleFlight`
- 现有 Lua 写路径逻辑完全不动，只改“读 miss 重建”这条路
- single-flight key 固定使用 `cntKey(target)`
- `redisGetCntOrRebuildInsideFlight` 的步骤固定如下：
  1. 先再次 `get cntKey`
  2. 若此时已命中，直接返回
  3. 仍 miss 才执行 `interactionReactionCountDao.selectCount(...)`
  4. 查到结果后回写 Redis
  5. 返回 count

### 8）Phase 2：补齐剩余 4 个点

#### 8.1 `PostAuthorPort`
- 新增 `NULL_VALUE = "NULL"`
- 新增 `NULL_TTL_BASE_SECONDS = 30L`
- 新增 `NULL_TTL_JITTER_SECONDS = 10L`
- 新增 field：`private final ConcurrentHashMap<Long, CompletableFuture<Long>> authorInflight = new ConcurrentHashMap<>();`
- 读路径固定改法：
  - 先查 Redis
  - 若命中 `NULL`，直接返回 `null`
  - miss 时走 single-flight
  - 锁内二次查 Redis
  - DB 仍为 null，则回写 `NULL`
  - DB 有值则回写作者 id

#### 8.2 `RelationCachePort`
- 新增 field：`private final ConcurrentHashMap<String, CompletableFuture<Long>> relationCountInflight = new ConcurrentHashMap<>();`
- key 固定使用 `key(userId) + ":" + fieldName`
- `getFollowingCount/getFollowerCount` 的 miss 路径统一改成 single-flight
- `adjust` 逻辑不改，只改读 miss 路径

#### 8.3 `FeedCardAssembleService`（不是 `FeedCardRepository`）
- 这里固定选择在**装配服务层**做 single-flight，不在仓储层做。原因：仓储层只返回命中，真正的 miss 装配发生在服务层。
- 固定改动文件：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/FeedCardAssembleService.java`
- 新增两个 field：
  - `private final ConcurrentHashMap<String, CompletableFuture<Map<Long, FeedCardBaseVO>>> baseCardInflight = new ConcurrentHashMap<>();`
  - `private final ConcurrentHashMap<String, CompletableFuture<Map<Long, FeedCardStatVO>>> statCardInflight = new ConcurrentHashMap<>();`

- `loadBaseCards` 的改法固定为：
  - `feedCardRepository.getBatch(candidateIds)` 之后收集 `missIds`
  - 若 `missIds` 非空，调用 `loadBaseMissWithSingleFlight(missIds)`
  - leader 负责：`contentRepository.listPostsByIds(missIds)` -> `userBaseRepository.listByUserIds(authorIds)` -> 组装 `FeedCardBaseVO` -> `feedCardRepository.saveBatch(toSave)`
  - waiter 直接拿 future 结果，不重复装配
- `loadStatCards` 的改法固定为：
  - `feedCardStatRepository.getBatch(candidateIds)` 之后收集 `missIds`
  - 若 `missIds` 非空，调用 `loadStatMissWithSingleFlight(missIds)`
  - leader 负责：逐个调 `reactionRepository.getCount(target)` -> 组装 `FeedCardStatVO` -> `feedCardStatRepository.saveBatch(toSave)`
  - waiter 直接拿 future 结果
- batch key 固定为：`feed-card-base:` / `feed-card-stat:` + 升序排序后的 missIds 逗号串

### 9）测试计划：文件名、场景、断言全部写死

固定新增 / 修改测试文件如下：
- `project/nexus/nexus-infrastructure/src/test/java/cn/nexus/infrastructure/adapter/social/repository/ContentRepositoryTest.java`
- `project/nexus/nexus-infrastructure/src/test/java/cn/nexus/infrastructure/adapter/social/repository/CommentRepositoryTest.java`
- `project/nexus/nexus-infrastructure/src/test/java/cn/nexus/infrastructure/adapter/social/port/RelationAdjacencyCachePortTest.java`
- `project/nexus/nexus-infrastructure/src/test/java/cn/nexus/infrastructure/adapter/social/port/ReactionCachePortTest.java`
- `project/nexus/nexus-trigger/src/test/java/cn/nexus/trigger/http/social/support/ContentDetailQueryServiceTest.java`
- `project/nexus/nexus-domain/src/test/java/cn/nexus/domain/social/service/FeedCardAssembleServiceTest.java`

固定测试风格：
- 全部使用 JUnit 5 + Mockito
- 并发测试统一使用：`CountDownLatch startLatch + CountDownLatch doneLatch + AtomicInteger dbCallCount`
- 不使用真实 Redis，不使用真实数据库，全部 mock

每个测试类必须覆盖的场景固定如下：
- `ContentRepositoryTest`
  1. 两个线程同时 miss 同一批 `postIds`，`contentPostDao.selectByIds` 只允许被调用 1 次
  2. leader 回写 Redis 后，waiter 直接拿 future 结果，不允许再打 DB
  3. 正常值 TTL 采用 `60~75s`，空值 TTL 采用 `30~40s`
- `ContentDetailQueryServiceTest`
  1. 两个线程同时 miss 同一 `postId`，`findPostMeta` 只允许被调用 1 次
  2. `post == null` 时，waiter 也必须拿到 `AppException(NOT_FOUND)`
  3. leader 已写入 Redis 后，第二个请求命中 L2，不再装配
- `CommentRepositoryTest`
  1. 两个线程同时 miss 同一批评论，`commentDao.selectByIds` 只允许 1 次
  2. `insert(reply)` 成功后，`limit=1..10` 的 reply preview key 全部被删除
  3. `softDeleteByRootId(rootId)` 成功后，`limit=1..10` 的 reply preview key 全部被删除
  4. `addLikeCount/addReplyCount` 成功后，对应 comment view key 被删除

- `RelationAdjacencyCachePortTest`
  1. `ensureFollowingCache` 发现不完整时，只允许一个线程拿到 rebuild lock
  2. rebuild 期间 `addFollow/removeFollow` 会同步镜像到 tmpKey
  3. `rename` 完成后正式 key 存在且无 TTL（`persist` 生效）
  4. build 不完整时禁止切换正式 key
- `ReactionCachePortTest`
  1. 两个线程同时 miss 同一 `cntKey`，`selectCount` 只允许被调用 1 次
  2. Redis 已命中时不走 DB
- `FeedCardAssembleServiceTest`
  1. 两个线程同时 miss 同一批 base cards，只允许走 1 次 `contentRepository.listPostsByIds`
  2. 两个线程同时 miss 同一批 stat cards，只允许走 1 次完整的统计装配循环

### 10）实施边界：后续实现时禁止自行发挥的点

- 不允许把 `single-flight` 换成 `synchronized(this)` 或方法级大锁。
- 不允许把 batch key 改成无序 key。
- 不允许为了省事去掉“锁内二次检查缓存”。
- 不允许把 `RelationAdjacencyCachePort` 简化回“delete 正式 key 再 rebuild 正式 key”。
- 不允许只删某一个 reply preview limit。
- 不允许把 Phase 2 的卡片装配 single-flight 错放到 `FeedCardRepository` 仓储层。
- 不允许修改 public interface 签名来迁就实现。
- 不允许新增依赖或引入分布式任务框架。

### 11）如果实现中出现下面这些情况，必须立即中断并让我选择

只有下面 3 种情况允许中断：
- **情况 A**：`StringRedisTemplate` 在当前项目版本里无法安全使用 `rename / persist / execute Lua` 组合，导致 `RelationAdjacencyCachePort` 的原子切换方案无法按本文实现。
- **情况 B**：`ReactionCachePort` 的现有单测或依赖关系表明，`getCount` 读路径不能安全改为 future-based single-flight，会破坏已有同步语义。
- **情况 C**：`FeedCardAssembleService` 的调用方要求“同一次 assemble 中必须立刻看到用户态实时字段变化”，而 single-flight 结果共享会破坏这个前提。

除这 3 种情况外，后续实现不得中断询问，必须严格按本节执行。
