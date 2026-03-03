# Nexus xiaohashu Playbook Adapter

目标：在不下线既有 `/api/v1/**` 路径、沿用 Nexus 现有命名（post/content/reaction 等）的前提下，按 `xiaohashu/xiaohashu_project_implementation_playbook.md` 的 0~8 节复现能力。

约束：RocketMQ/Cassandra/RedisBloom/Nacos/Zookeeper 均不可用；MQ 统一用 RabbitMQ 替代。

---

## 0. 组件映射

- RocketMQ -> RabbitMQ
  - Topic -> Exchange
  - Tag -> RoutingKey
  - consumerGroup -> Queue
  - 广播 -> Fanout exchange
  - 延迟消息 -> x-delayed-message（优先）或 TTL+DLX（降级）
- Cassandra KV -> MySQL KV 表
- RedisBloom -> Redis bitmap 自实现 Bloom
- Nacos/ZK -> 不依赖（本项目用配置轮询/Redis 配置实现热切换）

---

## 1. 鉴权 + userId 透传 + UserContext(ThreadLocal)

关键语义：请求进入应用后，必须能拿到当前 userId；优先从 `Authorization: Bearer <token>` 解析，其次兼容历史 Header。

- Header 约定
  - `Authorization: Bearer <token>`（优先）
  - `userId: <number>`（兼容 playbook）
  - `X-User-Id: <number>`（兼容历史实现）
- 关键类
  - `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/support/UserContext.java`
  - `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/support/UserContextInterceptor.java`
  - `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/auth/AuthController.java`
- 关键配置
  - `sa-token.token-name=Authorization`
  - `sa-token.token-prefix=Bearer`

---

## 2. Leaf 分布式 ID（segment + snowflake）

- HTTP
  - `GET /id/segment/get/{bizTag}`
  - `GET /id/snowflake/get/{key}`（key 目前仅用于占位，ID 全局唯一由 snowflake 保证）
- 关键类
  - `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/id/LeafSegmentIdService.java`
  - `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/dao/id/ILeafAllocDao.java`
  - `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/id/IdController.java`
- 表结构
  - `leaf_alloc`：`project/nexus/docs/migrations/20260303_01_add_leaf_alloc.sql`

---

## 3. OSS 策略（minio/aliyun）+ storage.type 热切换（不依赖 Nacos）

- 切换方式
  - 默认：读取配置 `storage.type`
  - 热切换：Redis `GET storage:type`（值为 `minio`/`aliyun`）覆盖默认值
- 关键类
  - 路由器：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/MediaStoragePort.java`
  - MinIO：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/storage/MinioMediaStorageStrategy.java`
  - Aliyun（占位）：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/storage/AliyunOssMediaStorageStrategy.java`
- 关键配置
  - `storage.type=minio`
  - `storage.redis-key=storage:type`
  - `storage.refresh-ms=2000`

- HTTP（补齐 playbook 的 multipart 上传入口）
  - `POST /file/upload`（`multipart/form-data`，字段名 `file`）
  - 实现：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/file/FileController.java`
  - 语义：服务端直传，返回 `{ url }`（当前 url 为预签名 GET URL；生产建议走 `/api/v1/media/upload/session` 的直传方案）

---

## 4. KV（post_content/comment_content）：MySQL 适配 Cassandra

- HTTP（与 playbook 一致）
  - `POST /kv/note/content/add` `{ uuid, content }`
  - `POST /kv/note/content/find` `{ uuid }` -> `data=content`（找不到返回 `0404`）
  - `POST /kv/note/content/delete` `{ uuid }`
  - `POST /kv/comment/content/batchAdd` `{ comments: [ {noteId, yearMonth, contentId, content}, ... ] }`
  - `POST /kv/comment/content/batchFind` `{ noteId, commentContentKeys: [ {yearMonth, contentId}, ... ] }`
  - `POST /kv/comment/content/delete` `{ noteId, yearMonth, contentId }`
- 关键类
  - HTTP：
    - `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/kv/NoteContentController.java`
    - `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/kv/CommentContentController.java`
  - Port：
    - `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/adapter/port/IPostContentKvPort.java`
    - `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/adapter/port/ICommentContentKvPort.java`
  - Impl：
    - `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/kv/PostContentKvPort.java`
    - `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/kv/CommentContentKvPort.java`
- 表结构
  - `post_content` / `comment_content`：`project/nexus/docs/migrations/20260303_02_add_kv_tables.sql`

- 评论“真的迁移”到 KV（破坏性改表，符合你的要求）
  - interaction_comment：删除 LONGTEXT `content`，新增 `content_id` 指向 KV
  - migration：`project/nexus/docs/migrations/20260303_04_comment_use_kv.sql`
  - 读写落点：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/CommentRepository.java`

---

## 5. Post 发布/详情/更新/删除（正文 KV + 元数据 MySQL）

- 规则
  - MySQL `content_post`：只存 `content_uuid`（正文键）+ 元数据
  - KV `post_content`：存正文大字段
- HTTP
  - 发布/更新：`POST /api/v1/content/publish`
  - 删除：`DELETE /api/v1/content/{postId}`（会同步删 KV 正文）
  - 详情：`GET /api/v1/content/{postId}`（二级缓存 + CompletableFuture 并发聚合）
- 关键类
  - 写链路：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/ContentService.java`
  - MySQL 仓储：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/ContentRepository.java`
  - 详情聚合：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/support/ContentDetailQueryService.java`
- Cache
  - Redis：`interact:content:detail:<postId>`（String=JSON；NULL="NULL"）
  - Local：Caffeine（`ContentDetailQueryService` 内存缓存）
- 表结构
  - KV：`post_content`：`project/nexus/docs/migrations/20260303_02_add_kv_tables.sql`
  - Meta：`content_post.content_uuid`：`project/nexus/docs/migrations/20260303_03_content_post_use_kv.sql`

---

## 6~8

## 6. 缓存一致性：延时双删 Redis + 广播删本地缓存（RabbitMQ 适配）

- Redis key
  - `interact:content:post:<postId>`（内容元数据/读侧缓存）
  - `interact:content:detail:<postId>`（详情聚合缓存）
- MQ
  - Exchange（fanout）：`social.cache.evict`
  - Queue：AnonymousQueue（每实例独占，达到广播效果）
- 关键类
  - Port：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/adapter/port/IContentCacheEvictPort.java`
  - 实现：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/cache/ContentCacheEvictPort.java`
  - MQ 拓扑：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/config/ContentCacheEvictConfig.java`
  - 消费者：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/ContentCacheEvictConsumer.java`
- 语义
  - 写成功 afterCommit：立即删 Redis + 广播删各节点本地缓存 + 1s 后再删一次 Redis（延时双删）

---

后续步骤会在本文件持续补齐：关键类/配置/Redis Key/MQ 拓扑/表结构/最小验收方法。

---

## 7. 点赞高并发（Bloom + ZSet + MQ 异步落库 + RateLimiter）

> 适配说明：RedisBloom 不可用，因此 Bloom 用 bitmap + sha1 取 3 个 bit 位实现。

- Redis Key
  - `bloom:post:likes:<userId>`（bitmap Bloom）
  - `user:post:likes:<userId>`（ZSet，最近 100 条）
  - `pending:post:like:<userId>:<postId>` / `pending:post:unlike:<userId>:<postId>`（去抖 + 并发保护）
  - `interact:reaction:cnt:{POST:<postId>:LIKE}`（帖子点赞数）
  - `interact:reaction:cnt:{USER:<creatorId>:LIKE}`（作者收到的点赞数）

- MQ（RabbitMQ 适配 RocketMQ LikeUnlikeTopic）
  - Exchange：`LikeUnlikeTopic`（direct）
  - RoutingKey：`Like` / `Unlike`
  - Queue（consumerGroup=A）：`like.unlike.persist.queue`（关系落库）
  - Queue（consumerGroup=B）：`like.unlike.count.queue`（计数对齐触发）
  - DLX：`LikeUnlikeTopic.dlx`（persist/count 各自 DLQ）

- 关键类
  - 写入口（post）：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/ReactionLikeService.java`
  - 缓存端口：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/PostLikeCachePort.java`
  - 事件发布：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/LikeUnlikeEventPort.java`
  - MQ 拓扑：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/config/LikeUnlikeMqConfig.java`
  - consumer 批量容器：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/config/LikeUnlikeListenerContainerConfig.java`
  - 关系落库消费者：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/LikeUnlikePersistConsumer.java`

- DB
  - `interaction_reaction`：事实表（用户是否点赞某 post）
  - 幂等：主键 `(target_type,target_id,reaction_type,user_id)` + upsert

- RateLimiter 配置
  - `mq-consumer.like-unlike.rate-limit`（每批 acquire 1 次）

---

## 8. 计数聚合/削峰写入（1000/1s 批量）→ Redis → 异步落库

> 适配说明：本项目“在线写”已直接更新 Redis cntKey；计数链路主要负责把 Redis 快照对齐到 DB（绝对值 upsert），保证重启后可回填。

- MQ
  - 聚合消费者：`like.unlike.count.queue`（批量拿到 LikeUnlikePostEvent）
  - Count->DB Exchange：`CountPostLike2DBTopic`（direct）
  - Queue（consumerGroup=C）：`count.post.like.db.queue`
  - DLX：`CountPostLike2DBTopic.dlx`

- 关键类
  - 聚合/发快照：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/PostLikeCountAggregateConsumer.java`
  - DB 对齐消费者：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/PostLikeCount2DbConsumer.java`
  - 快照 DTO：`project/nexus/nexus-types/src/main/java/cn/nexus/types/event/interaction/ReactionCountSnapshotEvent.java`
  - MQ 拓扑：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/config/CountPostLikeMqConfig.java`

- DB
  - `interaction_reaction_count`
    - `target_type=POST`：帖子点赞数
    - `target_type=USER`：作者收到的点赞数

- RateLimiter 配置
  - `mq-consumer.count-like2db.rate-limit`（每批 acquire 1 次）
