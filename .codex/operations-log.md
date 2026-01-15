# 操作与决策日志（执行者：Codex / 日期：2026-01-12）

## 2026-01-12

- 收集上下文：读取 `社交接口.md`、`社交领域.md`、`社交领域数据库.md`、`.codex/DDD-ARCHITECTURE-SPECIFICATION.md` 与现有 Feed 相关代码（`FeedController` / `FeedService`）。
- 决策：Timeline 不落 MySQL（遵循《社交领域数据库.md》建议），使用 Redis 存 InboxTimeline 索引。
- 决策：InboxTimeline 使用 Redis ZSET：member=postId（字符串），score=publishTimeMs（毫秒时间戳），避免 snowflakeId 作为 score 的 double 精度问题。
- 决策：分页 cursor 定义为 lastPostId，并用 `ZREVRANK` 定位进行翻页，规避“同一时间戳多条内容”导致的重复/漏页。
- 决策：分发链路使用 RabbitMQ 异步 fanout（复用现有 `RelationEventPort` 的接入风格），发布成功不被粉丝写放大拖慢。
- 交付：新增 `.codex/context-scan.json` 与 `.codex/distribution-feed-implementation.md`，作为后续开发的实现契约与阶段计划。
- 迭代：将 `.codex/distribution-feed-implementation.md` 升级为实现级文档（精确文件路径、接口签名、MyBatis XML 片段、RabbitMQ 配置/消费者骨架、以及掘金来源标题标注），确保新 Agent 可直接落地 Phase 1。
- 规划：使用 shrimp-task-manager 拆分 Phase 1 实现任务（7 个任务，含依赖与验收标准），供后续逐步落地代码。
- 迭代：补充 Phase 2 的落地顺序与实现说明（在线推 / 离线拉：`feed:active:{userId}` 活跃标记 + inbox miss 离线重建），并修正 Checklist 避免“文档勾选等于已实现”的误导。
- 迭代：补齐 Phase 2 的“逐文件改动清单”（含 `IFeedTimelineRepository.inboxExists` 判定 inbox miss），并修正 `.codex/distribution-feed-implementation.md` 中与现有代码不一致的路径/状态描述；同步更新 `.codex/context-scan.json` 反映 Phase 1 已落地。

## 2026-01-13

- 收集上下文：核对 Interaction 占位实现（`InteractionController`/`InteractionService`/`ReactionRequestDTO`）与 RabbitMQ 延迟队列既有模式（`ContentScheduleDelayConfig`/`ContentScheduleProducer`）。
- 交付：新增 `.codex/interaction-like-pipeline-implementation.md`，将“点赞/取消点赞：Redis 计数 + 延迟落库 + Kafka/Flink 实时监控 + Hive/Spark 离线分析”的实现契约写清楚（含 Redis Key/表结构/时序图/验收点/开放问题）。
- 交付：新增 `.codex/comment-floor-system-implementation.md`，将“B站两级评论盖楼：扁平化 root_id + Redis ZSet 热度排序 + MQ 异步计数与@通知”的实现契约写清楚（含表结构/索引/查询/热榜 key/一致性策略/开放问题）。
- 迭代：更新 `.codex/context-scan.json`，补充 interaction 现状与关键阻塞点（ReactionRequestDTO 缺 userId 导致无法做幂等）。
- 规划：使用 shrimp-task-manager 追加拆分“点赞链路落地”任务（Redis/Lua、MySQL 表与 DAO、延迟队列、flush 竞态收敛、监控与离线契约）。
- 交付：落地 Feed Phase 2（在线推 / 离线拉）：扩展 `IFeedTimelineRepository`（`inboxExists/replaceInbox`），新增 `FeedInboxRebuildService`，改造 `FeedService.timeline` 首页重建与 `FeedDistributionService.fanout` 在线推过滤。
- 迭代：更新 `.codex/distribution-feed-implementation.md` 与 `.codex/distribution-feed-implementation-ezRead.md`，补齐“已完成/未完成/改进点”并同步配置示例。
- 决策：按用户要求跳过本地编译与测试，仅在文档中记录待验证项与建议验收步骤。
- 修复：离线用户 inbox 过期重建可能为空的问题 —— 关注邻接缓存 `listFollowing` 在缓存 key miss 或 relation 表缺失时，从 `user_follower` 回源关注列表（新增 `IFollowerDao.selectFollowingIds`）。
- 修复：负反馈从“单条 postId”扩展为“内容类型（content_post.media_type）”维度 —— 新增 `feed:neg:type:{userId}` 并在 timeline/重建时按类型过滤。

## 2026-01-14

- 上下文补齐：使用 Playwright 抓取并阅读微信文章要点（`解法 3：三级存储（Caffeine + Redis + TaiShan KV）`），用于对齐本仓库的可落地方案。
- 迭代：升级 `.codex/interaction-like-pipeline-implementation.md` —— 补齐读链路（单条/批量查询 likeCount + likedByMe、Redis miss 回源与回填），并收敛 Redis 数据结构（`like:win:*` 合并 sync+dirty，`like:touch:*` 记录窗口内最终状态）。
- 迭代：flush 方案收敛 —— 用 `RENAMENX` 对 `like:touch:*` 做快照，避免 flush 期间新写入被误删；落库不再逐个 `SISMEMBER`，直接批量 upsert 最终状态。
- 迭代：补齐“原文解法 3”的落地映射 —— 本仓库吸收“热点探测 + L1 本地缓存（Caffeine）抗热点”思路；热点探测实现优先用 `JD HotKey`（生态复用，别自研）；L3 不引入 TaiShan/TiCDC，仍以 MySQL（`likes/like_counts`）做最终真值，并将 TaiShan KV 仅作为远期可选项写入文档。
- 迭代：补齐 `JD HotKey` 落地清单（组件部署/最小配置/依赖坐标/故障退化），并在 Feed 文档中引用同一清单，避免多处复制导致不一致。
- 迭代：在 `.codex/distribution-feed-implementation.md` 补充可选优化：用 `JD HotKey` 探测热点 postId，仅对 hot key 开 Caffeine 短 TTL 本地缓存，减少热点回表压力。
- 交付：同步更新 `.codex/context-scan.json`、`.codex/review-report.md`、`.codex/testing.md` 与 `verification.md`，记录本次改动与待拍板项（userId 来源、targetType 范围、窗口参数/Redis 版本）。
- 决策：`userId` 一律从登录态/网关上下文注入（Header：`X-User-Id`），不允许客户端通过 DTO/Query 传入；已同步到 Feed/点赞/评论三份实现文档。
- 核对：对照代码 double check Phase 2 已完成项，并将“关注回源兜底/内容类型负反馈”从代码回写到 `.codex/distribution-feed-implementation.md`（避免文档与实现不一致）。
- 迭代：补齐 `.codex/distribution-feed-implementation.md` 的实现级缺口（除“验收/验证”外）：`10.5.1` fanout 切片、`10.5.2` follow/unfollow 最小补偿、`10.6` 可改进点落地方案、`11` Phase 3（推荐与排序）实现级方案。
- 同步：更新 `.codex/distribution-feed-implementation-ezRead.md`（负反馈流程图补充类型维度写入；“剩余不足”指向新增的实现级章节）；按用户要求未做本地验证。
- 交付：落地 `10.5.1` fanout 大任务切片（规模化）—— `PostPublishedEvent` 由 dispatcher 拆片为多个 `FeedFanoutTask(offset,limit)` 并行消费，失败只重试切片。
- 交付：新增 `FeedFanoutTask`（types），新增 `feed.fanout.task.queue`（trigger），扩展 `IFeedDistributionService.fanoutSlice`（domain），补齐 follower count（`IFollowerDao.countFollowers` + `IRelationRepository.countFollowerIds`）。
- 迭代：更新 `.codex/distribution-feed-implementation.md` / `.codex/distribution-feed-implementation-ezRead.md`，将 10.5.1 标记为已落地并同步关键落点文件清单。
- 交付：落地 `10.5.2` follow 最小补偿 —— 新增 `FeedFollowCompensationService`，`RelationEventListener.handleFollow` 在 status=ACTIVE 时为在线用户回填 followee 最近 K 条内容到 inbox。
- 交付：落地 `10.5.3` Outbox + 大 V 判定 —— 新增 `IFeedOutboxRepository` + Redis 实现；dispatcher 永远写 outbox；大 V 默认不投递全量 fanout task，改由读侧拉取合并。
- 交付：落地 `10.5.4` 铁粉推 —— 新增 `IFeedCoreFansRepository` + Redis 实现；大 V 发布时只对铁粉集合（在线者）推送，数量受 `feed.bigv.coreFanMaxPush` 限制。
- 交付：落地 `10.5.5` 大 V 聚合池 —— 新增 `IFeedBigVPoolRepository` + Redis 分桶 ZSET 实现（默认开关关闭）；写侧入池，读侧在关注数较大时用聚合池降级替代逐个 outbox 拉取。
- 交付：落地 `10.5.6` Max_ID（内部）分页 —— `IFeedTimelineRepository` 增加 `pageInboxEntries/removeFromInbox`；Redis 侧用 `WITHSCORES + Max_ID 过滤` 做稳定分页；FeedService timeline 合并 Inbox + Outbox/Pool。
- 交付：落地 `10.5.7` 读时懒清理 —— timeline 回表后对缺失 postId 执行 inbox/outbox/pool 索引清理，减少反复 miss。
- 同步：更新 `.codex/distribution-feed-implementation.md` / `.codex/distribution-feed-implementation-ezRead.md` 将 10.5.2~10.5.7 标记为已落地，并补齐关键配置与文件清单。
- 修复：负反馈“类型”语义从 `content_post.media_type`（媒体形态）改为 `postType`（业务类目/主题），并移除 `feed:neg:type:{userId}` 的错误假设（改为 `feed:neg:postType:{userId}`；Phase 1 仅 postId 维度生效）。按用户要求未执行 Maven 编译/测试，仅做静态一致性自检与文档同步。

## 2026-01-15

- 决策：post 的业务类型采用“一帖多类型”模型：`ContentPostEntity.postTypes`（List<String>，用户发布时提交，最多 5 个），不再依赖“发布时 LLM 生成单个 postType”的链路。
- 交付：新增内容类型映射表 `content_post_type(post_id, type)`（一对多），并在 `ContentRepository` 回表时批量回填 `postTypes`。
- 交付：发布接口扩展 `PublishContentRequestDTO.postTypes`，`ContentService.publish` 成功发布后调用 `IContentRepository.replacePostTypes(postId, postTypes)` 覆盖写入映射表（postTypes 为空=清空；postTypes 为 null=旧客户端不传，不破坏既有数据）。
- 修复：清理错误的数据模型残留 —— 移除 `ContentPostPO.postType` 与仓储映射中的 `getPostType/setPostType`，避免与真实表结构不一致导致的编译/运行风险。
- 交付：负反馈类型语义最终落地为“用户从该帖 postTypes 中点选的一个类型”：写入时后端校验 type 是否属于该帖 postTypes，不合法直接忽略。
- 交付：为支持撤销负反馈（cancel 接口无 type 参数），负反馈仓储新增 Redis HASH：`feed:neg:postTypeByPost:{userId}`（postId->type），撤销时反查并在“无其它 post 仍点选该 type”时才移除类型级过滤集合。
- 同步：更新 `.codex/distribution-feed-implementation*.md`、`社交领域数据库.md`、`verification.md` 等文档，确保与代码一致。
- 交付：按 `.codex/distribution-feed-implementation.md` 的 `10.6` 补齐可改进点（不影响 Phase 2 正确性）—— MQ 消息统一 JSON（`Jackson2JsonMessageConverter`）、timeline 负反馈 postId 批量过滤（`SMEMBERS`）、fanout DLQ（死信）与最小指标日志、fanout 在线判定改为 Redis pipeline（批量 `EXISTS`）。
- 同步：更新 `.codex/distribution-feed-implementation.md` 与 `.codex/distribution-feed-implementation-ezRead.md`，将 `10.6.1/10.6.2/10.6.4/10.6.5` 标记为已落地；按用户要求跳过“热点探测 + L1 本地缓存（10.6.3）”。
- 迭代：按用户要求对“点赞业务（互动域 + 通知域）”做分步拆分，并同步更新实现契约与外部文档：`.codex/interaction-like-pipeline-implementation.md`（新增分步拆分与通知 MVP）、`社交接口.md`（补齐 state/batchState 与 userId 注入约定）、`社交领域数据库.md`（补齐 likes/like_counts/notification_inbox）。
- 交付：落地点赞写链路（Step 1）—— `InteractionService.react` 从占位改为真实实现；`ReactionRepository` 使用 Redis Lua 原子完成幂等集合 + 计数器 + touch + win 状态机；Controller 仅在 `needSchedule=true` 时投递延迟 flush。
- 交付：落地点赞读链路（Step 3）—— 新增 `GET /api/v1/interact/reaction/state` 与 `POST /api/v1/interact/reaction/batchState`；读侧优先 Redis（count MGET + set pipeline），miss 批量回源 MySQL（`like_counts`/`likes(status=1)`）并回填。
- 交付：落地点赞延迟 flush（Step 2）—— 新增 RabbitMQ `x-delayed-message` 拓扑（LikeSyncDelayConfig/Producer/Consumer/DLQ）；实现 `LikeSyncService` + `ReactionRepository.flush`：`RENAMENX` touch 快照 + 批量 upsert likes + finalize Lua 推进 win 状态机并按需重排队。
- 交付：新增 likes/like_counts 建表脚本 `project/nexus/docs/interaction_like_tables.sql`，并在 `application-dev.yml` 增加 `interaction.like.*` 配置项（windowSeconds/delayBufferSeconds/syncTtlSeconds/flushLockSeconds）。
- 决策：按用户要求跳过 Maven 编译与测试，仅做静态 CR（幂等/竞态/批量查询/NPE 风险）并同步实现文档。
- 修复：flush 关键正确性补强 —— `setIfAbsent` 非 true 即视为未持锁；解锁改为 Lua compare-and-delete；touch 快照 key 改为固定 key（避免 flush 失败后产生“孤儿快照”丢落库）；延迟队列补齐 DLX/DLQ 配置并统一用 `AmqpRejectAndDontRequeueException` 进入 DLQ。
