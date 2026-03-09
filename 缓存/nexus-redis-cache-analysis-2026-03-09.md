# Nexus 项目 Redis 缓存机会分析

- 日期：2026-03-09
- 执行者：Codex
- 范围：`project/nexus`

## 一句话结论

Nexus 不是没有 Redis，而是缓存只打在了部分点位上。单条详情、计数、时间线索引已经做了，但用户主页聚合、关系列表、评论首屏、搜索建议、通知页、推荐上游结果这些高频读链路还没有形成完整缓存闭环，继续加 Redis 是值得做的。

## 已经做过缓存的地方

- 内容详情双层缓存：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/support/ContentDetailQueryService.java`
- Feed 卡片基础信息缓存：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/FeedCardRepository.java`
- Feed 点赞统计缓存：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/FeedCardStatRepository.java`
- 评论详情与回复预览缓存：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/CommentRepository.java`
- 关系计数缓存：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/RelationCachePort.java`
- 关系邻接缓存：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/RelationAdjacencyCachePort.java`
- 评论热榜 ZSET：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/CommentHotRankRepository.java`
- Feed inbox/outbox/global latest/bigV pool：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository`

## 已确认最值得新增的缓存位置

### 1）用户资料读取

- 现状：`UserProfileRepository.get` 每次查 DB，但写路径 `updatePatch` 会删 Redis key，说明这里只做了失效，没有真正读缓存。
- 文件：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/user/repository/UserProfileRepository.java`
- 建议 key：`user:profile:{uid}`
- TTL：`30m + 随机抖动`
- 失效：资料更新后删除 key
- 理由：用户资料在个人中心、主页、评论/Feed 作者信息补全里复用很高，属于高频读低频写。
- 解决问题：降低 DB QPS；空值缓存可防穿透；TTL 抖动可防雪崩。

### 2）用户状态读取

- 现状：`UserStatusRepository.getStatus` 纯 DB 查询。
- 文件：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/user/repository/UserStatusRepository.java`
- 建议 key：`user:status:{uid}`
- TTL：`10m + 随机抖动`
- 失效：状态更新后删除 key
- 理由：状态对象很小，读很多，写很少。
- 解决问题：减少主页、个人中心、发帖校验重复查库。

### 3）用户主页聚合结果

- 现状：`UserProfilePageQueryService.query` 会拼 `profile + status + followCount + followerCount + followState + risk`。
- 文件：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/user/service/UserProfilePageQueryService.java`
- 建议拆成两层：
  - 公共层：`user:profile:page:base:{targetUid}`
  - 个性层：`relation:follow:state:{viewerUid}:{targetUid}`
- TTL：公共层 `30~120s`，个性层 `30~60s`
- 失效：资料变更、状态变更、处罚变更、关注关系变更时删除对应 key
- 理由：这是典型聚合查询，后端 fan-out 多，重复计算浪费大。
- 解决问题：降低聚合链路放大；避免把个性字段混进公共缓存导致 key 爆炸。

### 4）关注/粉丝列表查询

- 现状：`RelationQueryService.following/followers` 还在直接走 DB 分页；但项目已经有 `RelationAdjacencyCachePort.pageFollowing/pageFollowers`。
- 文件：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/RelationQueryService.java`
- 建议：直接改走现有邻接 Redis 分页能力，不重新造轮子。
- 理由：现成缓存设施已经存在，现在没接上就是浪费。
- 解决问题：削掉大 V 或活跃用户关系页的 DB 分页压力。

### 5）拉黑/隐私判断

- 现状：
  - `BlacklistPort.isBlocked` 每次点查关系表
  - `RelationPolicyPort.needApproval` 每次查隐私表
- 文件：
  - `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/BlacklistPort.java`
  - `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/RelationPolicyPort.java`
- 建议 key：
  - `relation:block:{source}:{target}` 或 `relation:block:set:{uid}`
  - `user:privacy:needApproval:{uid}`
- TTL：`1~10m`
- 理由：主页、关系、互动、私信前置校验都会碰到。
- 解决问题：减少高频 point-select；set membership 很适合布尔关系判断。

### 6）评论首屏与热评首屏聚合

- 现状：评论详情和回复预览已经缓存，但 `CommentQueryService.listRootComments/hotComments` 每次仍重新拼整页。
- 文件：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/CommentQueryService.java`
- 建议 key：
  - `comment:pin:{postId}`
  - `comment:root:first:{postId}:{limit}`
  - `comment:hot:first:{postId}:{limit}:{preload}`
- TTL：`5~15s`
- 失效：评论新增、删除、审核通过、置顶变化、热榜更新时删 key
- 理由：评论区首屏复用率高，尤其热门帖。
- 解决问题：避免评论首屏反复打 DB；短 TTL + 抖动防击穿与雪崩。
- 备注：只建议缓存首屏公共结果，不建议缓存深翻页与强 viewer 个性结果。

### 7）个人主页 Feed 首屏

- 现状：`FeedService.profile` -> `ContentRepository.listUserPosts` 仍是 DB 分页。
- 文件：
  - `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/FeedService.java`
  - `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/ContentRepository.java`
- 建议 key：`feed:profile:first:{uid}:{limit}`
- TTL：`10~30s + 抖动`
- 失效：发帖、删帖、状态变化、可见性变化时删第一页 key
- 理由：首页复用高，深翻页复用低。
- 解决问题：降低主页反复打开时的 DB 压力。

### 8）搜索建议

- 现状：`SearchService.suggest` 每次直打搜索引擎 completion。
- 文件：
  - `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/SearchService.java`
  - `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/SearchEnginePort.java`
- 建议 key：`search:suggest:{prefix}:{limit}`
- TTL：`30~120s`
- 空值缓存：`20~30s`
- 理由：高复用、强热点、读远大于写。
- 解决问题：明显降低搜索引擎 QPS；空值缓存防穿透。

### 9）搜索原始命中结果

- 现状：`SearchService.search` 每次打搜索引擎；用户态 `liked` 是后叠加的。
- 建议：缓存原始 hits 或 contentIds，不缓存最终用户态响应。
- 建议 key：`search:raw:{keywordHash}:{tags}:{after}:{size}`
- TTL：`10~30s`
- 理由：公共结果复用高，但 `liked` 是用户私有字段，不该混缓存。
- 解决问题：降搜索引擎 QPS，同时避免用户态脏数据。

### 10）推荐服务上游结果

- 现状：热门和相关推荐会直接调 Gorse HTTP：
  - `nonPersonalized`
  - `itemToItem`
- 文件：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/GorseRecommendationPort.java`
- 建议 key：
  - `rec:popular:{name}:{offset}:{n}`
  - `rec:neighbors:{name}:{itemId}:{n}`
- TTL：`5~60s`
- 理由：这是共享结果，天然适合缓存。
- 解决问题：降低外部推荐服务 QPS；还能在上游抖动时走短期旧值。

### 11）通知列表第一页

- 现状：`InteractionService.notifications` 每次读库分页。
- 文件：
  - `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/InteractionService.java`
  - `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/InteractionNotificationRepository.java`
- 建议 key：`interaction:notify:first:{uid}`
- TTL：`5~15s`
- 失效：收到新通知、已读单条、全部已读后删 key
- 理由：第一页复用最高。
- 解决问题：减少通知表分页压力。

## 不建议乱加缓存的地方

- 不要缓存整个个性化首页 Feed 响应，项目已经把时间线索引放进 Redis 了，再缓存最终响应收益不大、失效极脏。
- 不要缓存深翻页，复用低，key 数量大。
- 不要把公共字段和个性化字段塞进一个对象，比如 `liked`、`isFollow`、`seen`、`isRead`。

## 统一设计规则

- `TTL + 抖动`：防雪崩
- `空值缓存`：防穿透
- `互斥重建` 或 `stale-while-revalidate`：防击穿
- 优先缓存“公共结果”，用户态字段在返回前叠加
- 首屏优先，别一上来缓存所有分页

## 第二轮补充扫描新增机会

### A）用户公开资料接口本身也没走缓存闭环

- 现状：`UserProfileController.profile/myProfile` 直接调 `userProfileRepository.get + userStatusRepository.getStatus`。
- 文件：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/user/UserProfileController.java`
- 说明：这进一步证明用户资料缓存不是“可以做”，而是“应该马上做”，因为连 controller 直出的查询也在重复查 DB。

### B）风控用户状态可做短 TTL 聚合缓存

- 现状：`RiskService.userStatus` 每次读处罚列表再拼状态。
- 文件：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/RiskService.java`
- 依赖：`RiskPunishmentRepository.listActiveByUser` 纯 DB
- 建议 key：`risk:user:status:{uid}`
- TTL：`30~120s`
- 失效：处罚新增、撤销、过期处理后删 key
- 理由：主页和用户展示都会读，状态允许短时间最终一致。
- 解决问题：减少处罚表重复查询。

### C）内容元信息单查 `findPostMeta` 还没接入底层缓存

- 现状：`ContentDetailQueryService` 做了 DTO 级详情缓存，但 `ContentRepository.findPostMeta` 自己仍然每次查 DB。
- 文件：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/ContentRepository.java`
- 建议 key：沿用内容仓储已有 post key 体系，或新增 `content:meta:{postId}`
- TTL：`10~30m`
- 理由：内容元信息会被详情、互动、审核、推荐过滤等多个链路复用。
- 解决问题：把同一篇 post 的 meta 查询统一收口，减少重复查库。
- 备注：如果直接复用现有 `listPostsByIds` 的缓存模型更好，别再造一套平行 key。

### D）评论置顶 ID 可以单独缓存

- 现状：`CommentPinRepository.getPinnedCommentId` 每次查 DB。
- 文件：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/CommentPinRepository.java`
- 建议 key：`comment:pin:{postId}`
- TTL：`10m`
- 理由：置顶评论常在评论页首屏重复读取，数据量小，命中率高。

### E）推荐个性化结果可以做“极短 TTL + 失败兜底旧值”

- 现状：`GorseRecommendationPort.recommend` 每次 HTTP 调外部推荐服务。
- 建议 key：`rec:user:{uid}:{n}`
- TTL：`3~10s`
- 使用方式：只建议当作“抗抖缓存”，不是强一致缓存。
- 理由：个性化推荐变化快，不适合长缓存，但非常适合拿来挡瞬时流量尖峰和上游抖动。
- 解决问题：减轻推荐服务毛刺，不是追求长期命中。

## 优先级建议

- P0：用户资料、用户状态、用户主页聚合、关系列表、搜索建议、搜索 raw hits、Gorse 非个性化结果
- P1：评论首屏、个人主页 Feed 首屏、通知第一页、风险用户状态
- P2：拉黑/隐私布尔判断、内容元信息收口缓存、个性化推荐抗抖缓存

## 第三轮补充扫描新增机会

### F）登录链路的用户查找绕过了已有缓存仓储

- 现状：`AuthController.login` 直接调用 `IUserBaseDao.selectByUserId/selectByUsername`，没有走已经具备 Redis 用户缓存能力的 `UserBaseRepository`。
- 文件：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/auth/AuthController.java`
- 参考现有能力：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/UserBaseRepository.java`
- 建议方案：
  - 把登录查用户逻辑收口到 `UserBaseRepository`
  - 复用现有 `social:userbase:{uid}` 和 `social:userbase:uid:{username}`
  - 对不存在用户名增加短 TTL 空值缓存 `30~60s`
- 理由：登录/开发态鉴权属于高频入口，直接绕缓存是很差的品味。
- 解决问题：降低登录高峰时 DB 命中；避免用户名查询热点穿透。

### G）风控“生效规则集”应该缓存，而不是每次决策都查库+反序列化

- 现状：`RiskService.loadActiveRules` 每次决策都会 `ruleVersionRepository.findActive()`，再解析 `rulesJson`。
- 文件：
  - `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/RiskService.java`
  - `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/RiskRuleVersionRepository.java`
- 建议 key：`risk:rule:active`
- 缓存内容：直接缓存当前生效版本号 + `rulesJson`，更进一步可缓存解析后的 `RiskRuleSetVO` JSON
- TTL：`5~30m`
- 失效：发布规则、回滚规则后主动删 key
- 理由：这是典型“全站共享、读多写极少”的配置型数据。
- 解决问题：减少规则表查询与 JSON 反序列化开销；提高风控判定吞吐。

### H）风控 LLM 提示词快照应该缓存

- 现状：`DashscopeRiskLlmPort.loadActivePrompt` 每次扫描都 `promptVersionRepository.findActive(contentType)`。
- 文件：
  - `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/DashscopeRiskLlmPort.java`
  - `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/RiskPromptVersionRepository.java`
- 建议 key：`risk:prompt:active:{contentType}`
- TTL：`5~30m`
- 失效：发布提示词、回滚提示词后主动删 key
- 理由：TEXT/IMAGE 这类 prompt 版本是极低频写、高频读的共享配置。
- 解决问题：减少提示词表查询；稳定扫描链路延迟。

## 目前新增点里最值得优先落地的三项

1. 登录链路复用 `UserBaseRepository` 缓存，而不是直打 DAO
2. 风控 active rule 缓存
3. 风控 active prompt 缓存

### I）评论点赞用户列表首屏

- 现状：`ReactionLikeService.queryLikers` 每次走 `reactionRepository.pageUserEdgesByTarget` 查库，再补用户资料。
- 文件：
  - `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/ReactionLikeService.java`
  - `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/ReactionRepository.java`
- 建议 key：`reaction:likers:first:{targetType}:{targetId}:{limit}`
- TTL：`5~15s`
- 失效：新增点赞/取消点赞后删除对应首屏 key
- 理由：这是公共数据，不含 viewer 个性状态，且首屏复用高。
- 解决问题：减少点赞弹窗/列表反复打开时的 DB 压力。
- 边界：只建议缓存首屏，不建议缓存深翻页。

### J）KV 内容查询前置 Redis（条件型机会）

- 现状：
  - `PostContentKvPort.find/findBatch` 直读 Cassandra
  - `CommentContentKvPort.batchFind` 直读 Cassandra
- 文件：
  - `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/kv/PostContentKvPort.java`
  - `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/kv/CommentContentKvPort.java`
  - `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/kv/NoteContentController.java`
  - `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/kv/CommentContentController.java`
- 建议 key：
  - `kv:postContent:{uuid}`
  - `kv:commentContent:{postId}:{yearMonth}:{contentId}`
- TTL：`10~30m`
- 失效：内容新增、删除、覆盖更新时删 key
- 理由：如果 `/kv/*` 接口被业务直接频繁调用，Cassandra 前面放 Redis 很划算；如果只是低频内部接口，就别急着做。
- 解决问题：降低 Cassandra 读压；提升内容直查接口延迟稳定性。
- 结论：这是“条件成立才值得做”的 P2，不是无脑必做。
