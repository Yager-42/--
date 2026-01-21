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

