# 测试记录（执行者：Codex / 日期：2026-01-13）

本次交付落地了 Feed Phase 2（在线推 / 离线拉）代码改动；按用户要求，未执行本地编译与自动化测试。

建议后续补齐并执行（需要 MySQL + Redis + RabbitMQ）：
- Timeline 分页稳定性测试（同时间戳多条写入，翻页不重复/不漏）
- fanout 幂等性测试（重复消费同一 Post_Published 事件）
- 负反馈过滤测试（SADD/SREM 后读侧可见性变化）
- Phase 2 在线推/离线拉：inbox key 过期后不再接收 fanout；回归首页触发 rebuild 并返回近况

## 2026-01-13

本次交付为“点赞链路实现方案文档”，未修改业务代码，未执行自动化测试。

建议在开始实现点赞链路时补齐并执行：
- Reaction 幂等测试：同一用户重复 ADD/REMOVE，不应重复计数
- 并发测试：同一 target 热点并发下，Redis count 不为负且与 DB 最终一致
- flush 竞态测试：flush 过程中发生新点赞，不应出现“最后一次写丢失”

## 2026-01-14

本次交付为“点赞链路实现方案文档迭代”（补齐读链路 + 收敛 Redis 数据结构），未修改业务代码，未执行自动化测试。

建议在实现前/实现中补齐并执行：

- 读侧批量测试：batchState 对 20~50 个 targets 批量返回 likeCount + likedByMe，延迟可控且不产生 N 次 DB 查询
- Redis miss 回源测试：`like:count:*` miss 时从 `like_counts` 批量回源并回填；用户 set miss 时从 `likes` 对本批次 targets 回源并回填正例
- （可选）L1 本地缓存测试：如果引入 Caffeine，验证短 TTL 下不会把“自己刚点赞”的读请求读回旧值（写请求返回仍以 Redis Lua 的 `currentCount` 为准，并更新本机 L1）
- （可选）热点探测测试（`JD HotKey`）：hotkey 服务可用时，达到阈值的 key 被标记为 hot 并启用 L1；未达阈值不走 L1（避免无意义占用内存）；服务不可用时应自动退化为“不开 L1/或本地简易探测”
- count clamp 测试：极端并发下 count 不出现负数（Lua 里 clamp >=0）
- touch 快照竞态测试：flush 期间产生新点赞，`RENAMENX` 快照不会误删新写入；下一轮 flush 能把新写入落库

### Feed 10.5.1 fanout 大任务切片（已落地）

本次交付落地了关注流的“fanout 任务切片”：`PostPublishedEvent` 由 dispatcher 拆分为多个 `FeedFanoutTask(offset,limit)` 并行消费，worker 调用 domain `fanoutSlice` 执行单片写扩散。

按用户要求，未执行 Maven 编译/测试，仅做静态自检：
- 代码入口唯一性：确认 `feed.post.published.queue` 只由 `FeedFanoutDispatcherConsumer` 监听
- consumer 链路完整：确认 `feed.fanout.task.queue` 由 `FeedFanoutTaskConsumer` 监听
- 文档同步：`.codex/distribution-feed-implementation*.md` 已将 10.5.1 标记为已落地并给出落点文件清单

### Feed 10.5.2 ~ 10.5.7（已落地）

本次交付落地了关注流“生产级演进”的 10.5.2 ~ 10.5.7：follow 在线补偿、Outbox+大V隔离、铁粉推、聚合池、Max_ID（内部）分页与读时懒清理。

按用户要求，未执行 Maven 编译/测试，仅做静态自检：
- 写侧链路：确认 `FeedFanoutDispatcherConsumer` 永远写 Outbox，且大 V 分支不投递全量 fanout task（只推铁粉 + 可选入池）
- 读侧链路：确认 `FeedService.timeline` 不再依赖 `ZREVRANK`，改为 `pageInboxEntries(WITHSCORES)` 并合并 Outbox/Pool
- 接口契约：`/api/v1/feed/*` 路由与 DTO 字段未变；timeline 的 `nextCursor` 仍返回字符串形式的 `postId`
- 懒清理：确认 `IFeedTimelineRepository.removeFromInbox` / `IFeedOutboxRepository.removeFromOutbox` / `IFeedBigVPoolRepository.removeFromPool` 已提供并在 timeline 读侧触发

建议后续人工验收（需要 MySQL + Redis + RabbitMQ）：
- 10.5.2：A 关注 B（status=ACTIVE），若 A 在线（inbox key 存在）则 A timeline 立刻出现 B 最近内容
- 10.5.3/10.5.6：将 `feed.bigv.followerThreshold` 临时调小（便于造数），验证“大 V 发布不推全量 fanout”但 timeline 仍能通过 Outbox 合并读到
- 10.5.5：开启 `feed.bigv.pool.enabled=true` 并让关注数超过 `feed.bigv.pool.triggerFollowings`，验证读侧走聚合池分桶读取
- 10.5.7：将某些 `content_post.status` 改为非 2，验证 timeline 读侧不返回该内容，且后续 Redis 索引不会反复 miss（索引被懒清理）

## 2026-01-14（负反馈类型语义修复）

本次交付修复了负反馈“类型”误用 `content_post.media_type` 的问题：类型应指业务类目/主题（postType），而不是媒体形态（纯文/图文/视频）。

按用户要求，未执行 Maven 编译/测试，仅做静态自检：
- 代码一致性：全仓库搜索确认不存在 `listContentTypes/addContentType/removeContentType/feed:neg:type` 残留引用
- 行为一致性：负反馈仍保留 postId 维度的精确隐藏；类型维度切换为 postType（待发布链路补齐 postType 后再启用）
- 文档一致性：同步更新 `.codex/distribution-feed-implementation*.md` 的 key/流程图/实现说明，避免文档继续误导实现

## 2026-01-15（postTypes 落库 + 负反馈点选类型校验/撤销）

本次交付补齐了 postTypes 的真值落地与负反馈“点选类型”链路：postTypes 由用户发布时提交（最多 5 个）并落库到 `content_post_type`；负反馈写入时后端校验 type 是否属于该帖 postTypes，撤销时通过 Redis HASH 反查当时点选的 type 并做正确清理。

按用户要求，未执行 Maven 编译/测试，仅做静态自检：
- 代码一致性：全仓库搜索确认不存在 `ContentPostEntity.postType/getPostType/setPostType` 残留引用
- 仓储一致性：`ContentRepository` 回表路径（find/list/page）均会回填 `postTypes`
- 文档一致性：`.codex/distribution-feed-implementation*.md` 与 `社交领域数据库.md` 已同步到 `content_post_type` 与 `feed:neg:postTypeByPost:{userId}`

建议后续人工验收（需要 MySQL + Redis + RabbitMQ）：
- 发布：POST `/api/v1/content/publish` 传入 `postTypes=["游戏","技术"]`，校验 `content_post_type` 插入两条映射；再次发布同 postId 传空数组 `[]` 应清空映射；不传 `postTypes`（null）不应覆盖旧映射。
- 回表：timeline/profile 回表得到 `ContentPostEntity.postTypes`，并能用于负反馈类型过滤。
- 负反馈写入：对 postId 提交负反馈并传 `type="游戏"`（且该帖 postTypes 包含），应写入 `feed:neg:{userId}` + `feed:neg:postType:{userId}` + `feed:neg:postTypeByPost:{userId}[postId]=游戏`。
- 负反馈非法输入：若 `type` 不属于该帖 postTypes，应只记录 `feed:neg:{userId}`（精确隐藏），不写入类型维度与反查映射。
- 负反馈撤销：撤销后 `feed:neg:{userId}` 移除 postId；若该 type 没有其它 post 仍点选，应从 `feed:neg:postType:{userId}` 移除该 type；并清理 `feed:neg:postTypeByPost:{userId}` 对应 field。

## 2026-01-15（Feed 10.6 可改进点落地：JSON / 批量过滤 / DLQ / pipeline）

本次交付补齐了 `.codex/distribution-feed-implementation.md` 的 10.6.1/10.6.2/10.6.4/10.6.5：MQ 消息统一 JSON、timeline postId 负反馈批量过滤、fanout DLQ 与最小指标日志、fanout 在线判定改为 Redis pipeline（批量 EXISTS）。

按用户要求，未执行 Maven 编译/测试，仅做静态自检：
- RabbitMQ：`FeedFanoutConfig` 已声明 `Jackson2JsonMessageConverter`，并为 `feed.post.published.queue`/`feed.fanout.task.queue` 配置 DLX/DLQ；consumer 失败路径已统一 `AmqpRejectAndDontRequeueException`，确保消息可进入 DLQ。
- fanout：`IFeedTimelineRepository` 已新增 `filterOnlineUsers(List<Long>)` 且 Redis 实现存在；写扩散路径（`FeedDistributionService`、`FeedFanoutDispatcherConsumer.pushToCoreFans`）已改为批量过滤在线用户后写 inbox。
- timeline：`IFeedNegativeFeedbackRepository` 已新增 `listPostIds(Long)` 且 Redis 实现存在；读侧不再逐条调用 `contains`（避免 N 次 `SISMEMBER` 往返）。

建议后续人工验收（需要 MySQL + Redis + RabbitMQ）：
- MQ：发布一条内容，观察 `PostPublishedEvent`/`FeedFanoutTask` 能以 JSON 正常投递与消费；故意让 consumer 抛异常，消息应进入对应 DLQ 队列。
- fanout：造一批 followerId，其中部分 inbox key 不存在，验证写入日志里的 `skippedOffline` 与实际一致。

## 2026-01-15（点赞业务 Step 1~3 代码落地：写 + flush + 读）

本次交付已落地《社交接口.md》的点赞相关三接口（react/state/batchState）与“Redis 秒回 + 延迟 flush 落库”链路；按用户要求，未执行 Maven 编译/测试，仅做静态自检。

静态自检要点：
- HTTP：`/api/v1/interact/reaction`、`/api/v1/interact/reaction/state`、`/api/v1/interact/reaction/batchState` 已有入口；`userId` 固定从 Header `X-User-Id` 注入（`UserContextInterceptor`）。
- 写链路：Redis Lua 原子收敛（幂等 + count + touch + win 状态机），仅在窗口首次创建时投递一次延迟 flush。
- flush：分布式锁保护；`like_counts` 写绝对值；`like:touch:*` 用 `RENAMENX` 固定快照 key 并分批 upsert `likes(status)`；finalize Lua 原子推进 win 状态机并按需重排队。
- 读链路：count MGET + likedByMe pipeline；Redis miss 时批量回源 MySQL（`like_counts`/`likes(status=1)`）并回填。

建议后续人工验收（需要 MySQL + Redis + RabbitMQ）：
- 幂等：同一用户重复 ADD/REMOVE，不应重复计数；count 不为负。
- flush 竞态：flush 期间发生新点赞，不丢“最后一次写入”（应触发下一轮 flush）。
- 批量读：batchState 对 20~50 个 targets 不产生 N+1；miss 能按批次回源并回填。
- Redis 非预期丢 key：如果 `like:count:*` 被清空/逐出，需明确“回源重建/定期对账”的运维策略（否则可能出现用户可见的计数错误）。
