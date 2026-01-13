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
