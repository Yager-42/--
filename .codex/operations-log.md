# .codex/operations-log.md

日期：2026-01-21  
执行者：Codex（Linus-mode）

## 通知业务实现（按 .codex/notification-business-implementation.md#10）

- 追加 DDL：`interaction_notification`（聚合收件箱）、`interaction_notify_inbox`（消费幂等）到 `project/nexus/docs/social_schema.sql`
- 新增统一事件：`InteractionNotifyEvent` + `EventType`（LIKE_ADDED/COMMENT_CREATED/COMMENT_MENTIONED）
- 点赞链路：`ReactionLikeService.applyReaction` 在 `delta==+1` 发布 `LIKE_ADDED`
- 评论链路：`InteractionService.comment` 落库后发布 `COMMENT_CREATED`；并从文本解析 `@username` 发布 `COMMENT_MENTIONED`
- 通知消费者：`InteractionNotifyConsumer` 先 inbox 幂等去重，再聚合 UPSERT 写入 `interaction_notification`
- 修正 inbox payload：`InteractionNotifyConsumer` 写入 `interaction_notify_inbox.payload` 改为可复用 JSON（序列化失败则写入最小 JSON 标记，不影响幂等）
- 读接口：`GET /api/v1/notification/list` 改为真实分页读取 + 渲染 title/content
- 已读接口：新增 `POST /api/v1/notification/read` 与 `POST /api/v1/notification/read/all`
- 清理“mentions 契约残留”：移除 `CommentRequestDTO.mentions` 与 `InteractionService.comment(..., mentions)`；Controller 不再透传（兼容旧客户端：忽略未知字段）
- 文档同步：`.codex/comment-floor-system-implementation.md` 的 curl 示例删除 `mentions`；`社交接口.md` 的接口表删除 `mentions` 并补充通知已读接口
- 修正通知 inbox 幂等：`InteractionNotifyInboxMapper.xml` 改用 `INSERT IGNORE`，避免 `ON DUPLICATE KEY` 触发 `update_time` 自更新导致幂等失效
- 修正消费者“假成功”问题：`InteractionNotifyConsumer` 对缺失核心字段的事件直接抛错并标记 FAIL（坏数据不允许悄悄吞掉）

---

日期：2026-01-22  
执行者：Codex（Linus-mode）

## 评论热榜/点赞回写补齐（按 .codex/comment-floor-system-implementation.md）

- 追加 DDL：`interaction_comment`、`interaction_comment_pin` 到 `project/nexus/docs/social_schema.sql`（字段/索引与 MyBatis Mapper 对齐）
- 点赞→热榜回写：`ReactionResultVO` 增加 `delta`；`InteractionService.react` 在 `targetType=COMMENT && delta!=0` 时发布 `CommentLikeChangedEvent`
- 热榜重建：新增 `CommentHotRankRebuildService.rebuildForPost`（清空并重建 ZSET，支持 trim topK）；`ICommentRepository` 新增 `listRecentRootBriefs`；`ICommentHotRankRepository` 新增 `clear/trimToTop`
- 手动触发：新增 `CommentHotRankRebuildRunner`，仅在启动参数 `--comment.hot.rebuild.postId=<postId>` 时执行一次
- 本地验证：下载 Maven 3.9.6 到 `.codex/tools/apache-maven-3.9.6`，执行 `mvn -DskipTests package` 通过

---

日期：2026-01-22  
执行者：Codex（Linus-mode）

## 分发/Feed 缺口补齐（HotKey L1 + 铁粉生成 + unfollow）

- unfollow 全链路：新增 `POST /api/v1/relation/unfollow` + `IRelationService.unfollow`，并复用 `RelationFollowEvent(status=UNFOLLOW)` 触发 Feed 侧生效
- relation MQ 拓扑补齐：新增 `RelationMqConfig`，声明 `social.relation` 与 `relation.*.queue` 的绑定，保证 trigger listener 能收到事件
- unfollow 立刻生效策略：收到 `UNFOLLOW` 后对在线用户执行 `IFeedInboxRebuildService.forceRebuild`（原因：inbox 索引没有 authorId，无法精确删除）
- 铁粉集合生成：新增 `FeedCoreFansMqConfig`（独立队列绑定 `interaction.notify`）+ `FeedCoreFansConsumer`，消费 `LIKE_ADDED(仅 POST)`/`COMMENT_CREATED` 并在“仍关注作者”时写入 `feed:corefans:{authorId}`
- 热点回表 L1：`ContentRepository` 接入 `JD HotKey + Caffeine`，对热点 postId 的 `findPost/listPostsByIds` 启用短 TTL L1；写路径更新/删帖/改类型会 invalidate
- 文档同步：更新 `.codex/distribution-feed-implementation.md` 的 checklist 与 10.5.2/10.5.4 说明
- 本地验证：下载 Maven 3.9.6 到 `.codex/tools/apache-maven-3.9.6`（gitignore），执行 `project/nexus` 下 `mvn -DskipTests package` 通过

---

日期：2026-01-23  
执行者：Codex（Linus-mode）

## Phase 3 推荐系统方案（参考 scooter-WSVA / Gorse）

- 代码检索：`.codex/_repos/scooter-WSVA` 确认推荐系统采用 Gorse（`docker-compose.yml` + `scripts/gorse/config.toml`），Feed 侧拉取 `/api/recommend`/`/api/popular`/`/api/item/*/neighbors`，Favorite/Comment 侧写入 `/api/feedback`，MQ 侧写入 `/api/item`（labels）
- 方案文档：补齐 `.codex/distribution-feed-implementation.md` 第 11 章（Gorse 接口、数据映射、写入链路、读链路、分页 token、global latest 兜底）
- 文档上线化：补齐第 11 章的“上线必补契约”（RECOMMEND token=scanIndex、scanBudget、session cache 数据结构、降级契约、冷启动回灌、超时/预算、配置项、指标与验收用例），并同步修正 11.7 的分页描述避免自相矛盾

---

日期：2026-01-23
执行者：Codex（Linus-mode）

## Phase 3 上线口径定稿（按用户选择定死）

- 北极星指标：点赞率（Like Rate）
- Labels：选 B（业务类目/标签），真值来源 `content_post_type` / `ContentPostEntity.postTypes`，并写死 label 归一化规则
- Feedback 入口：选 A + C（复用 `PostPublishedEvent` 与 `interaction.notify` 的正向事件 + 新增推荐专用事件承接撤销/扩展）
- 撤销语义：选 B（反向 feedbackType：`unlike` / `unstar`）
- 强规则：选 A（推荐页“每页作者去重”）
- 部署：选 A（Gorse 单实例 + 持久化 volume）
- 文档同步：更新 `.codex/distribution-feed-implementation.md` 第 11 章，新增 11.0“上线口径”，并补齐写入链路/分页伪代码的硬约束

---

日期：2026-01-23
执行者：Codex（Linus-mode）

## Phase 3 口径补充：read/排序/postTypes（用户确认）

- Read（曝光）写入：选 1B（不使用 `write-back-type=read` 自动回写；只对最终下发 items 写 `read` feedback）
- 排序：选 2A（本次上线不做本地重排；返回顺序=候选扫描顺序）
- postTypes：固定一级类字典（每帖必须且只能选 1 个）：`game_news/general_news/guide/review/deal_trade/qa/lfg/showcase/discussion/life/emotion/meta`
- 文档同步：更新 `.codex/distribution-feed-implementation.md` 的 11.0/11.2/11.5/11.6/11.9，写死上述口径与字典

---

日期：2026-01-26  
执行者：Codex（Linus-mode）

## Phase 3 推荐流落地（按 `.codex/distribution-feed-implementation.md#11.12`）

- M0-M2：补齐推荐端口/仓储接口、global latest 写链路、RECOMMEND session cache + scanIndex 读链路（latest-only），并保持 FOLLOW 行为不变
- M3：新增 `GorseRecommendationPort`（JDK `HttpClient` + Jackson）；RECOMMEND 追加候选改为“优先 gorse recommend，失败/为空降级 global latest”；落地关键日志字段（sessionId/scanIndex/scanned/returned/appendRounds/fallbackReason）
- M4：新增独立队列 `feed.recommend.item.upsert.queue`（绑定 `social.feed` + `post.published`）与 `FeedRecommendItemUpsertConsumer` 写入 item（labels=postTypes，trim/去空/去重）
- M5：read feedback：RECOMMEND 实际下发后 best-effort 异步写 `read`；like/comment：新增独立队列 `feed.recommend.feedback.a.queue` 绑定 `interaction.notify`，消费 `LIKE_ADDED/COMMENT_CREATED` 写 `like/comment`
- M6：新增 `RecommendFeedbackEvent`（C 通道）+ `social.recommend` 拓扑；`ReactionLikeService` 在 `delta==-1` 发布 `unlike`；消费者写入推荐系统 feedback
- M7：新增 `POP:{offset}` / `NEI:{seedPostId}:{offset}` token，并在 `FeedService.timeline` 增加 POPULAR/NEIGHBORS 分支（统一“候选->过滤->回表->组装”，offset 按扫描推进）
- M8：新增 `PostDeletedEvent`，`ContentService.delete` after-commit 发布 `post.deleted`；新增 `feed.recommend.item.delete.queue` + consumer 调用 `deleteItem`
- M9：新增全站已发布分页 DAO（`selectPublishedPage`）与可开关回灌 runner：`FeedRecommendItemBackfillRunner`；dev 配置补齐 `feed.recommend.backfill.*`
- 本地验证：`project/nexus` 下执行 `mvn -DskipTests package` 通过
- 文档同步：补齐 `.codex/distribution-domain-implementation.md`（推荐流/GlobalLatest/session cache/MQ 旁路/配置与 Redis key）

---

日期：2026-01-27  
执行者：Codex（Linus-mode）

## 风控与信任服务设计增强（新文档产出）

- 现状确认：`RiskService`/`RiskController` 为占位实现（textScan=PASS、userStatus=NORMAL）；`社交接口.md` 风控接口表与代码契约不一致（`/v1` vs `/api/v1`，userId 来源不一致）
- 设计方向：用统一数据结构 `RiskEvent/RiskDecision/RiskAction` 消灭“按场景写 if/else”的特殊情况；新增不破坏式 `POST /api/v1/risk/decision` 作为统一决策入口
- 架构落地：在线链路只做低延迟预检/规则/轻量模型；重计算（图片扫描/人审工单）全部下沉异步，复用现有 Redis + RabbitMQ
- 公开资料参考：AWS Fraud Detector（模型评分+决策逻辑+Outcome）、Feast（离线/在线特征存储用于实时风控）、Cloudflare Bot（挑战/拦截自动化流量）
- 文档交付：新增 `风控与信任服务-实现方案.md`（架构图、关键流程、API/事件契约、存储设计、灰度发布、指标与上线验收、特征/模型接入）

- 用户追加要求：不接受 MVP 口径，需“完整可上线方案”；已将文档升级为 Production 上线方案，补齐上线定义、服务拆分（risk-api/risk-worker/risk-admin）、高可用与降级策略、后台接口契约、特征平台/模型接入、上线验收与检查清单，并移除 MVP/v2 表述
- 上线约束已定：≥1000 万 DAU / 峰值 ≥50k QPS；人审=工作时段+抽样；内容识别=使用 Spring AI Alibaba 调用 LLM API（异步扫描 + 预算/缓存/降级）
- 范围收敛：当前只覆盖“文本 + 图片”，视频明确为 future；补齐图片风控实现路线（你已选：多模态 LLM；备用：OCR+文本 LLM）、统一 JSON 输出契约（增加 `contentType`）并清理文档里“图片/视频”措辞

---

日期：2026-01-29  
执行者：Codex（Linus-mode）

## 风控与信任服务上线版落地（代码实现）

- 修复 DashScope 适配编译：`DashscopeRiskLlmPort` 对齐 Spring AI 1.1（`AssistantMessage.getText()` + `DashScopeChatOptions` setter），`mvn test` 通过
- 风控后台（risk-admin）接口落地：新增 `IRiskAdminApi` + DTO + `RiskAdminController`，覆盖规则版本/工单/处罚/审计/申诉处理（均为真实落库逻辑）
- 领域服务补齐：新增 `IRiskAdminService/RiskAdminService` 与 `IRiskAppealService/RiskAppealService`，人审结论会更新 `risk_case`/`risk_decision_log` 并推进内容/评论隔离状态
- MyBatis 扩展：规则版本 `maxVersion/updateRulesJson`；审计日志与处罚增加按条件分页查询；工单查询增加可选时间过滤
- 用户申诉入口：`POST /api/v1/risk/appeals` 写入 `risk_feedback(type=APPEAL,status=OPEN)`；后台 `ACCEPT` 会撤销 `risk_punishment`
- 配置与文档：`application-dev.yml` 增加 `spring.ai.dashscope.api-key` 与 `risk.llm.*`；`social_schema.sql` 注释对齐评论 `status=0`（待审核）

---

日期：2026-01-29  
执行者：Codex（Linus-mode）

## 风控与信任服务任务盘点（文档 vs 代码）

- 对照表：新增 `.codex/risk-trust-task-gap.md`，按《风控与信任服务-实现方案.md》18.2 的 10 项任务逐条标注 DONE/PARTIAL 与证据文件
- 主要缺口：`prompt_version`（表+存储层）、`ScanCompleted` 事件、`LOGIN/REGISTER/DM_SEND` 业务接入、PASS 抽检策略、指标埋点与开关矩阵

---

日期：2026-01-29  
执行者：Codex（Linus-mode）

## 风控与信任服务缺口补齐（18.2：任务 2/3/5/8/9；排除任务 10）

- Prompt 版本化：新增表 `risk_prompt_version` + MyBatis/Repository；risk-admin 增加 prompt 版本的 upsert/list/publish/rollback；LLM 调用会读取当前生效 prompt 并把 `promptVersion/model` 写入审计 detail，保证可回溯
- ScanCompleted 事件：新增 `ScanCompletedEvent`，在 `RiskLlmScanConsumer`/`RiskImageScanConsumer` 扫描完成后 best-effort 发布 `risk.scan.completed`（旁路消费用）
- PASS 抽检：`RiskService.tryCreateCase` 对 PASS 结果按“工作时段 + 抽样比例”创建 `risk_case(queue=sample)`（不改变用户可见决策结果）；默认 `passPercent=0` 关闭
- 自动处罚：`RiskAsyncService` 在 LLM=BLOCK 且置信度达阈值时可写 `risk_punishment`（幂等 `insertIgnore`）；默认 `risk.autoPunish.enabled=false` 关闭
- Never break userspace：新增能力默认关闭/0%，避免对现有用户可见行为造成意外变化
