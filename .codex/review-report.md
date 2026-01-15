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

## 2026-01-14（Feed 10.5.2 ~ 10.5.7 落地）

综合评分：89 / 100（通过）

### 覆盖检查清单

- 需求对齐：按 `.codex/distribution-feed-implementation.md` 落地 10.5.2~10.5.7（follow 补偿 / Outbox+大V / 铁粉推 / 聚合池 / Max_ID / 读时懒清理），保持 `/api/v1/feed/*` 契约与 DTO 字段不变。
- 数据结构优先：Outbox/Pool/Inbox 都使用 Redis ZSET（member=postId，score=publishTimeMs）；铁粉集合使用 Redis SET（`feed:corefans:{authorId}`）。
- 特殊情况收敛：timeline 不再依赖 `ZREVRANK` 的“cursor member 必须存在”前提；内部统一用 Max_ID（time+postId）做稳定翻页与多源合并。
- 零破坏性：对外 timeline `nextCursor` 仍返回 `postId` 字符串（兼容旧行为）；服务端通过 `ContentRepository.findPost(postId)` 反查时间戳。
- 读时修复：不做写扩散回撤；读侧发现 `status!=2` 的缺失 postId 时进行索引懒清理，避免反复 miss。

### 致命问题（需要记住，但本次不扩范围）

- 大 V 判定的读侧成本：当用户关注对象很多且聚合池关闭时，可能触发多次 `countFollowerIds`（MySQL COUNT）；当前通过 `feed.bigv.pull.maxBigvFollowings` 与 `feed.bigv.pool.triggerFollowings` 做兜底，但后续可考虑缓存/离线标记进一步降本。
- 聚合池模式的回表规模：`feed.bigv.pool.fetchFactor` 过大可能导致一次请求回表过多 postIds；建议保持默认并在压测后再调大。

### 改进方向（不阻塞交付）

- 把 `FeedService.timeline` 的“候选合并/游标解析/懒清理”抽成更小的组件，避免 Service 文件过长。
- 负反馈过滤可升级为批量化（`SMEMBERS` 或 pipeline），减少 N 次 `SISMEMBER`（对应 10.6.2）。

## 2026-01-14（负反馈类型语义修复）

综合评分：88 / 100（通过）

### 覆盖检查清单

- 需求对齐：负反馈“类型”从媒体形态（`content_post.media_type`）纠正为业务类目/主题（postType），避免用户点一次负反馈就把所有视频/图文屏蔽掉的错误体验。
- 数据结构优先：类型维度存储改为 Redis SET `feed:neg:postType:{userId}`，成员为字符串 postType；postId 维度保持 `feed:neg:{userId}` 不变。
- 零破坏性：HTTP 路由与 DTO 字段不变；Phase 1 行为保持“只屏蔽这一条”（postId 维度）稳定可用。

### 致命问题（必须正视）

- postType 真值缺失：当前 `content_post` 还没有 `post_type` 真值落地，导致类型维度过滤在 Phase 1 实际不会生效；要启用类型维度，必须在发布链路补齐 postType（LLM 生成并落库/回填到实体）。

## 2026-01-15（postTypes 落库 + 负反馈点选类型）

综合评分：90 / 100（通过）

### 覆盖检查清单

- 需求对齐：负反馈类型语义落地为“业务类目/主题”，并明确来源是用户发布时提交的 postTypes（最多 5 个），而不是 `content_post.media_type`（媒体形态）。
- 数据结构优先：用新表 `content_post_type(post_id, type)` 建模“一帖多类型”；读侧回表统一由 `ContentRepository` 批量回填 `ContentPostEntity.postTypes`，避免在 service 层到处散落 join/二次查询。
- 特殊情况收敛：发布链路对 postTypes 做归一化（trim/去空/去重/最多 5 个）；旧客户端不传 `postTypes`（null）时不覆盖既有映射，避免意外破坏用户可见行为。
- 零破坏性：HTTP 契约与既有 DTO 字段保持不变（仅新增可选字段 `postTypes`）；负反馈撤销接口不带 type 参数的问题，用 Redis HASH `feed:neg:postTypeByPost:{userId}` 存 postId->type 解决，不要求客户端补字段。
- 可撤销性：撤销负反馈会清理 postId 维度与 postId->type 映射，并在“无其它 post 仍点选该 type”时才移除类型级过滤集合，避免误删导致过滤失效。

### 致命问题（要记住，但本次不扩范围）

- DB 变更未自动执行：新增表 `content_post_type` 需要实际迁移（本次仅同步文档与 DAO/Mapper）。线上落地前必须确保建表已完成。

### 改进方向（不阻塞交付）

- 类型字典：当前 type 是自由文本字符串；未来若要统一枚举/多语言/推荐，可在不破坏现有表结构的前提下引入“类型字典表 + type_id”并逐步迁移。

## 2026-01-15（Feed 10.6 可改进点落地：JSON / 批量过滤 / DLQ / pipeline）

综合评分：91 / 100（通过）

### 覆盖检查清单

- 需求对齐：按 `.codex/distribution-feed-implementation.md` 的 10.6.1/10.6.2/10.6.4/10.6.5 落地，且不影响 Phase 2 正确性；按用户要求跳过 10.6.3（热点探测 + L1 缓存）。
- 数据结构优先：把“在线判定”收敛为一次 pipeline `EXISTS feed:inbox:{id}`；把“负反馈 postId 过滤”收敛为一次 `SMEMBERS feed:neg:{userId}` + 内存过滤，消除 N 次 RTT。
- 特殊情况消除：fanout 写扩散不再对每个 follower 单独 `inboxExists`；读侧不再逐条 `SISMEMBER`；两处最容易被 RTT 打爆的特殊情况被消掉了。
- 可运维性：Feed fanout 队列补齐 DLX/DLQ；consumer 失败时 `AmqpRejectAndDontRequeueException` 直接入 DLQ；最小指标用日志输出 wrote/skippedOffline/costMs，方便排查写扩散异常与性能抖动。

### 致命问题（必须正视）

- MQ 序列化切换的迁移风险：如果线上/本地队列里已有“旧格式（Java 序列化）”消息，切换到 JSON 后消费会失败并进入 DLQ。上线时要么先清空/隔离队列，要么做一次性迁移策略（本次按“快速落地”未做兼容层）。

### 改进方向（不阻塞交付）

- 指标进一步结构化：如果后续接入 Micrometer/Actuator，再把 wrote/skippedOffline/costMs 从日志升级为 counter/timer（本次按最小实现只打日志）。 
