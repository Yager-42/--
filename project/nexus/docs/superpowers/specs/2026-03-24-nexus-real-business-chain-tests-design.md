# Nexus 真实业务链路测试设计

**目标**

为 `project/nexus` 建立按“业务场景”组织的真实链路测试，而不是按模块或类名堆测试文件。测试必须连接 WSL Docker 中实际运行的中间件，只排除 Kafka。

**设计原则**

1. 一个测试覆盖一个清晰业务场景，入口可以是 HTTP、RabbitMQ consumer、scheduled job。
2. 断言必须落到可观测结果：MySQL / Redis / Cassandra / Elasticsearch / MinIO / RabbitMQ 侧状态。
3. 测试使用唯一业务主键，避免相互污染。
4. 对已有真实集成测试基座复用，不重造轮子。
5. Kafka 链路明确跳过，不写占位测试。

## 现状

- HTTP 入口：91 个
- RabbitMQ consumer：32 个 Java 类，38 个 `@RabbitListener`
- Job 类：9 个 Java 类，13 个 `@Scheduled`
- 已有真实链路测试：8 个业务测试文件
- 现有测试基座：`nexus-app/src/test/java/cn/nexus/integration/RealMiddlewareIntegrationTestSupport.java`
- 已接入真实服务：MySQL / Redis / RabbitMQ / Cassandra / Elasticsearch / MinIO / Gorse / etcd / hotkey

## 业务场景矩阵

| 业务域 | 关键入口 | 真实依赖 | 现有真实覆盖 | 缺口 |
| --- | --- | --- | --- | --- |
| 认证与鉴权 | `AuthController` 登录/注册/短信/管理员授权 | MySQL, Redis | 无 | 需要完整 HTTP 链路测试 |
| 用户资料与隐私 | `UserProfileController`, `UserSettingController`, `InternalUserController`, `UserProfilePageController` | MySQL, Redis | `UserBaseRepositoryRealIntegrationTest` 仅仓储级 | 需要 HTTP 链路测试 |
| 内容发布与媒体 | `ContentController`, `FileController`, `PostSummaryGenerateConsumer`, `ContentScheduleConsumer`, `ContentScheduleDLQConsumer` | MySQL, Cassandra, MinIO, RabbitMQ, Redis | `PostContentKvRealIntegrationTest`, `MediaStorageRealIntegrationTest` | 缺发布/草稿/定时/摘要/删除/回滚整链路 |
| 评论 | `InteractionController#comment`, `CommentController`, `CommentCreatedConsumer`, `CommentLikeChangedConsumer`, `RootReplyCountChangedConsumer`, 清理 job | MySQL, Redis, Cassandra, RabbitMQ | `CommentCreatedConsumerRealIntegrationTest`, `CommentInteractionConsumerRealIntegrationTest`, `CommentContentKvRealIntegrationTest` | 缺 HTTP 评论/查询/删除/置顶与清理链路 |
| 点赞与互动 | `InteractionController#react`, `#reactionState`, `ReactionEventLogConsumer`, `ReactionRedisRecoveryRunner`, `CommentLikeChangedConsumer` | Redis, MySQL, RabbitMQ, hotkey | `ReactionHttpRealIntegrationTest`, `HighConcurrencyConsistencyAuditIntegrationTest` | 已有核心 HTTP 与一致性链路测试，仍需补更细的恢复/边界场景 |
| 通知 / 钱包 / 投票 | `InteractionController` 通知、打赏、投票相关接口，`InteractionNotifyConsumer`, `InteractionNotifyDlqConsumer` | MySQL, Redis, RabbitMQ | 无 | 需要 HTTP + MQ 链路测试 |
| 关系 | `RelationController`, `RelationEventListener`, `RelationEventOutboxPublishJob`, `RelationEventRetryJob` | MySQL, Redis, RabbitMQ | 无 | 需要关注/取关/拉黑及补偿链路测试 |
| Feed 推荐与分发 | `FeedController`, `FeedFanoutDispatcherConsumer`, `FeedFanoutTaskConsumer`, `FeedRecommendItemUpsertConsumer`, `FeedRecommendItemDeleteConsumer`, `FeedRecommendFeedbackConsumer`, `FeedRecommendFeedbackAConsumer` | MySQL, Redis, RabbitMQ, Gorse | `FeedFanoutRealIntegrationTest` | 缺 HTTP 时间线/推荐反馈/推荐项 upsert/delete 链路 |
| 搜索 | `SearchController`, `SearchIndexConsumer` | MySQL, Redis, Elasticsearch, RabbitMQ | `SearchIndexConsumerRealIntegrationTest` | 缺 HTTP 搜索/联想链路 |
| 风控 | `RiskController`, `RiskAdminController`, `RiskImageScanConsumer`, `RiskLlmScanConsumer`, 各 DLQ consumer | MySQL, Redis, RabbitMQ | 无 | 需要业务接口与异步扫描链路测试 |
| 社群 | `CommunityController` | MySQL, Redis | 无 | 需要 HTTP 链路测试 |
| KV / ID / 健康 | `NoteContentController`, `CommentContentController`, `IdController`, `SystemHealthController` | Cassandra, MySQL, Redis | `PostContentKvRealIntegrationTest`, `CommentContentKvRealIntegrationTest` | 缺 HTTP 暴露层链路测试 |
| 出站补偿与可靠消息 | `ContentEventOutboxRetryJob`, `ReliableMqOutboxRetryJob`, `ReliableMqReplayJob`, `UserEventOutboxRetryJob` | MySQL, RabbitMQ | `UserEventOutboxRetryJobTest` 仅单元级 | 需要真实 job 链路测试 |

## 测试组织

真实链路测试统一放在 `nexus-app/src/test/java/cn/nexus/integration/` 下，按业务域拆分：

- `auth/`
- `user/`
- `content/`
- `interaction/`
- `relation/`
- `feed/`
- `search/`
- `risk/`
- `community/`
- `kv/`
- `job/`
- `support/`

## 技术设计

### 1. 支撑基座

新增两个抽象测试支撑：

- `RealBusinessIntegrationTestSupport`
  - 复用现有中间件连接与清理能力
  - 增加通用数据造数、等待器、幂等键、统一 JSON 解析
- `RealHttpIntegrationTestSupport`
  - 使用 `@SpringBootTest(webEnvironment = RANDOM_PORT)`
  - 提供 `GET/POST/PUT/PATCH/DELETE` 包装
  - 提供匿名调用、Bearer token 调用、统一响应断言

### 2. 场景断言口径

每个场景测试只做四层断言：

1. 入口被成功触发
2. 主数据被正确持久化
3. 中间件侧副作用真实发生
4. 下游可查询结果正确

### 3. 环境约束

- Profile 固定：`test + wsl + real-it`
- Kafka listener 必须保持关闭
- RabbitMQ 队列、Redis key、ES 文档在每个测试前后清理
- 使用唯一 ID 和唯一内容避免并发污染

## 初始实施顺序

1. 先补共享 HTTP 集成测试基座
2. 先补高价值闭环：
   - 认证
   - 用户资料
   - 内容发布
   - 评论互动
3. 再补异步闭环：
   - 关系
   - Feed
   - 搜索
   - 风控
4. 最后补边角场景：
   - 社群
   - KV / ID / 健康
   - 各类补偿 job / DLQ

## 验收标准

1. 每个业务域至少有一组真实链路测试文件。
2. 场景矩阵中每一行都能定位到至少一个真实链路测试。
3. 所有新增测试都可在 WSL Docker 服务在线时重复运行。
4. 最终清单中不包含 Kafka 链路测试。
