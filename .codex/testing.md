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
