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

