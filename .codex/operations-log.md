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

---

日期：2026-01-30  
执行者：Codex（Linus-mode）

## 搜索与发现服务域实现文档产出（Search & Discovery）

- 现状确认：项目已存在 `SearchController(/api/v1/search/*)` + `ISearchApi/DTO/ISearchService`，但 `SearchService` 为占位返回 demo 数据
- 复用写侧入口：内容发布/删除事件已由 `ContentDispatchPort` 投递到 exchange=`social.feed`（rk=`post.published`/`post.deleted`），且发布点为 after-commit
- 写死上线口径：支持 `type=ALL/POST/USER/GROUP`；USER 只按 `username+userId` 搜索；GROUP 只按 `groupName+groupId` 搜索；未知 type 返回空（items=[]）但不抛错（facets.meta.reason=UNSUPPORTED_TYPE）
- 技术选型定稿：Elasticsearch 8.12.2（官方 Java Client）+ Redis（history/trending/suggest）+ RabbitMQ（social.feed + 新增 social.search）；不引入 CDC
- 核心数据结构写死：单索引多 docType（index=`social_search_v1` + alias=`social_search`）；ES `_id` 写死 `{docType}:{id}`（POST/USER/GROUP 统一幂等）
- 查询一致性约束：`filters` 字符串承载 JSON Schema（分页/过滤/归因）；`facets` 返回 JSON 字符串（含 meta + aggs）；type=ALL 仍是一条 ES query（不做多 query merge，避免 offset/limit 语义崩坏）
- GROUP 真相源补齐：文档内补充 `community_group` 最小可用 DDL + DAO/Mapper/Repository 规范；并定义 `social.search` 的 user/group upsert/delete 事件契约与事件类文件路径
- 文档交付：更新 `.codex/search-discovery-implementation.md`（ES mapping/Redis key/MQ 拓扑/索引规则/查询 DSL/回灌 runner/验收用例/溯源映射，范围=POST+USER+GROUP）

公开资料溯源（摘要）：

- GitHub Code Search inverted index primer：https://github.blog/2023-02-06-the-technology-behind-githubs-new-code-search/
- Google 论文（大规模搜索架构）：http://infolab.stanford.edu/~backrub/google.html
- Transactional Outbox Pattern：https://microservices.io/patterns/data/transactional-outbox.html
- Elasticsearch kNN / Suggesters：https://www.elastic.co/guide/en/elasticsearch/reference/current/knn-search.html / https://www.elastic.co/guide/en/elasticsearch/reference/current/search-suggesters.html

---

日期：2026-01-30  
执行者：Codex（Linus-mode）

## 搜索与发现服务域方案重构：仅 POST 搜索

- 范围收缩：只支持 POST；`type` 空/ALL/POST → POST；USER/GROUP/其它 → UNSUPPORTED（返回空列表，不抛错）
- 用户确认：接受 `type=USER/GROUP` 返回空列表（不抛错）
- 依赖收敛：移除 `social.search` 与 USER/GROUP 索引链路；Search 不再依赖 `community_group`
- 数据结构简化：ES mapping/文档结构只保留 POST 字段；不再引入 `docType`
- 查询与验收更新：只保留 POST Query；facets 仅 `mediaType/postTypes + meta`；冒烟用例增加 `type=ALL` 归一化与 `type=USER` UNSUPPORTED

---

日期：2026-02-02  
执行者：Codex（Linus-mode）

## 搜索与发现服务域：实现细节选项写死（按用户选择）

- 已按你的选择固化策略：`1A 2B 3B 4C 5A 6C 7A`
- 文档更新：`.codex/search-discovery-implementation.md`
  - 读侧失败语义：ES 查询失败直接 `0001`（UN_ERROR），不做“空列表降级”
  - 写侧失败策略：可重试/不可重试分流 + 5 次指数退避重试，耗尽后进入 DLQ
  - 索引陈旧治理：新增并消费 `post.updated` + `user.nickname_changed`
  - 回灌断点续跑：Redis checkpoint `search:backfill:cursor`
  - filters 严格校验：类型不对/值非法直接 `0002`（ILLEGAL_PARAMETER）
- MQ 并发/预取配置化：默认 `concurrency=2`、`prefetch=20`
- 日志格式写死：单行 `key=value`，首字段 `event=...`

---

日期：2026-02-02  
执行者：Codex（Linus-mode）

## 搜索与发现服务域：代码实现落地（Search & Discovery / POST only）

- Domain：新增 `ISearchEnginePort` + `SearchEngineQueryVO/SearchEngineResultVO/SearchDocumentVO`，新增 `ISearchHistoryRepository/ISearchTrendingRepository`，并将 `SearchService` 从占位实现替换为真实链路（filters 校验 → ES 查询 → best-effort 写 history/trend）
- Trigger(HTTP)：`SearchController` 仅做传参 + 异常映射；`search/suggest` 增加读取 `UserContext.getUserId()` 并传入 domain（HTTP 契约/DTO 不变）
- Trigger(MQ)：新增 `SearchIndexMqConfig`（复用 `social.feed` + DLX/DLQ + retry/backoff + concurrency/prefetch 配置化）与 `SearchIndexConsumer`（回表 → upsert/delete，消费 `post.published/post.updated/post.deleted/user.nickname_changed`）
- Infrastructure：新增 `SearchElasticsearchConfig`（ES 8.12.2 官方 Java Client），实现 `SearchEnginePort`（BM25 + gauss recency + highlight + aggs + update-by-query），实现 Redis 仓储 `SearchHistoryRepository/SearchTrendingRepository`
- App：新增 `SearchIndexBackfillRunner`（默认关闭；checkpoint=Redis `search:backfill:cursor`；可重跑 best-effort）
- Types/写侧事件：新增 `PostUpdatedEvent/UserNicknameChangedEvent`；内容编辑成功后 after-commit 触发 `post.updated`（避免把 edit 当成 publish）
- 依赖与验证：`nexus-infrastructure` 增加 `elasticsearch-java:8.12.2`，`nexus-trigger` 增加 `spring-retry`；本地 `project/nexus` 下 `mvn test` 通过

---

日期：2026-02-02  
执行者：Codex（Linus-mode）

## 用户领域：实现方案文档（基于已实现半套补齐）

- 新增文档：`.codex/user-domain-implementation.md`
- 设计定位：User Domain=Profile/Settings/Status + `user_base` 读模型；账号/认证不在本项目实现（继续信任 `X-User-Id` 真值）
- 关键补齐点：`user_base` 增 `nickname` 消除 “nickname=username” 特殊情况；昵称变更 after-commit 发布 `user.nickname_changed` 复用现有 search 消费链路

---

日期：2026-02-02  
执行者：Codex（Linus-mode）

## 用户领域：跨领域冲突盘点（用户域方案 vs 现有实现）

- user_base 当前表结构缺少 `nickname`：多处实现写死 `nickname=username`（影响评论展示与搜索 authorNickname）
- `user.nickname_changed`：已有 MQ binding + consumer，但当前缺少 producer 发布该事件（用户域写入口尚未落地）
- user_base / user_privacy_setting：DAO 目前仅提供 select（无 insert/update），跨域读依赖缺少正式上游写入口
- HTTP userId 真值：大多数 Controller 用 `UserContext.requireUserId()`；少数历史接口仍保留 userId 参数（ignored），需在接口文档中明确“不信任客户端传入 userId”
- 已按你的选择固化“用户域缺漏项”实现选项：`1A 2A 3C 4A 5A 6A 7A 8A 9B 10B 11A 12B 13A 14A 15A 16B 17A 18B`（详见 `.codex/user-domain-implementation.md` 第 13.1 节）

---

日期：2026-02-02  
执行者：Codex（Linus-mode）

## 用户领域：缺漏项拍板更新（1B2A3A4A5A）

- 1B：`/api/v1/internal/user/upsert` = update-only；`user_base` 缺行直接 NOT_FOUND，不自动创建  
- 2A：Profile 本次只做 `nickname/avatarUrl`；bio/background/gender 延后  
- 3A：`username` 真正区分大小写（数据库 collation + 唯一索引）  
- 4A：昵称变更事件走 MySQL Outbox（落库 -> after-commit 尝试投递 -> 失败可重试）  
- 5A：DEACTIVATED 只拦写不拦读（internal 接口例外允许写）

- 文档更新：`.codex/user-domain-implementation.md`（user_base/接口/Outbox/阶段计划/已拍板条目对齐）

---

日期：2026-02-03  
执行者：Codex（Linus-mode）

## 用户领域：阶段 A1（user_base.nickname 读模型对齐）

- 数据结构：`user_base` DDL 补齐 `nickname`；`username` 按 `utf8mb4_bin` 约束大小写区分（schema 文档对齐）
- 代码对齐：`UserBasePO`/`UserBaseMapper.xml`/`UserBaseRepository` 增加 nickname 读取与映射
- 特殊情况收敛：迁移期 fallback（nickname 为空 -> username）只允许存在于 `UserBaseRepository` 一处，避免补丁扩散到调用方
- 测试：`nexus-infrastructure` 引入 `spring-boot-starter-test`（test scope）；新增 `UserBaseRepositoryTest`
- 本地验证：`project/nexus` 下执行 `mvn test`，BUILD SUCCESS

---

日期：2026-02-03  
执行者：Codex（Linus-mode）

## 用户领域：阶段 A2（user_status / user_event_outbox 基础设施）

- DDL：新增 `project/nexus/docs/user_status.sql`、`project/nexus/docs/user_event_outbox.sql`（与实现方案文档一致）
- MyBatis：新增 `cn.nexus.infrastructure.dao.user.*` DAO/PO 与 `mapper/user/*.xml`
- 配置：`application-dev.yml` 的 `mybatis.mapper-locations` 扩展为 `classpath*:mapper/**/*.xml`，确保新 mapper 能被加载
- 本地验证：执行 `mvn -pl nexus-infrastructure -am test`，BUILD SUCCESS

---

日期：2026-02-03  
执行者：Codex（Linus-mode）

## 用户领域：阶段 B1（UserService 写入逻辑 + 领域接口）

- 新增用户域分层：`cn.nexus.domain.user.*`（adapter/repository、adapter/port、model/valobj、service）
- 写逻辑：`UserService` 实现 profile/privacy/internal upsert 的核心规则（昵称空白报错；avatarUrl 允许清空；internal upsert update-only + username 一致性校验）
- 事件触发：nickname 变更时，事务内调用 `IUserEventOutboxPort.saveNicknameChanged(userId, tsMs)`，并用 `TransactionSynchronizationManager.registerSynchronization(...afterCommit...)` 在提交后触发 `tryPublishPending()`
- 用户停用：面向客户端写请求拦截 `DEACTIVATED`（internal 入口例外允许写）
- 错误码：`ResponseCode` 补齐 `NOT_FOUND/CONFLICT/USER_DEACTIVATED`（后续 controller 直接复用）
- 测试：`nexus-domain` 增加 `spring-boot-starter-test`（test scope）；新增 `UserServiceTest` 覆盖核心分支；全量 `mvn test` 通过

---

日期：2026-02-03  
执行者：Codex（Linus-mode）

## 用户领域：阶段 B2（Outbox 发布器 + 定时重试）

- Outbox 端口实现：`UserEventOutboxPort`（MySQL outbox 落库 + RabbitMQ 投递）
- 投递规则：`tryPublishPending()` 每次拉取 `NEW/FAIL` 各 100 条，逐条投递 `exchange=social.feed`、`rk=user.nickname_changed`；成功 markDone，失败 markFail 并增加 retry_count
- payload：最小化为 `{userId, tsMs}`，消费者回表读 nickname（避免冗余字段漂移）
- 定时任务：新增 `UserEventOutboxRetryJob`（每分钟重试 NEW/FAIL；每日 3 点清理 DONE 7 天前记录）
- 调度启用：`Application` 增加 `@EnableScheduling`（否则已有的 RelationEventRetryJob 也不会运行）
- 本地验证：全量 `mvn test`，BUILD SUCCESS

---

日期：2026-02-03  
执行者：Codex（Linus-mode）

## 用户领域：阶段 B3（写接口 HTTP/API + 基础仓储实现）

- nexus-api：新增 `cn.nexus.api.user.*`（`IUserProfileApi/IUserSettingApi/IUserInternalUserApi`）与 DTO（Profile Patch/Privacy Update/Internal Upsert）
- nexus-trigger：新增 `cn.nexus.trigger.http.user.*` Controller，实现 `/api/v1/user/me/profile`、`/api/v1/user/me/privacy`、`/api/v1/internal/user/upsert`
- 契约对齐：面向客户端的写接口一律用 `UserContext.requireUserId()` 取 userId；internal 接口不走 UserContext
- MyBatis 补齐：`IUserBaseDao` 增 `selectByUserId/updatePatch`；`IUserPrivacyDao` 增 `upsertNeedApproval`；对应 Mapper.xml 增 SQL
- 特殊情况消除：`updatePatch` 不允许用 affectedRows=0 误判 NOT_FOUND（值相同也应视为成功）；仓储层二次回表确认是否存在
- 测试与验证：新增 `UserProfileRepositoryTest`；全量 `mvn test`，BUILD SUCCESS


---

日期：2026-02-03  
执行者：Codex（Linus-mode）

## 用户领域：阶段 C（读接口 Profile + 最小屏蔽校验）

- nexus-api：扩展 `IUserProfileApi`，新增 `GET /api/v1/user/me/profile` 与 `GET /api/v1/user/profile?targetUserId=...`，并补齐 DTO（Query/Response）
- nexus-trigger：`UserProfileController` 实现读接口；读取 `user_base`（profile）与 `user_status`（status）；DEACTIVATED 只拦写不拦读
- 最小隐私：查看他人 profile 前做双向屏蔽判断：`IRelationPolicyPort.isBlocked(viewer,target)` 与 `isBlocked(target,viewer)` 任一为 true 则返回 NOT_FOUND（不泄露用户存在性）
- 本地验证：全量 `mvn test`，BUILD SUCCESS

---

日期：2026-02-03  
执行者：Codex（Linus-mode）

## 用户领域：补齐 Settings 读接口（me/privacy）

- nexus-api：`IUserSettingApi` 增加 `GET /api/v1/user/me/privacy`，返回 `needApproval`
- nexus-trigger：`UserSettingController` 增加 GET 入口；当前用户必须存在于 `user_base`，否则返回 NOT_FOUND；needApproval 默认 false
- 本地验证：全量 `mvn test`，BUILD SUCCESS

---

日期：2026-02-03  
执行者：Codex（Linus-mode）

## 用户领域：9.3 个人主页聚合接口 + 阶段 D user_base 缓存

- 9.3 聚合接口：新增 `GET /api/v1/user/profile/page?targetUserId=...`（Profile + 关系统计 + 风控能力）
- 契约不破坏：不改既有 Profile/Settings/Internal HTTP；聚合接口为新增 endpoint + 新 DTO
- 最小隐私：查看他人主页前做双向屏蔽校验，任一方向屏蔽则返回 NOT_FOUND（不泄露用户存在性）
- 关系统计：followCount 复用 `IRelationCachePort.getFollowCount`；followerCount 用 `IRelationRepository.countFollowerIds`；friendCount 用 `countRelationsBySource(..., RELATION_FRIEND)`；isFollow 以 follow/friend 边存在性判定
- 风控能力：复用 `IRiskService.userStatus(targetUserId)` 返回 capabilities（POST/COMMENT）
- 阶段 D 缓存：`UserBaseRepository` 增加 user_base 批量读 Redis 缓存（multiGet 命中直返；miss 单次 DB 回源并回填）
- 缓存 key：`social:userbase:{userId}`（JSON：userId/nickname/avatarUrl，TTL=3600s）；`social:userbase:uid:{username}`（userId 字符串，TTL=3600s）
- 缓存失效：`UserProfileRepository.updatePatch` 更新成功后删除 `social:userbase:{userId}`，确保昵称/头像变更能及时反映到跨域读侧
- 本地验证：全量 `mvn test`，BUILD SUCCESS

---

日期：2026-02-03  
执行者：Codex（Linus-mode）

## 用户领域：修正 Search 索引回灌昵称语义

- 修复：`SearchIndexBackfillRunner.loadNicknames` 回灌索引时优先读取 `user_base.nickname`；若为空则回退 `username`，避免把展示昵称写成 handle
- 本地验证：全量 `mvn test`，BUILD SUCCESS

## 2026-03-11 Codex 缓存方案消歧审计
- 审计对象：`缓存/nexus-redis-统一实施与审计合并方案-2026-03-09.md`
- 结论 1：`B4/B5` 明确收口为“候选池，不是直接实现契约”，禁止实现模型直接编码。
- 结论 2：实施顺序显式排除 `B4/B5`，要求先补独立子文档。
- 结论 3：`followers` keyset 路径增加硬门槛：若未确认目标侧复合索引 `target_id + relation_type + status + create_time + source_id`，按硬阻塞处理。
- 执行者：Codex

## 2026-03-11 Codex ContentRepository 第 4 步收口
- 文件：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/ContentRepository.java`
- 修正正缓存 TTL 抖动：从 `60~120s` 收口为方案要求的 `60~75s`
- 修正空值缓存 TTL：从固定 `30s` 收口为方案要求的 `30~40s`
- 复核 `listPostsByIds`：保留输入顺序与重复 id；`NULL` 不回源；坏 key 删除后回源；single-flight leader 内二次查 Redis 仍保留

## 2026-03-11 Codex ContentDetailQueryService 第 5 步收口
- 文件：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/support/ContentDetailQueryService.java`
- 收口详情稳定快照 TTL：显式固定为 `24h + 0~1h`
- 修正坏 key 语义：Redis 命中空串/空白值时，先删坏 key 再按 miss 处理
- 保留固定流程：L1 -> L2 -> NULL -> single-flight -> leader 内二次查 Redis -> 回源构建稳定快照 -> 返回前补作者资料与点赞数
- 保留固定降级：作者资料失败返回空串、点赞数失败返回 `0`、正文 KV 失败返回空串

## 2026-03-11 Codex CommentRepository 第 6 步收口
- 文件：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/CommentRepository.java`
- 修正 `listByIds` 负缓存语义：Redis 命中 `NULL` 后标记为已解析，禁止继续回表
- 修正 `pageReplyCommentIds` preview 边界：只允许 `viewerId == null` 的公共首屏小页进入共享 preview cache
- 修正 `addReplyCount` 失效矩阵：除删除 `comment:view:{rootId}` 外，必须同步删除 `comment:reply:preview:{rootId}:{1..10}`

## 2026-03-11 Codex ReactionCachePort 第 7 步审计
- 文件：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/ReactionCachePort.java`
- 结论：当前 `getCount` 已符合方案，无需编码改动
- 已确认：热点先查 L1，miss 后走 single-flight，leader 先查 `cntKey`，Redis miss 才回表 `interaction_reaction_count`
- 已确认：`cntKey` 不设 TTL，`applyAtomic` 成功后只做 `countCache.invalidate(hotkey)`，不删 `cntKey`
- 已确认：`getCount` 未使用 bitmap / set / ops 日志临时拼 count

## 2026-03-11 Codex PostAuthorPort 第 8 步收口
- 文件：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/PostAuthorPort.java`
- 补齐 `single-flight`，避免高并发 miss 一起回表 `contentPostDao.selectUserId`
- 补齐 leader 锁内二次查 Redis，避免并发窗口重复回源
- 保持边界不变：只缓存 `authorId`，不缓存昵称、头像；正常值 TTL 仍为 `1d + 0~1h`，空值 TTL 仍为 `30~40s`

## 2026-03-11 Codex FeedCard 第 9 步收口
- 文件：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/FeedCardAssembleService.java`
- 文件：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/adapter/repository/IFeedFollowSeenRepository.java`
- 文件：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/FeedFollowSeenRepository.java`
- 修正 `seen` 装配：从逐条 `isSeen` 改为批量 `batchSeen`，仍然放在装配末尾，不进入共享卡片缓存
- 保持共享缓存边界：`FeedCardRepository.copyStable` 继续清空 `authorNickname/authorAvatar`，作者展示资料仍由 `IUserBaseRepository` 覆盖补齐

## 2026-03-11 Codex 编码后 Code Review
- 审查结论：本轮缓存方案编码步骤已完成，进入审查阶段
- 关键问题：`FeedCardBaseVO` 仍保留 `authorNickname/authorAvatar`，而 `FeedCardRepository.copyStable(...)` 又在稳定快照落缓存前强制清空，属于“数据结构脏、靠下游补丁兜底”
- 次级问题：`ContentDetailQueryService` 注释出现乱码，影响 TTL/NULL/坏 key 规则的可读性与审计性
- 风险提示：`IFeedFollowSeenRepository.batchSeen(...)` 当前是 best-effort 降级语义，建议在接口注释显式写死，避免后续被改成异常传播
- 审查报告：`.codex/review-report.md`

## 2026-03-11 Codex 审查后小修闭环
- 删除 `FeedCardBaseVO` 的 `authorNickname/authorAvatar`，把共享缓存稳定卡片的数据结构彻底收口
- 删除 `FeedCardRepository.copyStable(...)` 中对应的强制清空补丁逻辑
- 修复 `ContentDetailQueryService` 顶部乱码注释，重新写明 TTL 与坏 key 规则
- 为 `IFeedFollowSeenRepository.batchSeen(...)` 写死“失败按未读处理”契约，并在 `FeedFollowSeenRepository` 中实现异常告警 + 空集合降级
