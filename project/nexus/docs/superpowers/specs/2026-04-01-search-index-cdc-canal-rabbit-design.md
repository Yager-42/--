# Search 索引同步从业务事件改为 Binlog CDC（Canal + RabbitMQ）设计稿

## 1. 目标

将 **Post 的 Elasticsearch（下称 ES）索引同步**从当前的“业务代码落库 + Outbox/业务事件 MQ”方式，改为“监听 MySQL binlog（CDC）→ RabbitMQ → 消费者更新 ES”的方式。

仅覆盖 **Post 的索引更新**（发布/更新/删除/类型变更导致的可索引性变化）。

## 2. 非目标（本次不做）

- 不迁移点赞数（`like_count`）的索引更新链路：继续保留现有 `CountPostLike2SearchIndex` 快照推送机制。
- 不迁移用户昵称变更（`user.nickname_changed`）的 ES 批量更新链路。
- 不改变 `social.feed` 相关业务事件的发布与消费（fanout、推荐等仍按现有方式运行）。

## 3. 现状（代码对齐）

### 3.1 Post → ES 同步方式（已切换为 CDC）

- 旧的 Post 索引 MQ 消费方案（`search.post.published / updated / deleted`）已废弃，不再作为运行路径保留。
- 当前生效链路为：
  - `SearchIndexCdcRawPublisher`：消费 Canal raw 队列，过滤 `content_post / content_post_type`，抽取 `postId`
  - `SearchIndexCdcConsumer`：消费 `search.post.cdc.queue`，回表执行 `SearchIndexUpsertService.upsertPost(postId)`
- `SearchIndexConsumer` 仅保留 `user.nickname_changed` 处理，不再承担 Post 索引同步。

### 3.2 点赞数 → ES 的专用链路（保留）

`CountPostLike2SearchIndex` 通过“聚合点赞事件 → 从 Redis 读计数快照 → 批量发 MQ → upsertPost(postId, likeCountOverride)”来更新 ES 文档里的 `like_count`，用于高频场景降成本。

## 4. 方案概述（CDC + RabbitMQ）

### 4.1 数据流

```
MySQL (nexus_social)
  └─ binlog (ROW)
      └─ Canal Server
          └─ CDC Publisher（消费 Canal(raw) 队列 → 抽取 postId 事件 → 再投递到 search.cdc.exchange）
              └─ RabbitMQ（search.cdc.exchange / search.post.cdc.queue）
                  └─ nexus-trigger：PostSearchIndexCdcConsumer
                      └─ SearchIndexUpsertService.upsertPost(postId)
                          └─ SearchEnginePort.upsert/softDelete → ES
```

### 4.2 设计原则

1. **消息尽量最小**：CDC 只发 `postId` 事件，不携带旧值/新值，避免“旧值覆盖新值”与 schema 变化连锁影响。
2. **回表构建真值**：消费者统一回表读取最新 post 状态并构建索引文档，复用 `SearchIndexUpsertService.indexable()` 规则。
3. **幂等优先**：CDC 天然可能重复与乱序；消费者必须幂等。

## 5. MQ 拓扑（与业务事件隔离）

为避免与现有 `social.feed` 混用，新增独立拓扑：

- Exchange：`search.cdc.exchange`（Direct，durable）
- RoutingKey：`post.changed`
- Queue：`search.post.cdc.queue`（durable）
- DLX：`search.cdc.exchange.dlx`（Direct，durable）
- DLQ：`search.post.cdc.dlq`

DLQ 策略：沿用项目当前 Rabbit Listener 的重试/DLQ 实践（最大重试次数、退避等由 Spring AMQP 统一配置或专用 containerFactory 控制）。

### 5.1 Canal(raw) 输入队列（由外部 Canal 投递）

本设计要求 Canal 将 raw 事件投递到 RabbitMQ 的一个输入队列（队列名由部署侧提供），应用侧仅通过配置读取：

- `search.index.cdc.raw.queue` / `SEARCH_INDEX_CDC_RAW_QUEUE`

应用侧不再提供 CDC 开关；raw->postId 转发逻辑常驻启用，只需要提供输入队列配置。

## 6. “postId 事件”消息协议（方案 2）

### 6.1 消息体（JSON）

```json
{
  "eventId": "mysql-bin.000123:456789:row1",
  "postId": 1774592424710704,
  "tsMs": 1712345678901,
  "source": "canal",
  "table": "content_post"
}
```

字段说明：
- `eventId`：全局唯一，推荐 `binlogFile:binlogPos:rowIndex`（Canal 能提供）。
- `postId`：索引更新的主键。
- `tsMs`：事件时间（用于观测与排障，不参与业务判断）。
- `table`：来源表，仅用于排障（消费者逻辑不依赖）。

### 6.2 事件触发条件（CDC Publisher 过滤规则）

CDC Publisher 只需在以下变更时发出 `post.changed`：
- `nexus_social.content_post` 的 INSERT/UPDATE/DELETE
- `nexus_social.content_post_type` 的 INSERT/UPDATE/DELETE（类型变更会影响索引 tags/分类）

说明：
- **不建议**把 raw row change 直接发给业务消费者：会把表结构变化、字段兼容、隐私字段等复杂度强行引入应用侧。
- 对于 `content_post_type` 变更，需要发布对应 `postId` 的 `post.changed`（从 row data 抽取 post_id）。

## 7. 消费者处理逻辑（nexus-trigger）

### 7.1 核心处理：统一 upsert（不分操作类型）

消费者收到 `post.changed` 后，统一调用：
- `SearchIndexUpsertService.upsertPost(postId)`

理由：
- 乱序时，消费者回表拿到“当前最新状态”并决定是否 `softDelete`，比“根据 CDC 的操作类型”更可靠。
- insert/update/delete 都可收敛成“把 ES 状态修正到与 DB 一致”。

### 7.2 幂等与重复消费

复用 `cn.nexus.infrastructure.mq.reliable.ReliableMqConsumerRecordService`：
- `start(eventId, consumerName, payloadJson)` 返回 `true` 才执行副作用
- 成功 `markDone`
- 异常 `markFail`（允许重放）

`consumerName` 建议固定为：`search-index-cdc`。

## 8. Canal / MySQL 前置条件

MySQL：
- `binlog_format=ROW`
- `binlog_row_image=FULL`（至少能拿到主键字段）
- Canal 账号具备必要复制权限（`REPLICATION SLAVE`, `REPLICATION CLIENT` 等）

Canal：
- 订阅过滤 `nexus_social.content_post`、`nexus_social.content_post_type`
- 输出必须包含 binlog 文件/位点信息以生成 `eventId`

## 9. 常驻策略（CDC-only）

目标：Post → ES 同步只保留 CDC 方案，应用侧不再提供 MQ/CDC 双路切换开关。

实现对齐（代码）：
- CDC consumer：`cn.nexus.trigger.mq.consumer.SearchIndexCdcConsumer`
- CDC 拓扑：`cn.nexus.trigger.mq.config.SearchIndexCdcMqConfig`
- CDC 事件类型：`cn.nexus.types.event.search.PostChangedCdcEvent`
- CDC publisher（raw->postId）：`cn.nexus.trigger.mq.consumer.SearchIndexCdcRawPublisher`
- 旧的 Post MQ consumer 已从 `SearchIndexConsumer` 中移除

上线顺序：
1) 先部署 Canal，并确认 raw 事件已稳定投递到 `search.index.cdc.raw.queue`
2) 部署应用（`nexus-trigger` 所在服务）
3) 观测：
   - `search.post.cdc.queue` 消费速率（无持续堆积）
   - ES 写入错误率 / DLQ 数量

说明：
- 应用配置只保留 raw 输入队列与 CDC 过滤/发布参数，不再保留 `search.index.sync.source`
- 如 CDC 方案异常，回退方式是回滚代码版本，而不是切运行时开关

## 10. 可观测性与排障

CDC consumer 日志建议输出：
- `eventId / postId / action(upsert|softDelete) / reason / costMs`

CDC Publisher 侧建议输出：
- 每分钟投递数量、过滤数量、异常次数
- 关键错误（Canal 断连、解析失败、投递 RabbitMQ 失败）

## 11. 风险清单

- Canal 未覆盖某些表导致“类型变更不触发索引更新”（因此必须订阅 `content_post_type`）。
- CDC 消息重复/乱序导致多次写 ES（幂等入口必须开启）。
- ES `upsert` 频率过高导致写放大（需要根据实际写入量评估是否要 batch 或限流；本次先复用现有同步方式）。
