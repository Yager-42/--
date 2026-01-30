# .codex/review-report.md

日期：2026-01-20  
执行者：Codex（Linus-mode）

## 需求与范围

- 依据：`.codex/interaction-like-pipeline-implementation.md` 第 16 章“逐步实现清单”。  
- 目标：从业务链路角度审查“点赞 LIKE 三条链路”（链路 1/2/3）是否可走通；不做 Maven 验证。  

## Linus Review（代码走通性）

【品味评分】🟢 好品味  
【综合评分】90/100（通过）

### 链路 1：在线写入（HTTP -> Domain -> Redis）

走通路径：

- `POST /api/v1/interact/reaction` -> `InteractionController` -> `InteractionService.react(...)` -> `ReactionLikeService.applyReaction(...)`
- `ReactionCachePort.applyAtomic(...)`：Lua 原子完成 `set 去重 + cnt 计数 + ops 记录 + syncKey 首次 pending`
- 首次 pending 才投递延迟消息（delayMs 来自 `window_ms`，默认 5 分钟）
- 返回 `currentCount`（Redis 近实时计数）+ `requestId`（链路追踪）

关键正确性：

- 幂等不是“补丁逻辑”，而是数据结构：`SADD/SREM` 的返回值决定 `delta`（1/-1/0）。  
- `cntKey` 丢失不当真相：用 `SCARD(setKey)` 重建，避免负数/漂移。  
- `requestId` 只做追踪：不参与幂等（幂等靠 set-state + Redis set）。  

### 链路 2：延迟落库（RabbitMQ -> Redis 快照 -> DB）

走通路径：

- `ReactionSyncProducer` 延迟投递 -> `ReactionSyncConsumer` 消费
- consumer 用 Redis 锁避免并发落库；抢不到锁/短暂失败用 `attempt` 重投递（不吞消息）
- `ReactionLikeService.syncTarget(...)`：
  - Lua 快照：`opsKey -> processingKey`（RENAME，Redis Cluster 同 slot）
  - 批量落库：upsert/delete 事实表
  - 覆盖写 count 表：读 Redis 原始计数（不走 L1）
  - 清理 processingKey + syncKey；同步期间若又产生新 ops，则再触发一次延迟同步

关键正确性：

- 消灭“并发丢数据”的特殊情况：同步链路不做“读完 DEL opsKey”的垃圾写法，而是快照 + 重新触发。  
- DB count 明确是派生值：同步链路对齐 DB 读 Redis 原始值，不被热点 L1 污染。  

### 链路 3：实时监控/热榜/动态窗口（日志 -> Kafka -> Redis）

走通路径：

- 在线写成功后打印结构化 JSON 日志（`event=reaction_like`，字段对齐 13.1）
- 外部系统：Logstash -> Kafka `topic_like_monitor`；Flink 5m 聚合 -> Kafka `topic_like_5m_agg` / `topic_like_hot_alert`
- 项目内：Kafka consumers
  - `topic_like_5m_agg`：回写热榜 ZSET + 写入 `window_ms`（EX=60s，分段映射）
  - `topic_like_hot_alert`：最小告警 `log.warn(...)`

关键正确性：

- 链路 3 是旁路：外部依赖不可用不影响主写链路语义。  
- `window_ms` 由事件驱动写入并自动过期，避免轮询/额外特殊情况。  

## 致命问题（未发现）

本次聚焦点赞三链路，未发现“会导致链路走不通”的结构性问题。

## 改进方向（可选）

- MQ DLQ 的策略目前是“业务主动投递 + 最小告警日志”，后续如要更强可观测性，可补充指标与告警规则（不改变业务语义）。  
- 手拼 JSON 日志是可接受的简化；如后续扩字段频繁，可考虑统一一个 log-event DTO（仍然保持字段契约不变）。  

## 关键文件（入口与主干）

- `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/InteractionController.java`
- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/InteractionService.java`
- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/ReactionLikeService.java`
- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/ReactionCachePort.java`
- `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/ReactionSyncConsumer.java`
- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/ReactionRepository.java`
- `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/kafka/ReactionLikeAggConsumer.java`

---

# 追加：通知业务 CR

日期：2026-01-21  
执行者：Codex（Linus-mode）

## 需求与范围

- 依据：`.codex/notification-business-implementation.md` 第 10 章“逐步实现清单”。  
- 目标：补齐通知业务（聚合收件箱 + MQ 消费者 + /notification/list 真实实现 + 已读接口）；不做 Maven 验证。  

## Linus Review（从接口模拟链路走通性）

【品味评分】🟢 好品味  
【综合评分】92/100（通过）

### 链路 1：点赞新增 -> 通知写入（旁路 MQ）

前端请求（示例）：

- `POST /api/v1/interact/reaction`
- body：`{"targetId":90001,"targetType":"POST","type":"LIKE","action":"ADD","requestId":null}`

走通路径：

- HTTP：`InteractionController.react` -> `InteractionService.react` -> `ReactionLikeService.applyReaction`
- 只在 `delta==+1` 时发布 `InteractionNotifyEvent(eventType=LIKE_ADDED, eventId=requestId)`
- MQ：`InteractionNotifyConsumer` 先写 `interaction_notify_inbox` 幂等去重，再做聚合 UPSERT 写入 `interaction_notification`

关键正确性：

- 幂等不写补丁：点赞幂等靠 Redis set-state 的 `delta`；MQ 至少一次靠 notify inbox 的 `event_id` 去重。  
- 聚合维度稳定：唯一键 `(to_user_id, biz_type, target_type, target_id)` 收敛高频写。  

### 链路 2：评论创建 -> 通知写入（direct/reply 一刀切）

前端请求（示例）：

- `POST /api/v1/interact/comment`
- direct：`{"postId":90001,"parentId":null,"content":"hi","mentions":[]}`
- reply：`{"postId":90001,"parentId":80001,"content":"hi","mentions":[]}`

走通路径：

- `InteractionService.comment` 落库成功后发布 `InteractionNotifyEvent(eventType=COMMENT_CREATED, eventId=commentId)`
- direct：`targetType=POST,targetId=postId` -> 通知消费者回表 post 拿作者作为收件人 -> `POST_COMMENTED`
- reply：`targetType=COMMENT,targetId=parentId` -> 通知消费者回表 comment 拿作者作为收件人 -> `COMMENT_REPLIED`

关键正确性：

- 消灭“回复双通知”：reply 只通知被回复的评论作者（不再同时通知 post 作者）。  

### 链路 3：@提及 -> 通知写入（去重规则在消费端统一）

前端内容（示例）：`"hello @alice, @bob!"`

走通路径：

- 写侧解析 `@username` -> 回表 `user_base` -> 对每个收件人发布 `COMMENT_MENTIONED`（`eventId=commentId:toUserId`）
- 消费端统一去重：若 `toUserId == targetOwnerUserId` 直接丢弃（避免“主收件人 + 提及”双通知）

### 链路 4：通知列表读（/notification/list）

前端请求（示例）：

- `GET /api/v1/notification/list?cursor=1700000000000:10001`

走通路径：

- `InteractionService.notifications` -> `InteractionNotificationRepository.pageByUser`
- 只读 `unread_count>0`，排序 `update_time DESC, notification_id DESC`
- `nextCursor` 固定格式：`{updateTimeMs}:{notificationId}`
- title/content 在 domain 渲染（不在 DB 存文案，避免迁移）

### 链路 5：已读闭环（/notification/read /read/all）

前端请求（示例）：

- `POST /api/v1/notification/read` body：`{"notificationId":10001}`
- `POST /api/v1/notification/read/all` body：空

关键正确性：

- 已读语义简单粗暴：`unread_count=0`；且不会再出现在 list。  
- `markRead/markReadAll` 不更新 `update_time`（DDL 不含 `ON UPDATE`，SQL 也不动该列）。  
- 幂等：重复调用永远 success=true（找不到也算成功）。  

## 致命问题（未发现）

本次按“接口 -> 事件 -> 消费 -> DB 聚合 -> 读接口/已读”走通性审查，未发现会把链路卡死的结构性问题。

## 改进方向（可选）

- notify inbox 当前只做 NEW/DONE/FAIL 标记；如需要自动补偿，可后续补一个定时重放 FAIL（不改变业务语义）。  

## 关键文件（入口与主干）

- `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/InteractionController.java`
- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/InteractionService.java`
- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/ReactionLikeService.java`
- `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/InteractionNotifyConsumer.java`
- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/InteractionNotificationRepository.java`
- `project/nexus/docs/social_schema.sql`

---

# 追加：分发/评论/跨域联通性 CR

日期：2026-01-22  
执行者：Codex（Linus-mode）

## 范围

- 分发/Feed：`PostPublishedEvent -> fanout -> inbox/outbox -> /feed/timeline`
- 点赞：在线写入 + 延迟落库 + 通知旁路
- 评论：两级盖楼写入 + 读侧列表/热榜 + 计数/热榜异步更新
- 通知：统一事件消费 + 聚合收件箱 + 读接口/已读接口

## 结论（走通性）

- 领域内链路：分发/点赞/评论/通知主链路都“能跑通”。  
- 领域间交互：点赞/评论 -> 通知（InteractionNotifyEvent）能走通；内容发布 -> 分发（PostPublishedEvent）能走通。  
- 但存在 2 个“上线会炸”的风险点（见下方致命问题 1/2），以及 2 个“会悄悄算错/丢消息”的高风险点。

## Linus Review（问题清单，按严重程度）

### 致命问题 1：发布事件在事务提交前发送，可能导致 Feed 索引被“读侧误删”

现状：

- `ContentService.publish` 在事务内直接调用 `contentDispatchPort.onPublished(...)`：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/ContentService.java:250`
- `ContentDispatchPort` 立即 `convertAndSend`：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/ContentDispatchPort.java:29`

风险：

- MQ 消费者（fanout）可能先写入 inbox/outbox，再被读侧回表时发现 `content_post` 还没提交（查不到），触发“懒清理”把索引删掉，最终用户看不到刚发布的内容（秒级概率问题，线上最难排）。

建议：

- 把发布事件改为“事务提交后再发”（after-commit），不要让 MQ 读到未提交数据。

### 致命问题 2：点赞延迟队列依赖 RabbitMQ x-delayed-message 插件，缺插件会直接启动失败

- `ReactionSyncDelayConfig` 声明 `CustomExchange(..., "x-delayed-message", ...)`：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/config/ReactionSyncDelayConfig.java:33`

建议：

- 部署前把“插件是否安装”写进环境验收清单；否则应用启动就会在声明 exchange/queue 阶段炸掉。

### 高风险 1：通知消费者失败直接 reject 且队列无 DLX，消息会丢

- `InteractionNotifyConsumer` catch 后 `throw new AmqpRejectAndDontRequeueException(...)`：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/InteractionNotifyConsumer.java:58`
- `InteractionNotifyMqConfig` 的队列未配置 `x-dead-letter-exchange`：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/config/InteractionNotifyMqConfig.java:27`

结果：

- 任何一次瞬时依赖故障（DB/Redis 抖动）会导致通知事件被标记 FAIL 且从 MQ 直接消失，需要人工补偿/重放。

### 高风险 2：评论计数/热榜消费不做幂等，MQ 重投会把 like_count/reply_count 算飞（甚至负数）

- `CommentLikeChangedConsumer` 直接 `addLikeCount(delta)`：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/CommentLikeChangedConsumer.java:32`
- `RootReplyCountChangedConsumer` 直接 `addReplyCount(delta)`：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/RootReplyCountChangedConsumer.java:32`
- MyBatis SQL 无负数保护：`project/nexus/nexus-infrastructure/src/main/resources/mapper/social/CommentMapper.xml:40`、`project/nexus/nexus-infrastructure/src/main/resources/mapper/social/CommentMapper.xml:46`

结果：

- RabbitMQ 至少一次投递下，计数和热榜分数会漂移；极端情况下会出现负数，排序与展示都不可信。

## 跨域交互链路（代码层面）

- 内容发布 -> 分发：`ContentService.publish` -> `ContentDispatchPort.onPublished` -> `FeedFanoutDispatcherConsumer` -> `FeedFanoutTaskConsumer` -> `FeedDistributionService.fanoutSlice` -> `IFeedTimelineRepository.addToInbox`
- 点赞 -> 通知：`InteractionController.react` -> `InteractionService.react` -> `ReactionLikeService.applyReaction(delta==1)` -> `InteractionNotifyEventPort.publish` -> `InteractionNotifyConsumer` -> `InteractionNotificationRepository.upsertIncrement`
- 评论 -> 热榜/计数：`InteractionController.comment` -> `InteractionService.comment` -> `CommentEventPort.publish` -> `CommentCreatedConsumer/RootReplyCountChangedConsumer/CommentLikeChangedConsumer`
- 评论 -> 通知：`InteractionService.comment` -> `InteractionNotifyEventPort.publish(EventType.COMMENT_CREATED/COMMENT_MENTIONED)` -> `InteractionNotifyConsumer`

## 品味评分（主观但诚实）

- 分发/Feed：🟡 凑合（核心链路清晰，但“事务内发事件”是低级错误，会让线上出现鬼故事）
- 点赞：🟢 好品味（数据结构驱动幂等，delta 信号清晰，延迟落库范式正确）
- 评论：🟡 凑合（读写分离与两级盖楼做对了，但消费幂等/计数负数保护没做）
- 通知：🟢 好品味（收件箱幂等 + 聚合写入方向正确，但 MQ 失败策略需要补 DLX 或补偿）

## 修复落地（2026-01-22）

- 已修复 致命问题 1：发布事件改为事务提交后发送（`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/ContentService.java:252`、`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/ContentService.java:760`）。
- 已修复 高风险 1：通知队列补 DLX/DLQ，reject 不再“直接丢”（`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/config/InteractionNotifyMqConfig.java:28`、`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/config/InteractionNotifyMqConfig.java:40`）。
- 已修复 高风险 2：评论计数消费加幂等 + 计数防负数（`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/CommentLikeChangedConsumer.java:35`、`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/RootReplyCountChangedConsumer.java:35`、`project/nexus/nexus-infrastructure/src/main/resources/mapper/social/CommentMapper.xml:40`）。
- 未修复 致命问题 2：x-delayed-message 插件依赖仍保留（按你的要求）；上线前请在环境验收清单里确认插件已安装。



---

# 追加：Phase 3 推荐流 CR

日期：2026-01-26  
执行者：Codex（Linus-mode）

## 范围

- 依据：`.codex/distribution-feed-implementation.md` 第 11 章与 `11.12 最小逐步实现步骤`（M0~M9）。
- 目标：在不破坏 FOLLOW（cursor=postId）前提下，落地 RECOMMEND/POPULAR/NEIGHBORS + gorse + fallback + item/feedback 写入 + 删帖 deleteItem + 冷启动回灌。

## Linus Review（走通性与品味）

【品味评分】好品味  
【综合评分】88/100（通过）

### 好品味点（消除特殊情况）

- cursor 不再硬绑 postId：FOLLOW 仍是 postId；推荐流改为不透明 token（REC/POP/NEI），避免“推荐流天然无序”导致的边界补丁。
- 推荐统一成一条数据流：候选集 -> 过滤（负反馈 postId/postType + 回表 miss/下架 + 每页作者去重）-> 组装；`nextCursor` 以“扫描过的候选位置”推进，避免过滤后卡页。
- 外部依赖失败策略够实用：gorse 超时/失败立即降级 latest（不 500、不白屏、仍可翻页推进）。

### Never break userspace（零破坏性）

- FOLLOW 行为隔离：`FeedService.timeline` 对 RECOMMEND/POPULAR/NEIGHBORS 走独立分支，FOLLOW 仍沿用 inbox/outbox 读取与原 cursor 协议。
- MQ 不抢消息：推荐侧复用 `interaction.notify` 时使用独立队列绑定 routingKey，绝不复用 `interaction.notify.queue`。

### 风险点（现实问题，不写假想补丁）

- `read` feedback 当前用 `CompletableFuture.runAsync`（默认线程池）：高并发可能放大资源压力；后续如要收敛，建议改为受控线程池或消息化（不改变语义即可）。
- 推荐写入/删帖 consumer 走 best-effort 且吞异常：DLQ 可能不触发，这属于“允许丢”的取舍；若将来要求必须送达，需要改变失败策略。

## 本地编译

- `project/nexus` 下执行 `mvn -DskipTests package`：BUILD SUCCESS（Finished at: 2026-01-26T12:18:19+08:00）。

## 关键文件（Phase 3）

- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/FeedService.java`
- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/model/valobj/FeedRecommendCursor.java`
- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/model/valobj/FeedPopularCursor.java`
- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/model/valobj/FeedNeighborsCursor.java`
- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/GorseRecommendationPort.java`
- `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/config/FeedRecommendItemMqConfig.java`
- `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/config/FeedRecommendFeedbackAMqConfig.java`
- `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/config/FeedRecommendFeedbackMqConfig.java`
- `project/nexus/nexus-types/src/main/java/cn/nexus/types/event/PostDeletedEvent.java`
- `project/nexus/nexus-app/src/main/java/cn/nexus/config/FeedRecommendItemBackfillRunner.java`

---

# 追加：风控与信任服务实现方案（文档 CR）

日期：2026-01-27  
执行者：Codex（Linus-mode）

## 范围

- 依据：`社交接口.md` 第 5 章风控概览 + 现有占位实现 `RiskController/RiskService`。
- 目标：产出 `风控与信任服务-实现方案.md`，把“概览”补成“可落地实现方案”，并保证不破坏现有用户可见接口行为。

## Linus Review（好品味=数据结构优先）

【品味评分】🟢 好品味  
【综合评分】88/100（通过）

### 好的部分（值得保留）

- 先把核心数据结构定死：`RiskEvent/RiskSignal/RiskDecision/RiskAction`，让“发帖/评论/关注/登录”等场景都变成同一种输入，天然减少 if/else。
- 在线/离线分层清晰：在线只做低延迟预检/规则/轻量模型；重计算（图片、人审）全部下沉异步，避免把业务主链路拖死。
- Never break userspace：现有 `scan/text`、`scan/image`、`user/status` 不改语义；新能力以新增统一决策入口（v2）扩展。

### 致命问题（未发现）

本次为文档与架构设计补齐，不涉及对现网接口/数据的破坏性变更；未发现会导致“落地必炸”的结构性问题。

### 改进方向（可选，但能让文档更可执行）

- 建议补一段 `Response<T>` 的示例 JSON（对齐项目实际返回形态），避免读者只看到业务字段不知道外层封装。
- 事件命名需最终对齐项目 MQ 规范（exchange/routingKey/queue）；文档里目前给的是示例名。

## 关键文件

- `风控与信任服务-实现方案.md`
- `社交接口.md`

---

# 追加：风控与信任服务（上线版代码落地）CR

日期：2026-01-29  
执行者：Codex（Linus-mode）

## Linus Review（实现质量）

【品味评分】🟢 好品味  
【综合评分】91/100（通过）

### 走通性（闭环）

- 统一入口：`RiskService.decision(RiskEvent)` 做幂等 + 决策 + `risk_decision_log` 审计落库；旧接口 `scan/text` 也走统一入口避免“有些判断没日志”的特殊情况。
- REVIEW 不是特殊分支：落地成“工单 + 隔离内容/评论 + 异步回写推进”的统一闭环，避免业务到处散落 if/else。
- 后台不是摆设：`/api/v1/risk/admin/*` 覆盖规则版本发布/回滚、工单分配/结论、处罚 apply/revoke/query、审计查询、申诉处理；全部真实落库。

### 关键实现点（Good Taste）

- 规则版本发布/回滚以 `risk_rule_version` 为单一事实来源；发布只改变状态，不搞影子兼容层。
- 人审结论不“只改工单”：同步更新 `risk_decision_log` 并推进内容/评论状态（从 decision_log.extJson 取 attemptId/commentId），减少“人审做了但业务不生效”的隐性 bug。
- DashScope 适配对齐 Spring AI 1.1：`AssistantMessage.getText()` + `DashScopeChatOptions` setter，消灭编译层面的 API 漂移特殊情况。

### 风险与改进（仍然值得记住）

- admin 查询接口默认允许不传 userId（可分页），对生产需配合限流/审计（本任务按要求不引入安全设计）。
- 处罚 apply 的幂等目前以 `(decisionId,type)` 为强约束；若 admin 侧不传 decisionId，重试会产生多条处罚（需要上层运营流程约束）。

## 关键文件（实现）

- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/RiskService.java`
- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/RiskAdminService.java`
- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/RiskAppealService.java`
- `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/RiskController.java`
- `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/RiskAdminController.java`

---

# 追加：风控与信任服务缺口补齐（18.2：任务 2/3/5/8/9）CR

日期：2026-01-29  
执行者：Codex（Linus-mode）

## Linus Review（实现质量）

【品味评分】好品味  
【综合评分】90/100（通过）

### 好的部分（Good Taste）

- Prompt 版本化沿用 `risk_rule_version` 的“版本表”模式：DRAFT/PUBLISHED/ROLLED_BACK + publish/rollback，且把 `promptVersion/model` 写入审计 detail，能对比“改 prompt 前后效果”，不会变成不可追溯的黑盒。
- ScanCompleted 做成旁路事件：best-effort 发布，不影响现有“落库回写推进”的主闭环；需要消费时再绑定队列即可。
- PASS 抽检与自动处罚都默认关闭（或 0%）：能力具备但不强行改变用户可见行为，符合 Never break userspace。

### 风险与改进（仍然值得记住）

- 一旦打开 autoPunish/抽检，要配指标/告警，否则你只是在“盲打人”（任务 10 仍缺指标埋点）。
- `risk.scan.completed` 当前不绑定队列是刻意选择：如果业务方误以为“发了就一定有人收”，会产生排障歧义；要消费就显式声明队列绑定。

## 关键文件（本次变更）

- `project/nexus/docs/social_schema.sql`
- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/RiskService.java`
- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/risk/RiskAsyncService.java`
- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/DashscopeRiskLlmPort.java`
- `project/nexus/nexus-types/src/main/java/cn/nexus/types/event/risk/ScanCompletedEvent.java`
- `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/config/RiskMqConfig.java`

---

# 追加：搜索与发现服务域实现文档 CR

日期：2026-01-30  
执行者：Codex（Linus-mode）

## 需求与范围

- 依据：`社交接口.md` 的“搜索与发现服务域”章节 + 当前代码骨架（SearchController/ISearchService/SearchService 占位）。  
- 目标：产出实现级文档，约束到“任何 agent 都能实现出一致的搜索域”，并给出可追溯的外部借鉴来源。  
- 本次口径：`type=ALL/POST/USER/GROUP`；USER 只按 `username+userId` 搜索；GROUP 只按 `groupName+groupId` 搜索；HTTP/DTO 契约保持不变。  

## Linus Review（文档可实现性与一致性）

【品味评分】🟡 凑合  
【综合评分】90/100（通过）

### 好的部分（Good Taste）

- 先看事实再设计：把“保持 /api/v1/search/* 与 DTO 字段不变”写成硬约束，符合 Never break userspace。  
- 消灭特殊情况：把“不可见 POST”的判定放在索引侧（回表判定 → delete/upsert），从而让查询侧不需要 status/visibility filter。  
- 幂等靠数据结构：ES `_id` 写死 `{docType}:{id}`（POST/USER/GROUP 统一），MQ 重投不会写出重复垃圾。  
- ALL 行为不模糊：明确 type=ALL 仍然是一条 ES query（不做多 query merge），保证 offset/limit 可验证。  
- 交付可执行：mapping/Redis key/MQ 拓扑/filters+facets schema/Query DSL/curl 冒烟/回灌 runner 都给到“可照抄”的粒度。  

### 风险与改进（仍然值得记住）

- USER/GROUP 的索引更新依赖新事件 `social.search`：如果业务侧不在“资料变更/圈子变更”后 after-commit 发事件，索引会长期不更新（这不是 Search 的锅，是上游没按契约做）。  
- GROUP 真相源需要补齐：文档已给出 `community_group` DDL + DAO/Mapper/Repository 规范，但它属于“新增持久化”，上线需要 DB 变更流程配合。  
- USER 回灌需要新增 `user_base.selectPage`：否则只能靠事件增量，历史用户永远搜不到。  

## 关键文件（本次交付）

- `.codex/search-discovery-implementation.md`
