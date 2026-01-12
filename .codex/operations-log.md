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
