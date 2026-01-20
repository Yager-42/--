# operations-log.md

日期：2026-01-16  
执行者：Codex（Linus-mode）

## 这次我做了什么

1) 用 Playwright 打开并读取微信文章（指定 URL）。  
2) 精确抽取文章里的两段：`解法 2：WAL + 智能聚合` 与 `解法 3：三级存储 + TaiShan KV`。  
3) 以“写入走 WAL + 聚合，读取走三级缓存”的思路，把两者组合成一个可落地的点赞系统方案。  
4) 输出实现方案文档与验收/验证清单。
5) 重写点赞方案文档：补全可实现的接口/数据结构/写读链路/幂等语义/边界条件与验收测试，删除口水内容，方便其它 agent 直接借鉴。
6) 在方案文档中补充“组件清单（建议默认选型）”，明确必选与建议组件。
7) 根据用户反馈修正文档：严格按原文语义重写“解法 2/解法 3”，恢复热点探测→本地缓存→Redis→TaiShan KV 的层级，并按原文加入 TiDB+TiCDC；同时明确解法 2 的“写内存+本地 WAL+动态窗口+MQ 异步落库”顺序。
8) 按用户指定，将热点探测组件选型明确为“京东 hotkey detector”。

## 关键工程决策（Good Taste）

- “点赞”不是一个数字，是两份数据：`用户是否点赞(状态)` 与 `总点赞数(聚合)`；状态是事实，计数是派生结果。  
- 写入用事件模型统一：`set_like_state(user, target, desired_state)`，避免“toggle/反转”制造特殊情况。  
- 高并发写入不直接打 DB：先写 WAL（消息日志），再在消费者侧做动态窗口聚合与批量落库。  
- 读路径以缓存为主：L1 本地缓存 + L2 Redis + L3 持久化 KV/DB；不让用户体验依赖 DB 的实时性。

## 2026-01-16（流程图版点赞链路）

### 这次我做了什么

1) 读取用户提供的点赞链路流程图，拆出 4 条链路：在线写入、延迟落库、实时监控、离线分析。  
2) 对齐现有接口契约：`POST /api/v1/interact/reaction`（见 `社交接口.md`）与现有入口实现：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/InteractionController.java`。  
3) 复用项目已有 RabbitMQ 延迟队列范式（`ContentScheduleDelayConfig` / `ContentScheduleConsumer`）作为点赞同步落库的基础模式（同样的延迟交换机、Redis 锁、DLQ）。  
4) 确认点赞当前仅为占位实现：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/InteractionService.java` 的 `react()` 需要按方案落地。  
5) 准备输出新的“可照抄实施”说明书：`.codex/interaction-like-pipeline-implementation.md`。

### 关键工程决策（Good Taste）

- 点赞的核心不是一个数字，是两份数据：`LikeState(事实)` 与 `LikeCount(派生)`；计数永远不作为真相。  
- 只接受 **set state**（ADD/REMOVE = desiredState 1/0），拒绝 toggle 这种制造特殊情况的垃圾接口。  
- Redis Cluster 必踩坑：Lua 脚本与 `RENAME` 涉及的 key 必须共享同一个 hash-tag（按 target 归槽），否则直接运行失败。  
- 延迟落库并发不靠“读完再删”的补丁：用 `opsKey -> processingKey` 的原子快照（RENAME）消除丢数据特殊情况。  
- 热点治理遵循“先简单再增强”：先做动态 delayWindow（按 like_count/热度缩短同步窗口）；再可选接入 HotKey Detector + Caffeine L1 做读侧隔离。

## 2026-01-16（补齐链路 3/4 的可运行配置占位）

1) 在 `.codex/interaction-like-pipeline-implementation.md` 增加“四条链路 <-> 步骤对照表”，并补齐 Step 9-12，把链路 3/4 明确为“项目内可验收 + 外部系统交付物”。  
2) 新增外部配置占位目录：`project/nexus/docs/analytics/like-pipeline/`，提供 Logstash/Flink/Hive/Spark/Prometheus 的模板与 README 流程。  
3) 让用户在**不写 Java**的情况下也能跑通链路 3/4：直接往 Kafka 写 JSON 事件即可验证 Flink 聚合输出。

## 2026-01-16（新增 S2+S3 可借鉴点对照文档）

- 新增：`.codex/interaction-like-s2-s3-adoption-notes.md`，把原文严格版（S2+S3）与当前流程图方案的“可借鉴点/不借鉴点”写成可执行取舍清单，并标注原因与适用条件。


## 2026-01-20（补齐点赞说明书缺口）

### 这次我做了什么

1) 补齐“读接口：查询是否点赞(state)”的接口契约与实现步骤（10.4 / Step 8.1 / 14.3.2）。
2) 修正文档矛盾：明确需要在 `InteractionController` 组装响应时回传 `requestId`（保持路由不变）。
3) 修正 Redis 原子脚本：`cntKey` 丢失时用 `SCARD(setKey)` 重建，避免 `DECR` 负数；并补齐 `getCount` 的同类重建逻辑（11.4 / 14.3.1）。
4) 补齐延迟同步 consumer 的锁竞争处理：通过 `attempt` + 短延迟重投递避免消息被 ack 掉后无人再触发（12.2 / 12.3）。
5) 补齐本地依赖推荐版本与“先跑链路 1+2，再补链路 3”的最小跑法（Step 0.0）。

### 关键工程决策（Good Taste）

- `cntKey` 不是“另一个真相”，它丢了就用 `setKey` 的基数重建，把特殊情况变成普通情况。
- `state` 查询要走 Redis set 的 Exists（近实时），不要拿最终一致的 DB 去当实时数据源。
- 锁抢不到不能直接吞消息：吞了就可能永远不 sync；重投递才是正确的工程语义。


### 2026-01-20（接口名/字段名复核）

- 将 state 查询接口定为 GET（对齐现有 controller 的 GET 查询风格）。
- 将 `state` 字段定为 boolean（true/false），并移除 state 查询的 `requestId/success` 字段（查询接口不需要重复“成功语义”）。


## 2026-01-20（落地点赞三链路代码）

### 这次我做了什么

1) 按 `.codex/interaction-like-pipeline-implementation.md` 的 Step 清单，把点赞业务链路 1/2/3 的代码落进 `project/nexus`（不再是文档/占位）。  
2) 写接口兼容升级：`ReactionRequestDTO/ReactionResponseDTO` 增加 `requestId`（可选传入，服务端必回传），并新增 `GET /api/v1/interact/reaction/state`（返回 `state + currentCount`）。  
3) Domain 落地：新增 `ReactionLikeService` + VO/端口/仓储接口；彻底移除 `react()` 的 0/1 垃圾占位逻辑，改为委托真实链路。  
4) Redis 落地：`ReactionCachePort` 实现 Lua 原子写 + RENAME 快照 + 热点读 L1 Caffeine，并接入 `JdHotKeyStore.isHotKey("like__"+tag)`。  
5) MyBatis 落地：新增 reaction 事实/计数 DAO + Mapper XML + `ReactionRepository`，支持批量 upsert/delete 与覆盖写 count。  
6) MQ 落地：新增 RabbitMQ 延迟队列 `ReactionSyncDelayConfig/Producer/Consumer`，用 Redis 锁 + attempt 重投递避免悬挂，并提供 DLQ 最小告警消费者。  
7) 实时链路落地：新增 Kafka consumers 消费 `topic_like_5m_agg/topic_like_hot_alert`，回写 Redis 热榜与 `window_ms`，并打印热点告警。  
8) 启动配置补齐：`application-dev.yml` 增加 `spring.kafka.*` 与 `hotkey.*`。  

### 关键工程决策（Good Taste）

- 禁止 toggle：对外只接受 ADD/REMOVE(set-state)，幂等由 Redis `SADD/SREM` 的返回值天然保证。  
- 消灭“并发丢数据”特殊情况：同步链路用 `opsKey -> processingKey` 的原子快照（Lua + RENAME），同步期间的新写入落在新的 opsKey，再触发一次延迟同步。  
- 热点读不搞花活：只缓存 count，热点才启用 L1，且一律 L1-first（miss 回源 Redis 并回填），不做 L1-only 绕过 Redis。  
