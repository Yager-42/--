# verification.md

日期：2026-01-20  
执行者：Codex（Linus-mode）

## 点赞业务（三条链路）交付物验收

### 链路 1：在线写入（HTTP -> Redis）

- [x] 写接口：`POST /api/v1/interact/reaction` 已实现真实链路（不再是占位返回 0/1）。
- [x] 幂等语义：只支持 set-state（ADD/REMOVE），重复请求不会把计数算飞。
- [x] `requestId`：请求可选传入，服务端必回传；并写入结构化日志用于串联排障。

### 链路 2：延迟落库（RabbitMQ 延迟队列 -> DB）

- [x] 延迟队列：x-delayed-message 拓扑已落地（exchange/queue/routingKey + DLQ）。
- [x] 消费者：Redis 锁 + attempt 重投递（抢不到锁/短暂失败不吞消息）。
- [x] 同步策略：opsKey -> processingKey 快照（Lua + RENAME）避免并发丢更新；批量 upsert/delete 事实表；覆盖写 count 表。

### 链路 3：实时监控/热榜/动态窗口（日志 -> Kafka -> Redis）

- [x] 结构化日志：在线写成功后打印 `event=reaction_like` 的 JSON 行（字段对齐说明书 13.1）。
- [x] 回写消费者：消费 `topic_like_5m_agg` 回写 Redis 热榜 `hot:like:5m:{targetType}` 并写入 `window_ms`（EX=60s）。
- [x] 告警消费者：消费 `topic_like_hot_alert`，最小告警 `log.warn(...)`。

## 验收与自测

- [x] 验收说明书：`.codex/interaction-like-pipeline-implementation.md`（第 16/17 章）。
- [x] 关键决策留痕：`.codex/operations-log.md`。
- [x] 代码审查报告：`.codex/review-report.md`。
- [x] 最小自测清单：`.codex/testing.md`（不依赖 Maven）。

---

日期：2026-01-22  
执行者：Codex（Linus-mode）

## 分发/Feed 缺口补齐验收（不做 Phase 3 推荐）

- [x] unfollow：新增 `POST /api/v1/relation/unfollow`，并通过 `RelationFollowEvent(status=UNFOLLOW)` 驱动 Feed 侧立刻生效（在线用户强制重建 inbox）
- [x] 铁粉集合生成：消费 `interaction.notify`（`LIKE_ADDED(仅 POST)`/`COMMENT_CREATED`）自动写入 `feed:corefans:{authorId}`，并刷新 TTL
- [x] 热点回表 L1：`ContentRepository` 对热点 postId 启用 `JD HotKey + Caffeine` 短 TTL 缓存；写路径会 invalidate
- [x] 本地编译验证：`project/nexus` 下执行 `mvn -DskipTests package` 通过

