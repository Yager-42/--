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

---

# 追加：通知业务最小自测（不跑 Maven）

日期：2026-01-21  
执行者：Codex（Linus-mode）

目标：按 `.codex/notification-business-implementation.md` 第 11 章验证通知链路能走通（事件 -> 聚合写 -> 读 -> 已读）。

## 0. 前置条件

1) MySQL：已执行 `project/nexus/docs/social_schema.sql` 中新增的两张表 DDL：`interaction_notification`、`interaction_notify_inbox`。  
2) RabbitMQ：可连接，且已声明队列 `interaction.notify.queue`（由 Spring 配置自动声明）。  
3) 应用启动：HTTP 请求携带 Header `X-User-Id: <Long>`（由 `UserContext` 注入）。  

## 1. 点赞通知（只统计新增）

1) 用户 A 对用户 B 的 post 连续点两次赞（ADD）：  
   - 期望：只产生 1 次 `LIKE_ADDED`（delta=+1 才发），`POST_LIKED.unread_count` 只 +1。  
2) 用户 A 再取消赞（REMOVE）：  
   - 期望：不产生通知（只统计新增）。  

## 2. 评论通知（direct/reply 不重复）

1) 用户 A 直接评论用户 B 的 post（parentId=null）：  
   - 期望：用户 B 收到 `POST_COMMENTED.unread_count +1`。  
2) 用户 A 回复用户 B 的评论（parentId!=null）：  
   - 期望：只通知“被回复的评论作者”，不再同时通知 post 作者（消除重复）。  

## 3. @提及通知（去重）

1) 用户 A 评论内容包含 `@username`：  
   - 期望：被提及用户收到 `COMMENT_MENTIONED.unread_count +1`。  
2) 若被提及用户本来就是主收件人（post 作者或被回复评论作者）：  
   - 期望：消费端丢弃提及事件，不产生双通知。  

## 4. 列表 + 已读

1) 调用 `GET /api/v1/notification/list`：  
   - 期望：按 `update_time DESC, notification_id DESC`；`nextCursor` = `{updateTimeMs}:{notificationId}`。  
2) 调用 `POST /api/v1/notification/read` body：`{"notificationId":<id>}`：  
   - 期望：该条 `unread_count=0` 且不会再出现在 list。  
3) 调用 `POST /api/v1/notification/read/all`：  
   - 期望：该用户所有通知 `unread_count=0`，list 返回空。  

---

# 追加：分发/Feed 缺口最小自测（不做 Phase 3 推荐）

日期：2026-01-22  
执行者：Codex（Linus-mode）

## 0. 本地编译验证（已执行）

在 `project/nexus` 下执行：`mvn -DskipTests package`。

## 1. 铁粉集合生成（interaction.notify -> feed:corefans）

1) 用户 A 先关注用户 B（确保 relation ACTIVE）。  
2) 用户 A 对用户 B 的 post 点赞（ADD）或发表评论。  
3) 期望：Redis `SISMEMBER feed:corefans:{B} A == 1`，且 key TTL 被刷新（`feed.corefans.ttlDays`）。  

## 2. unfollow 立刻生效（事件驱动强制重建）

1) 用户 A 关注用户 B，并确保 A 在线（inbox key 存在）。  
2) 用户 A 调用 `POST /api/v1/relation/unfollow` 取消关注 B。  
3) 期望：A 的 inbox 被强制重建；下一次拉取 timeline 不再包含 B 的内容。  

## 3. HotKey + L1 回表缓存（热点 postId）

前置：启动 etcd + HotKey worker + dashboard，并配置规则 `prefix=post__`。  
验证点：对同一个 postId 高频拉 timeline 时，`JdHotKeyStore.isHotKey(\"post__<postId>\")` 变为 true 后，回表会开始命中本地 L1（Caffeine，短 TTL）。  



---

# 追加：Phase 3 推荐流最小自测（按 11.11.9）

日期：2026-01-26  
执行者：Codex（Linus-mode）

## 0. 本地编译验证（已执行）

在 `project/nexus` 下执行：`mvn -DskipTests package`。  
结果：BUILD SUCCESS（Finished at: 2026-01-26T12:18:19+08:00）。

## 1. 前置条件（最小可跑）

1) MySQL：`content_post` 至少有 30 条 `status=2` 的内容（否则翻 5 页没意义）。  
2) Redis：可连接。  
3) RabbitMQ：可连接（Phase 3 的 item/feedback 写入走 MQ）。  
4) 应用启动：使用 `application-dev.yml`；HTTP 请求携带 Header `X-User-Id: <Long>`。  
5) Gorse：可选；不可用也必须能降级（用例 4）。

## 2. RECOMMEND 翻页稳定（用例 1）

接口：GET `/api/v1/feed/timeline?feedType=RECOMMEND&limit=10&cursor=<可选>`。

1) 首页：不传 cursor（或传空字符串）。保存返回的 `nextCursor`（应为 `REC:{sessionId}:{scanIndex}`）。  
2) 连续翻 5 页：用上一页的 `nextCursor` 作为下一页 cursor。  
   - 期望：不重复、不漏页（cursor 持续推进，接口不会“卡住”）。  
   - 期望：每页作者去重（同一页里 `items[].authorId` 不重复）。  
3) 幂等重试：对任意一页，用同一个 cursor 重试 2 次。  
   - 期望：在 session TTL 内返回一致（items 与 nextCursor 保持一致）。  
4) 非法 cursor：传入一个明显不合法的 cursor（例如 `REC:bad`）。  
   - 期望：当作首页处理（不 500、能正常返回）。

## 3. 负反馈生效（用例 2）

1) 从 RECOMMEND 结果中挑一个 `postId` 作为 targetId。  
2) 提交负反馈：POST `/api/v1/feed/feedback/negative`，body 示例：

```json
{"targetId":123,"type":"game_news","reasonCode":"not_interested","extraTags":[]}
```

说明：
- `type` 可选：如果你知道该帖的 postType（来自 `content_post_type.type`），填入后会触发“postType 级过滤”；填空字符串则只验证 postId 过滤。  
3) 继续拉 RECOMMEND 翻页。  
   - 期望：该 postId 不再出现；若填写了合法 type，该 type 的内容也不再出现。  
4) 撤销负反馈：DELETE `/api/v1/feed/feedback/negative/{targetId}`（请求体可传 `{}`，服务端忽略）。  
   - 期望：success=true；后续该 postId 有机会再次出现（只要数据还在且能被扫到）。

## 4. 下架不穿透（用例 3）

1) 选择一个曾出现在 RECOMMEND 的 postId。  
2) 将 DB 中该记录的 `content_post.status` 改为非 2（例如 6：删除）。  
3) 同用户继续拉 RECOMMEND 翻页。  
   - 期望：该 postId 不再返回（回表过滤生效，即使推荐候选仍吐出该 id）。

## 5. gorse 不可用仍不白屏（用例 4）

1) 让 gorse 不可达：停止 gorse 或把 `feed.recommend.baseUrl` 配成不可达地址。  
2) 同用户拉 RECOMMEND 首页并连续翻页。  
   - 期望：接口仍返回内容（不 500、不空白），并且 cursor 可推进翻页（降级到全站 latest）。  
   - 期望：日志里能看到 `fallbackReason`（例如 GORSE_FAILED/GORSE_EMPTY）。

## 6. 可选：POPULAR / NEIGHBORS（M7）

- POPULAR：GET `/api/v1/feed/timeline?feedType=POPULAR&limit=10`（cursor 为空视为首页），`nextCursor` 形如 `POP:{offset}`。  
- NEIGHBORS：必须提供 cursor，格式 `NEI:{seedPostId}:0`，示例：`GET /api/v1/feed/timeline?feedType=NEIGHBORS&limit=10&cursor=NEI:123:0`。  
  - 期望：负反馈过滤生效、每页作者去重、nextCursor 继续推进（或为空表示结束）。
