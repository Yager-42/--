# 质量审查报告（执行者：Codex / 日期：2026-01-12）

## 结论

综合评分：92 / 100（通过）

## 覆盖检查清单

- 需求对齐：已基于《社交接口.md》与《DDD-ARCHITECTURE-SPECIFICATION.md》给出分发 + Feed 的可落地方案，并保持现有 API 契约不变。
- 数据结构优先：明确 MySQL 只做内容真值，Timeline 用 Redis 存索引，符合《社交领域数据库.md》建议。
- 特殊情况控制：将“大 V 写放大”收敛为 Phase 2 的“在线推/离线拉”，避免在 MVP 阶段引入大量 if/兼容层。
- 可执行性：方案按 Phase 1/2/3 分阶段，给出可验证的验收点；实现文档已补齐落地顺序、接口签名、MyBatis XML 片段与 MQ 配置/消费者骨架，便于新成员直接开工。

## 致命问题（需要在编码落地时立刻处理）

- Cursor 依赖 member 存在：`ZREVRANK` 分页的前提是 cursor 对应的 postId 仍在 ZSET 中；当 key 过期或裁剪后必须有明确退化策略（返回空 / 触发重建），否则会造成“翻页突然断流”的用户可见问题。

## 改进方向（不影响当前交付，但会让实现更干净）

- 明确统一的 key 命名与 TTL/容量配置位置（配置中心 / application.yml）。
- 事件去重命名：现有 `RelationEventInboxPort` 更像 outbox/去重表；Feed 侧实现时建议命名准确，避免概念污染。

## 2026-01-13（点赞链路方案文档）

综合评分：90 / 100（通过）

### 覆盖检查清单

- 需求对齐：已把“Redis 秒回 + 延迟落库 + 实时监控 + 离线分析”拆成可实现的契约（Key/表/Topic/时序/验收点）。
- 数据结构优先：按 user 侧集合做幂等、按 target 侧计数做展示、用 sync/dirty/touched 收敛窗口 flush，特殊情况被压到 Lua 原子脚本里。
- 复用优先：延迟队列明确复用本仓库 `x-delayed-message` 模式（`ContentScheduleDelayConfig/Producer`），避免自研 scheduler。

### 致命问题（实现前必须先拍板）

- `userId` 来源必须固定：没有 userId 不能判定“是否已点赞”，这会直接破坏幂等与计数正确性（用户可见 bug）。✅ 已拍板：从登录态/网关上下文注入（Header：`X-User-Id`），trigger 层提供 `UserContext.requireUserId()`。

### 改进方向（不阻塞交付，但能让实现更干净）

- 明确目标范围：`targetType` 只支持 `POST/COMMENT` 还是还包括其它实体，避免 key/schema 未来被迫迁移。
- 明确窗口参数：windowSeconds/delayBufferSeconds/阈值必须配置化，别硬编码在服务里。

## 2026-01-14（点赞链路方案文档迭代）

综合评分：91 / 100（通过）

### 覆盖检查清单

- 需求对齐：在保持“Redis 秒回 + 延迟落库 + 实时/离线分析”不变的前提下，补齐了读链路契约（单条/批量 likeCount + likedByMe）与 Redis miss 回源/回填策略。
- 数据结构优先：用 `like:win:*` 合并旧的 sync+dirty，减少 key 与 if 分支；用 `like:touch:*`（HASH）记录窗口内最终状态，flush 不再逐 user `SISMEMBER`。
- 特殊情况消除：flush 通过 `RENAMENX` 把 `like:touch:*` 原子快照到独立 key，避免 flush 期间新写入被误删（这类竞态本来就不该存在）。
- 原文对齐：补充“解法 3（三级存储）”的可落地映射 —— 只吸收 L1 Caffeine 抗热点；L3 不引入 TaiShan/TiCDC，仍以 MySQL 做最终真值。
- 实用主义：热点探测对 L1 是“必选搭档”（否则就是盲目缓存）；实现优先复用 `JD HotKey` 这类成熟组件，而不是自研探测；对主链路不是硬依赖，避免过度设计。

### 致命问题（实现前仍必须先拍板）

- `userId` 来源必须固定：没有 userId 就无法判定 likedByMe，更无法保证写链路幂等（用户可见 bug）。✅ 已拍板：从登录态/网关上下文注入（Header：`X-User-Id`），不允许客户端通过 DTO/Query 传入。

### 改进方向（不阻塞交付，但需要确认）

- Redis 版本：是否 >= 6.2（决定读侧能否直接用 `SMISMEMBER` 做批量 likedByMe）。
- 接口归属：`state/batchState` 是单独的 Interaction API，还是由 Feed/Content 聚合接口内联返回（影响调用方与缓存命中方式，但不影响底层数据结构）。

## 2026-01-14（Feed 10.5.1 fanout 大任务切片落地）

综合评分：90 / 100（通过）

### 覆盖检查清单

- 需求对齐：按 `.codex/distribution-feed-implementation.md` 的 10.5.1 落地 dispatcher+worker，避免单条发布事件做完整 fanout。
- 数据结构优先：切片粒度只用 `offset/limit`，不引入额外表；粉丝总数用 `user_follower` 反向表 `COUNT` 作为真值来源。
- 特殊情况控制：失败重试粒度收敛为 `FeedFanoutTask(offset,limit)`；重复消费依赖 Redis ZSET member 幂等，不新增去重表。
- 零破坏性：不改既有 HTTP 契约与读侧逻辑；发布侧仍只发 `PostPublishedEvent`，内部链路升级为拆片并行。

### 致命问题（要记住，但本次不扩范围）

- DLQ/重试/监控：10.5.1 只解决“拆片与重试粒度”，具体 DLQ/重试策略应统一落到 10.6.4，别在 consumer 里各写一套。

### 改进方向（不阻塞交付）

- Redis pipeline：worker 内 `inboxExists` 与 `ZADD` 可对一个 batch pipeline，减少 1:1 往返（10.6.*）。
- 触发器薄度：dispatcher 目前直接依赖仓储接口（而非领域服务），是可接受的折中，但后续若要统一编排可考虑下沉为 domain 的“拆片计算器”（返回任务列表给 trigger 发送）。
