# Nexus MQ 链路整改方案（仅 RabbitMQ）

日期：2026-03-11
执行者：Codex

## 1. 目标

这份文档只讨论 `nexus` 项目里的 RabbitMQ 链路。

目标只有两个：
- 找出哪些链路已经有可靠重试/补偿
- 找出哪些链路还存在“失败后没有重试机制”的问题

边界也要说清楚：
- 本文只覆盖 RabbitMQ 发送、消费、DLQ、自动重放、补偿任务
- 非 RabbitMQ 链路不在本文范围内

## 2. 统一整改原则

### 2.1 发送端原则

发送端只允许两种模式：
- 关键业务链路：必须走 outbox + 定时重发
- 明确允许丢失的旁路链路：允许 best-effort，但必须由业务 Owner 明确签字

禁止继续新增这种代码：
- `rabbitTemplate.convertAndSend(...)` 直接发完就算成功
- 没有 publisher confirm、没有 outbox、没有补偿任务
- catch 住异常只记日志，不向上暴露

### 2.2 消费端原则

消费端只允许三种结果：
- 成功处理：ACK
- 临时失败：进入统一重试流程
- 永久失败：进入 DLQ，后续自动重放或人工处理

禁止继续新增这种代码：
- `@RabbitListener` 里 catch 住异常后直接 return
- 失败只进 DLQ，但没有自动重放任务，也没有明确人工处理手册
- 失败后改写业务状态，然后把 MQ 失败伪装成“已经处理完成”

### 2.3 幂等原则

只要存在“重试”或“重放”，就必须先解决幂等。

统一要求：
- 每条消息必须有稳定的 `eventId` 或 `outboxId`
- 消费端必须基于 `eventId` 做幂等校验
- 幂等落点必须是持久化的，不能只靠进程内内存
- 任何会写库、发通知、改计数的消费者，都不允许在无幂等保护下重放

### 2.4 统一重试参数

为了避免实现阶段自己乱定参数，本文直接拍板统一值：
- 容器内瞬时重试：`5` 次
- 瞬时重试退避：`200ms -> 400ms -> 800ms -> 1600ms -> 3200ms`，上限 `5000ms`
- DLQ 自动重放节奏：`1m / 5m / 15m / 1h / 6h / 24h`
- 自动重放跑完仍失败：进入 `FINAL_FAILED`

这几个值在本轮整改里视为统一标准：
- 不允许单个链路私自改成别的数字
- 如果后续要统一调参，必须改公共配置，不允许散落在各个消费者类里

## 3. 当前 RabbitMQ 链路分组

### 3.1 已有可靠重试 / 补偿闭环

这些链路已经有比较完整的可靠投递或消费补偿，不是本轮优先整改对象，但后续要向它们统一对齐：
- 内容事件发布：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/ContentEventOutboxPort.java`
- 内容事件补偿：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/job/social/ContentEventOutboxRetryJob.java`
- 用户事件发布：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/user/port/UserEventOutboxPort.java`
- 用户事件补偿：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/job/user/UserEventOutboxRetryJob.java`
- 关系事件发布：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/job/social/RelationEventOutboxPublishJob.java`
- 关系事件重放：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/job/social/RelationEventRetryJob.java`
- Like/Unlike 批量消费：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/config/LikeUnlikeListenerContainerConfig.java`
- 搜索索引消费：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/config/SearchIndexMqConfig.java`
- Reaction Sync：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/ReactionSyncConsumer.java`

这些链路说明了一件事：
- 项目里已经有 outbox、容器重试、延迟重投、DLQ 这些成熟做法
- 所以本轮整改不要再自研第三套方案，直接复用现有模式

### 3.2 只有 DLQ，没有自动重放闭环

这些链路失败后虽然不会直接吞掉，但当前只做到“打入 DLQ”，还没有完整的自动重放：
- Feed fanout dispatcher：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FeedFanoutDispatcherConsumer.java`
- Feed fanout task：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FeedFanoutTaskConsumer.java`
- 定时发布消费：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/ContentScheduleConsumer.java`
- 定时发布 DLQ 监听：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/ContentScheduleDLQConsumer.java`
- 推荐 item upsert：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FeedRecommendItemUpsertConsumer.java`
- 互动通知消费：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/InteractionNotifyConsumer.java`

这些链路的核心问题不是“没有 DLQ”，而是：
- 消息进入 DLQ 后，没有统一 replay job 接管
- 一旦没有人工盯盘，就会长期堆死
- 从用户视角看，这和“静默丢失”没有本质区别

### 3.3 明确没有重试机制，或者失败被吞掉

#### 3.3.1 发送端裸奔

下面这些发送端是直接发 MQ，没有 outbox，没有统一补偿：
- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/LikeUnlikeEventPort.java`
- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/CommentEventPort.java`
- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/InteractionNotifyEventPort.java`
- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/RecommendFeedbackEventPort.java`
- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/RiskTaskPort.java`
- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/ContentScheduleProducer.java`
- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/content/port/ContentCacheEvictPort.java`

这类代码的致命问题很简单：
- 业务提交成功了，不代表消息真的发出去了
- 一旦 broker 抖动、连接瞬断、confirm 失败，这条消息就没了
- 没有落库凭证，后面连补发都没法做

#### 3.3.2 消费失败被吞掉

下面这些消费者会 catch 异常后只打日志，或者把失败改写成业务状态，但不会进入统一 MQ 重试：
- `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FeedRecommendFeedbackConsumer.java`
- `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FeedRecommendFeedbackAConsumer.java`
- `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FeedRecommendItemDeleteConsumer.java`
- `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/PostSummaryGenerateConsumer.java`
- `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/RiskImageScanConsumer.java`
- `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/RiskLlmScanConsumer.java`

这类代码的问题更糟：
- MQ 以为消费成功了
- 业务其实失败了
- 后面既不会自动重试，也不会进 DLQ
- 这不是降级，这是无声数据丢失

## 4. 整改优先级

### 4.1 P0：必须立刻改

P0 的判断标准只有两条，满足任意一条就必须先改：
- 发送失败可能直接丢消息
- 消费失败被吞掉，导致 MQ 误判为成功

本轮 P0 清单：
- 全部裸奔发送端：`LikeUnlikeEventPort`、`CommentEventPort`、`InteractionNotifyEventPort`、`RecommendFeedbackEventPort`、`RiskTaskPort`、`ContentScheduleProducer`
- 条件 P0：`ContentCacheEvictPort` 在业务 Owner 没有明确签字前，也按 P0 处理
- 全部吞异常消费者：`FeedRecommendFeedbackConsumer`、`FeedRecommendFeedbackAConsumer`、`FeedRecommendItemDeleteConsumer`
- 用户可见闭环缺口：`InteractionNotifyConsumer` 已有失败标记和 DLQ，但没有自动 replay job，按 P0 处理

### 4.2 P1：第二批改

P1 的含义不是“不重要”，而是当前至少不会立刻被吞掉，但闭环还没做完：
- `FeedFanoutDispatcherConsumer`
- `FeedFanoutTaskConsumer`
- `ContentScheduleConsumer`
- `ContentScheduleDLQConsumer`
- `FeedRecommendItemUpsertConsumer`
- `PostSummaryGenerateConsumer`
- `RiskImageScanConsumer`
- `RiskLlmScanConsumer`

它们的统一目标是：
- 从“只有失败记录”升级成“有自动 replay 的完整闭环”

### 4.3 P2：只有明确签字才允许降级

当前默认没有任何链路自动归类为 P2。

只有同时满足下面两个条件，才允许改成 best-effort：
- 丢一条消息不会造成用户可见错误，也不会造成长期数据错误
- 业务 Owner 明确签字确认“允许丢消息”

没有签字，就不要自作聪明地降级。

## 5. 统一整改动作

### 5.1 发送端统一改造模板

所有 P0 发送端统一按 `ContentEventOutboxPort` / `UserEventOutboxPort` 的模式改，不允许每条链路各写一套。

统一做法：
- 业务事务内先写 outbox 表，不直接把“数据库成功”和“MQ 成功”绑成双写幻想
- outbox 字段至少包含：`id`、`eventId`、`exchange`、`routingKey`、`payload`、`status`、`retryCount`、`nextRetryAt`、`lastError`
- 定时任务只扫描 `PENDING` 和 `RETRY_PENDING`
- 发布成功后改成 `SENT`
- 发布失败后递增 `retryCount`，并计算下一次 `nextRetryAt`
- 超过自动重发上限后改成 `FINAL_FAILED`，等待人工处理

硬性要求：
- 不允许发送端自己拼一套新的表结构命名
- 不允许一个链路走 outbox，另一个链路继续裸发
- 不允许“先发 MQ，再写库兜底”这种垃圾双写

### 5.2 消费端统一改造模板

所有消费者统一按 `LikeUnlikeListenerContainerConfig`、`SearchIndexMqConfig`、`ReactionSyncConsumer` 这三种已存在模式收敛：
- 容器内瞬时重试：用于处理短暂抖动
- 超过瞬时重试后进入 DLQ：用于隔离持续失败
- DLQ 自动重放：用于真正补齐闭环

消费端统一规则：
- 业务异常默认抛出，不允许 catch 后 return
- 只有明确判定为“永远重试也没用”的非法消息，才允许直接送 DLQ
- 任何写库、发通知、改状态的消费者都必须先做幂等检查
- replay 时必须带上 attempt 计数，超过上限后停止自动重放

### 5.3 DLQ 自动重放统一模板

只要链路存在 DLQ，就必须补 replay 机制，不能只停在“有人出问题时再手工捞”。

统一做法：
- 新增统一的 replay job 或按业务域拆分 replay job，但实现模板必须一致
- replay 数据源可以是 outbox/inbox 失败表，也可以直接消费 DLQ 后转存失败表
- 每条失败记录至少要有：`eventId`、`originalQueue`、`payload`、`attempt`、`nextRetryAt`、`lastError`
- replay 成功后标记 `DONE`
- replay 达到上限后标记 `FINAL_FAILED`

本文直接拍板：
- `FeedFanoutDispatcherConsumer`、`FeedFanoutTaskConsumer`、`ContentScheduleConsumer`、`FeedRecommendItemUpsertConsumer`、`InteractionNotifyConsumer` 都必须补 replay job
- `ContentScheduleDLQConsumer` 不允许继续只打日志，必须改成“落失败记录 + 等待 replay job 处理”

### 5.4 幂等统一模板

只要消息可能重试，就必须回答这三个问题：
- 同一条消息被执行两次，会不会重复写库？
- 同一条消息被执行两次，会不会重复发通知？
- 同一条消息被执行两次，会不会把状态机推进错位？

统一要求：
- 发送端生成稳定 `eventId`
- 消费端按 `eventId + consumerName` 建唯一幂等记录
- 先落幂等记录，再做副作用；或者在副作用表上直接建唯一约束
- 不允许用本地缓存做幂等，因为进程重启就没了

## 6. 不允许 Codex 自行拍板的规则

下面这些事情，如果方案里没写清楚，Codex 不允许自己决定：
- 不允许自己决定某条链路是否可以丢消息
- 不允许自己给某条链路单独发明一套 retry 次数和 backoff
- 不允许自己新增一套和现有 outbox 不兼容的表结构
- 不允许自己把异常降级成 `log.warn(...)` 然后继续返回成功
- 不允许自己删除已有 DLQ，只为了让代码看起来简单
- 不允许自己假设“这个失败概率很低，所以不用补偿”
- 不允许自己跳过幂等，只因为“理论上不会重复消费”

如果实现时遇到本文没拍板的情况，默认动作只有一个：
- 停下来，补方案，不准靠猜

## 7. 建议实施顺序

建议按下面顺序推进，别到处并发乱改：
1. 先抽一套统一的 RabbitMQ 可靠消息基础设施：outbox 字段、状态枚举、retry policy、replay policy
2. 先改全部 P0 发送端，把裸发清零
3. 再改全部 P0 消费端，把“吞异常”清零
4. 给所有“只有 DLQ 没 replay”的链路补 replay job
5. 最后统一收口监控、告警、人工处理手册

这个顺序不能倒过来：
- 如果先改消费者，不改发送端，消息还是会在发送阶段丢掉
- 如果只有 DLQ，没有 replay，系统只是把问题藏到另一处

## 8. 完成标准

整改完成后，每条 RabbitMQ 链路都必须能回答下面 8 个问题，而且答案不能含糊：
1. 发送失败时，消息会不会丢？
2. 如果不会丢，凭证落在哪里？
3. 消费临时失败时，谁来重试？
4. 重试次数和间隔是多少？
5. 超过重试上限后，消息去哪？
6. 后续是谁来自动重放？
7. 自动重放时，如何保证幂等？
8. 最终失败后，人工处理入口在哪里？

验收口径也直接拍板：
- 不再存在关键链路裸 `convertAndSend`
- 不再存在 catch 异常后直接吞掉的 RabbitMQ 消费者
- 不再存在“只有 DLQ，没有 replay job”的关键链路
- 不再存在没有 `eventId` 的可重试消息
- 不再存在每条链路自定义一套 retry 参数的情况

## 9. 一句话结论

现在 `nexus` 的 RabbitMQ 问题，不是“完全没有重试机制”，而是“已经有几条链路做得像样，但剩下不少链路还在裸奔、吞异常、或者只有 DLQ 没闭环”。

这轮整改的正确方向不是继续补零散 if/else，而是把所有 RabbitMQ 链路统一收敛到两套成熟模式：
- 发送端：outbox + retry job
- 消费端：容器重试 + DLQ + replay job + 幂等
