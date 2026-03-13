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

---

# 追加：用户领域（Profile/Settings/Status + user_base 读模型 + Outbox）CR

日期：2026-02-03  
执行者：Codex（Linus-mode）

## Linus Review（实现质量）

【品味评分】🟢 好品味  
【综合评分】92/100（通过）

### 好的部分（Good Taste）

- 数据结构先行：`user_base` 正式引入 `nickname`（展示名）并保持 `username` 为不可变 handle（区分大小写），让“展示名”不再靠补丁冒充。
- 事件可靠性不是口号：nickname 变化走 MySQL Outbox（事务内落库 → after-commit 尝试投递 → 失败定时重试），避免“改名成功但索引不更新”的线上鬼故事。
- 特殊情况收敛：迁移期 nickname fallback 不扩散到调用方；并处理 MySQL `affectedRows=0`（值相同）导致的误判 NOT_FOUND。
- Never break userspace：现有 UserContext 契约保持不变；屏蔽时返回 NOT_FOUND（不泄露用户存在性）；DEACTIVATED 只拦写不拦读（internal 入口例外允许写）。

### 致命问题（未发现）

本次为新增用户域最小能力与补齐缺口，不涉及对既有用户可见接口的破坏性改动；未发现会导致启动失败或“写成功但读不到”的结构性问题。

### 风险与改进（仍然值得记住）

- 真实环境需要执行 DDL/回填：`user_base.nickname` 必须回填 `nickname=username`（否则可能出现 nickname 为空的历史数据）；这是数据治理问题，不应该靠业务层到处打补丁长期兜底。
- 端到端冒烟依赖 MySQL/RabbitMQ：当前以单测+编译验证为主；建议本地拉起依赖后跑一次“改昵称→outbox→search consumer”链路冒烟。
- 可选项已落地：个人主页聚合接口（9.3）与 user_base Redis 缓存（阶段 D）已实现；仍需注意 relation 口径（PENDING 是否计入计数）与缓存一致性（写路径 evict + TTL）。

## 关键文件（实现）

- `project/nexus/docs/social_schema.sql`
- `project/nexus/docs/user_status.sql`
- `project/nexus/docs/user_event_outbox.sql`
- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/user/service/UserService.java`
- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/user/port/UserEventOutboxPort.java`
- `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/user/UserProfileController.java`
- `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/user/UserSettingController.java`
- `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/user/InternalUserController.java`

## 2026-03-11 缓存统一实施方案编码后审查

- 执行者：Codex（Linus-mode）
- 审查范围：`FeedCardBaseVO`、`FeedCardRepository`、`FeedCardAssembleService`、`ContentDetailQueryService`、`IFeedFollowSeenRepository`、`FeedFollowSeenRepository`
- 审查目标：确认本轮编码是否已完成，并识别仍会让后续实现者在未讨论 corner case 上自行发挥的残留问题
- 结论：本轮编码步骤已完成；运行时主路径已按方案收口，但仍存在 1 个结构性误导点和 1 个次级工程卫生问题

### 评分

- 技术评分：88/100
- 战略评分：93/100
- 综合评分：90/100
- 通过结论：通过当前编码交付；建议进入一轮小修而不是重做

### Linus Code Review

- 【品味评分】🟡 凑合
- 【致命问题】`FeedCardBaseVO` 仍声明 `authorNickname` 与 `authorAvatar`，但 `FeedCardRepository.copyStable(...)` 在写入稳定快照前又强制清空这两个字段。运行结果没错，但数据结构在撒谎：它让人误以为“基础卡片可以安全携带作者展示信息”，实际又靠下游补丁抹掉。这个设计会诱导未来实现者把展示字段偷偷塞回共享缓存。
- 【致命问题】`ContentDetailQueryService` 顶部中文注释已经出现编码污染，注释不再表达约束，反而制造噪音。代码还能跑，但文档层已经坏了。
- 【改进方向】把 `FeedCardBaseVO` 收口为真正的稳定快照结构，只保留 `postId/authorId/text/summary/mediaType/mediaInfo/publishTime`；不要让对象先携带脏字段，再在仓储里删掉。
- 【改进方向】如果短期不改结构，至少把 `FeedCardRepository.copyStable(...)` 的“强制清空作者展示字段”提升成明确契约，并在接口层说明“作者资料只能在 assemble 末尾补齐，禁止进入共享缓存”。
- 【改进方向】修复 `ContentDetailQueryService` 的乱码注释，直接把 TTL、NULL 语义、坏 key 删除规则用正常中文写清楚，不要让注释变成随机字节。
- 【改进方向】`IFeedFollowSeenRepository.batchSeen(...)` 现在的行为是合理的：失败时返回空集合，相当于 best-effort 降级，不影响主流程正确性。这个语义最好在接口注释里写死，免得后续有人把它改成异常传播。

### 剩余风险

- 风险 1：未来有人看到 `FeedCardBaseVO.authorNickname/authorAvatar`，会以为共享缓存允许携带展示字段，从而重新引入缓存污染。
- 风险 2：乱码注释会降低后续审计质量，尤其是 TTL/NULL 规则这类必须精确执行的地方。
- 风险 3：`batchSeen` 当前是静默降级语义，若无接口注释约束，后续实现者可能改成强失败，影响 feed 页面可用性。

### 审查后立即修正

- 已移除 `FeedCardBaseVO` 中误导性的 `authorNickname/authorAvatar` 字段，数据结构与共享缓存边界重新一致。
- 已同步简化 `FeedCardRepository.copyStable(...)`，不再依赖“先带脏字段、再强制清空”的补丁式写法。
- 已修复 `ContentDetailQueryService` 的乱码注释，重新写清 L2 TTL、负缓存 TTL 与坏 key 处理规则。
- 已给 `IFeedFollowSeenRepository.batchSeen(...)` 写死失败语义，并在 `FeedFollowSeenRepository` 中实现异常降级：Redis 批量查询失败时记录告警并返回空集合，不阻断 feed 主流程。

## 2026-03-11 缓存方案并发极限 Code Review

- 执行者：Codex（Linus-mode）
- 目标：只盯高并发下的 corner case，不讨论常规功能路径

### 【品味评分】
- 🟡 凑合

### 【致命问题】
- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/support/SingleFlight.java:21`
  当前 `SingleFlight` 用 `future.join()` 无超时、无中断传播、无降级出口。只要 leader 线程卡死在 DB/Redis/网络调用上，同 key 的所有 waiter 会无限挂死。这不是“慢一点”，这是并发放大器：一个 leader 卡住，一串业务线程陪葬。
- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/ContentRepository.java:242`
  `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/ContentRepository.java:656`
  `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/CommentRepository.java:115`
  `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/CommentRepository.java:425`
  批量 single-flight 的 key 是“整批 miss 集合”，不是“单个热点对象”。这会让重叠批次完全无法合并：`[1]`、`[1,2]`、`[1,2,3]` 在高并发下会各自回源，热点越集中，浪费越大。再加上它只是进程内单飞，多实例部署时每个节点都会各打一遍。
- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/CommentRepository.java:273`
  `pageReplyCommentIds` 的 preview cache 在 L1/L2 miss 后直接回 DB，再写回缓存，中间没有 single-flight。也就是说，最热的“公共首屏回复预览”在失效瞬间会发生标准惊群：100 个并发请求就 100 次同库查询。

### 【高风险问题】
- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/RelationAdjacencyCachePort.java:54`
  `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/RelationAdjacencyCachePort.java:58`
  关系 followers/following 现在彻底直读 DB 真相源。这本身不脏，但它把系统承压点完全押给索引与执行计划。只要目标侧复合索引没建好、统计信息漂了、执行计划抖了，就没有任何缓存缓冲层能替你扛流量。
- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/FeedFollowSeenRepository.java:47`
  `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/FeedFollowSeenRepository.java:75`
  `batchSeen` 现在是 best-effort，这个方向对；但 pipeline 结果只按 `min(normalized.size(), raw.size())` 消化。Redis 抖动或客户端返回半截结果时，会静默把部分“已读”当“未读”。它不会打挂主流程，但会在流量高峰制造肉眼可见的已读抖动。

### 【改进方向】
- 把 `SingleFlight` 从“无限等待”改成“有限等待 + 快速失败/降级”。没有超时的单飞，品味很差，因为它把单点慢调用升级成系统级阻塞。
- 批量热点不要按“整批 miss 集合”做单飞；至少按单 ID 或小分片做，消掉重叠批次重复回源这个特殊情况。
- 给 `CommentRepository.pageReplyCommentIds` 的 preview 重建加单飞；这个路径就是热点页，最不该裸奔回库。
- 关系 followers 路径继续保留 DB 真相源可以，但要把“目标侧复合索引是硬前提”当成上线闸门，不是口头提醒。
- `batchSeen` 要么显式要求“结果不完整直接整批按未读”，要么至少把 partial result 打出告警，不要默默吞掉。

## 2026-03-11 缓存方案并发极限 Code Review（第二轮，仅保留非改不可项）

- 结论：上一轮有些点更像吞吐优化，不是必须改。这一轮只保留 2 个真正会导致错误结果或长时间脏读的问题。

### 真正高风险
- `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/cache/ContentCacheEvictPort.java:73`
  `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/support/ContentDetailQueryService.java:66`
  `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/support/ContentDetailQueryService.java:40`
  内容详情先查本地 `localCache`，TTL 是 1 小时；跨节点本地失效完全依赖 MQ 广播。现在 MQ 发布失败只写日志、不补偿、不重试。结果是：当前节点已经删 Redis，但其他节点如果本地还有旧快照，会继续直接命中本地缓存，最长返回 1 小时旧数据。这不是性能问题，是错误结果。
- `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/cache/ContentCacheEvictPort.java:69`
  `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/cache/ContentCacheEvictPort.java:102`
  这套“双删 + 1 秒延迟删”依赖进程内单线程调度器。高并发写入下，如果进程重启、线程卡住、调度堆积，第二次删除会直接丢失。那条经典竞态——读线程先读旧库，再在写事务提交后把旧值回填缓存——就可能把旧内容留满整个 Redis TTL。尤其 `detail` 是 `24h + 0~1h`，一旦发生就是长时间脏读。

### 次高风险但可接受
- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/PostAuthorPort.java:97`
  `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/cache/ContentCacheEvictPort.java:44`
  `PostAuthorPort` 的负缓存没有进入内容失效矩阵。理论上若某个 postId 在内容正式可见前被查过，会留下 30~40 秒 `NULL`，后续点赞链路可能把真实存在的内容误判成 NOT_FOUND。这个问题成立，但触发面比前两个小得多。
