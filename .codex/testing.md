# .codex/testing.md

日期：2026-01-20  
执行者：Codex（Linus-mode）

目标：按 `.codex/interaction-like-pipeline-implementation.md` 第 17 章做“最小自测”，验证三条链路从业务上走得通（不要求 Maven 编译通过）。

## 0. 前置条件（最小可跑）

1) MySQL：已执行 `project/nexus/docs/social_schema.sql` 中新增的两张表 DDL。  
2) Redis：可连接。  
3) RabbitMQ：已启用 `x-delayed-message` 插件。  
4) 应用启动：使用 `application-dev.yml`，并确保 HTTP 请求携带 Header `X-User-Id: <Long>`（由 `UserContextInterceptor` 注入）。

可选（链路 3/热点治理才需要）：Kafka / Flink / Logstash / etcd + HotKey worker + dashboard。

## 1. 链路 1 自测（在线写入：幂等 + requestId）

准备一个 target：`targetType=POST, targetId=90001, type=LIKE`。

1) 连续两次点赞（ADD）：
   - 期望：第一次 `currentCount` +1；第二次不变（delta=0）。
2) 连续两次取消（REMOVE）：
   - 期望：第一次 `currentCount` -1；第二次不变（delta=0）。
3) 不传 `requestId`：
   - 期望：响应里必有 `requestId`，且日志里出现同一个 `requestId`。
4) 传入 `requestId=abc`：
   - 期望：响应回传 `requestId=abc`（trim 后），日志同样打印该值。

## 2. 链路 1.1 自测（读接口：state + currentCount）

调用：`GET /api/v1/interact/reaction/state?targetId=90001&targetType=POST&type=LIKE`

1) 在“ADD 之后”查询：
   - 期望：`state=true`，`currentCount` 为近实时计数。
2) 在“REMOVE 之后”查询：
   - 期望：`state=false`，`currentCount` 为近实时计数。

## 3. 链路 2 自测（延迟落库：最终一致 + 不丢更新）

为了本地不用等 5 分钟（默认 window=300000ms），建议用 Redis 临时写入动态窗口：

- `SET interact:reaction:window_ms:{POST:90001:LIKE} 3000 EX 60`

验证点：

1) 写接口触发后，RabbitMQ 中应出现延迟消息消费（首次 pending 才投递）。  
2) 延迟后 DB 事实表 `interaction_reaction` 出现/消失对应行（按 userId）。  
3) 延迟后 DB 计数表 `interaction_reaction_count.count` 与 Redis `interact:reaction:cnt:{...}` 对齐。  
4) 并发不丢：在同步执行期间继续发写请求，最终 DB 状态必须与 Redis set 一致；若同步期间有新 ops，会自动再投递一次延迟消息。

## 4. 链路 3 自测（实时热榜 + window_ms + 告警）

最小验证不依赖 Logstash/Flink：你可以直接往 Kafka topic 写入一条模拟聚合结果，验证本项目 Consumer 的“回写逻辑”：

1) 向 `topic_like_5m_agg` 写入（示例）：

```json
{"targetType":"POST","targetId":90001,"reactionType":"LIKE","like_add_count":2000}
```

期望：
- Redis ZSET：`hot:like:5m:POST` 中 `member=90001` 的 score 更新。
- Redis String：`interact:reaction:window_ms:{POST:90001:LIKE}` 被写入并在 60s 后过期。

2) 向 `topic_like_hot_alert` 写入（示例）：

```json
{"targetType":"POST","targetId":90001,"reactionType":"LIKE","like_add_count":5000,"threshold":2000}
```

期望：
- 应用日志出现一条 `like hot alert ...` 的 warn 级别日志。

## 5. 热点治理自测（HotKey + L1）

前置：启动 etcd + HotKey worker + dashboard，并在 dashboard 配好规则 `prefix=like__`。

验证点：
- 对同一个 target 高频读 `getCount` 路径后：`JdHotKeyStore.isHotKey("like__{...}")` 变为 true；随后该 key 的读开始稳定命中 L1（Caffeine）。  

说明：HotKey 系统不可用时会自动退化为“全部冷 key”（不影响主链路正确性）。

