# Nexus 项目简历逐条深挖稿

## 先给面试官的 1 分钟总述

这个项目是一个偏内容社区场景的后端项目，采用 DDD 分层风格，把 HTTP 入口、领域编排、基础设施适配拆开，围绕认证鉴权、内容发布、互动计数、内容缓存、Feed 推荐、搜索索引做了完整链路。核心目标不是做一个纯 CRUD 系统，而是把内容社区最常见的几个高频问题真正落到代码里，包括高并发读写、缓存分层、异步解耦、最终一致性、索引同步和失败恢复。

如果你面试时先总述，建议按这条主线讲：

1. 先说统一鉴权和用户上下文怎么收口。
2. 再说发帖为什么要拆成草稿、上传、发布 Attempt 三段。
3. 再说点赞计数、内容详情为什么要以 Redis 和多级缓存抗高并发。
4. 最后说 Feed 推荐和 ES 搜索为什么必须异步化、索引化。

这份稿子不是“漂亮话版本”，而是按当前 `project/nexus` 仓库真实代码和测试能支撑的程度来写的。每个点都分成：

1. 简历上怎么说
2. 业务上下文
3. 实际请求链路
4. 设计动机
5. 技术原理补充
6. 高频追问
7. 谨慎表述点

---

## 1. 认证与统一鉴权

### 简历上怎么说

使用 Sa-Token + 自定义全局 MVC 拦截器统一鉴权入口，全局拦截器解析 Bearer Token，并通过 `UserContext` 将用户 ID 透传到下游业务逻辑；实现 access token + refresh token 返回与 refresh rotation，在保证安全边界的同时优化登录体验。

### 业务上下文

内容社区项目里，几乎所有接口都依赖“当前登录用户是谁”。如果每个 Controller 自己解析 Token、自己处理 userId，会出现几个问题：

1. 身份来源不统一，容易有接口漏校验。
2. 历史代码容易遗留前端直传 `userId` 或 `X-User-Id` 的绕过问题。
3. 后续接 RBAC、审计、风控时，身份链路会很分散。
4. 登录体验上，如果 access token 过期就强制重新登录，用户体验会比较差。

所以这条链路的目标其实是两件事：

1. 统一“当前请求是谁”的身份入口。
2. 用 access/refresh 双令牌减少频繁登录。

### 实际请求链路

以“用户请求一个需要登录的内容接口”为例：

1. 客户端带 `Authorization: Bearer <token>` 请求 `/api/v1/**`。
2. `WebMvcConfig` 注册的 `UserContextInterceptor` 先拦截请求。
3. 拦截器先清理一次旧的 `ThreadLocal`，避免线程复用串号。
4. 非白名单接口调用 `StpUtil.getLoginIdAsLong()` 从 Token 中解析 `userId`。
5. 解析成功后，把 `userId` 写入 `UserContext`。
6. 业务 Controller 只从 `UserContext.requireUserId()` 取身份，不接受前端自己传 `userId`。
7. 请求结束后，`afterCompletion()` 再清理一次 `UserContext`。

登录和刷新链路可以这样补充：

1. 用户登录成功后，`StpUtil.login(userId)` 发放 access token。
2. `AuthController.issueRefreshToken()` 生成 refresh token，并把 `refreshUserId` 绑定到 Sa-Token token session。
3. 当调用刷新接口时，会先解析旧 refresh token，再执行 refresh rotation，返回新 access token + 新 refresh token。

### 设计动机

1. 把认证细节前置到统一拦截器，下游业务只关心 userId，不关心 Token 解析细节。
2. 用 `ThreadLocal` 做请求级身份透传，能把 Controller、Service、下游调用的身份读取统一起来。
3. access token 尽量短，减少泄漏窗口；refresh token 负责续签，减少用户频繁重新登录。
4. 去掉 header bypass 风格的旧设计，避免前端伪造 userId。

### 技术原理补充

#### 原理说明

1. 这里用的是服务端登录态模式，不是自包含 JWT 模式。核心思路是客户端持有随机 Token，服务端维护登录态。
2. MVC 拦截器本质上是在请求进入业务 Controller 前做统一前置校验，适合做登录态解析和上下文注入。
3. `ThreadLocal` 很适合承载“当前请求用户”这类线程内短生命周期上下文，但必须在请求结束后清理。
4. refresh rotation 的本质是“旧 refresh token 用一次就失效，再换新 refresh token”，目的是缩短 refresh token 暴露窗口。

#### 原理类可能提问

1. 为什么不用每个接口自己解析 Token？
   答法：因为我要统一身份来源，保证所有接口都只信同一个入口。这样业务层只处理“有权限的 userId”，不再处理 Token 细节，也能彻底去掉前端伪造 userId 的空间。
2. ThreadLocal 为什么适合做用户上下文？为什么一定要清理？
   答法：因为一次请求在当前线程内执行时，ThreadLocal 取值很方便，调用链也不需要层层传参。但线程池会复用线程，如果不清理，上一个请求的 userId 可能泄漏到下一个请求。
3. access/refresh 双令牌为什么比单令牌体验更好？
   答法：因为 access token 可以设置得更短，风险更低；用户 access token 过期后可以通过 refresh token 换新，不用频繁重新登录。
4. Sa-Token 在这里承担了什么角色？
   答法：它主要承担登录态管理和角色校验，统一封装了 login、logout、getLoginId、role check 这些能力。

### 高频追问

#### Q1：你这个全局鉴权是 Sa-Token 自带拦截器吗？

答法：
不是直接挂的 Sa-Token 自带全局拦截器，而是自定义 `UserContextInterceptor`，内部调用 `StpUtil.getLoginIdAsLong()`。我这样讲是因为我更想强调“统一身份入口 + UserContext 透传”，而不是只说用了哪个框架。

#### Q2：为什么业务层不直接拿 Token？

答法：
因为 Token 解析应该收口在入口层。业务层真正需要的是“当前用户 ID”和“当前用户角色”，而不是知道 Bearer Token 怎么解析。这样职责更清晰，后续替换认证实现时业务层改动也更小。

#### Q3：refresh token 这块是不是完整安全闭环？

答法：
不是完全闭环。当前仓库里实现了 refresh token 返回和 refresh rotation，但刷新接口的白名单、logout 后 refresh token 全量失效、密码修改后的 refresh token 清理这几块还没有完全封死。所以我会说“实现了双令牌机制雏形和 rotation”，不会吹成完整设备级会话管理。

### 谨慎表述点

1. 不要说“用了 Sa-Token 自带全局拦截器统一鉴权”。真实代码是自定义 MVC 拦截器。
2. 不要说“双令牌安全闭环完全打通”。当前只有 refresh rotation，闭环没做满。
3. 不要说“JWT 鉴权”。这里更接近服务端维护登录态的随机 Token 模式。

---

## 2. 内容发布：草稿 + 预签名直传 + Publish Attempt

### 简历上怎么说

采用 Draft + 发布 Attempt 的分阶段内容发布流程，后端生成预签名上传会话，前端直传对象存储；发布请求返回 `attemptId` 和中间状态，事务内写入 Outbox，并在提交后异步驱动 Feed、摘要、搜索等下游链路，降低同步发布阻塞并提升可观测性。

### 业务上下文

内容社区里的发帖不是一次简单的“插一条 post 记录”：

1. 图片和视频很大，不能让应用服务做文件中转。
2. 发布后往往还会触发风控、审核、转码、摘要、搜索索引、Feed 分发等后处理。
3. 用户点击发布后，不可能让接口一直阻塞到所有下游都处理完。
4. 发布中的中间状态必须可观测，否则用户不知道自己到底是审核中、处理中，还是已经发布成功。
5. 详情可见、Feed 可见、搜索可见不是同一个时间点，必须明确区分。

### 实际请求链路

完整链路可以拆成三段：

#### 第一段：上传会话

1. 前端先调 `/api/v1/media/upload/session`。
2. `ContentService.createUploadSession()` 生成 `sessionId`。
3. 存储适配层根据当前策略路由到 MinIO 或 Aliyun。
4. 存储策略生成预签名 PUT URL 和后续读取用的 URL。
5. 前端拿 URL 直接把媒体上传到对象存储，应用服务不承接文件流。

#### 第二段：草稿保存

1. 前端调 `PUT /api/v1/content/draft` 保存草稿。
2. 服务端创建或覆盖 draft。
3. 这里得到的 `draftId` 后续直接复用成 `postId`。
4. 这样定时发布、历史版本、回滚、再次发布都围绕同一个主键推进。

#### 第三段：发布 Attempt

1. 前端调用 `POST /api/v1/content/publish`。
2. `ContentService.publish()` 先按 `postId` 加锁，校验权限和帖子状态。
3. 如果已有活跃 Attempt，优先复用；没有的话就创建新的 `ContentPublishAttemptEntity`。
4. Attempt 里会保存输入快照、哈希后的幂等 token、状态、错误上下文。
5. 服务端同步执行风控决策。
6. 如果风控返回 `BLOCK / LIMIT / CHALLENGE`，则只更新 Attempt 为拒绝态，不推进正式发布。
7. 如果风控要求审核，则先写 `content_post + content_history(PENDING_REVIEW)`，然后把 `attemptId` 返回给前端轮询。
8. 如果风控通过，则继续进入转码提交流程。
9. 当前默认实现里 `MediaTranscodePort` 基本直接返回 `ready=true`，所以多数请求会继续落正式版本、写历史版本，并把 Attempt 更新成 `PUBLISHED`。
10. 事务内还会写 `content_event_outbox`。
11. 事务提交后，afterCommit 再做缓存失效和 MQ 投递。
12. 详情查询通常在提交后就能看到最新主记录，而 Feed、摘要、搜索等下游链路依赖异步消费最终一致。

### 设计动机

1. 用预签名直传解决大文件上传，把带宽压力从应用服务移到对象存储。
2. 用 Draft 把“编辑中内容”和“正式发布内容”拆开，避免草稿频繁覆盖正式版本。
3. 用 Attempt 建模“这一次发布请求”，把“用户点击发布”和“下游全部处理完成”分开。
4. 用 Outbox 把数据库提交和 MQ 发送解耦，避免数据库未提交、消费者先消费的乱序问题。
5. 用轮询状态代替长阻塞请求，让用户知道这次发布处于哪个阶段。

### 技术原理补充

#### 原理说明

1. 预签名 URL 的核心作用是让客户端直接对对象存储发起受限上传，服务端只负责签名授权，不负责转发大文件。
2. Attempt 本质上是 MySQL 持久化状态机，不是事件总线。它承接的是“这次发布请求”的生命周期。
3. Attempt 的状态推进用了 CAS 条件更新，目的是防止旧回调把新 Attempt 覆盖掉。
4. Outbox 模式的核心思想是“先把要发的事件和业务数据写在同一事务里，提交后再异步发消息”，这样一致性更容易保证。
5. 这里的“渐进式发布”更准确是 staged submit-and-observe flow，也就是“分阶段反馈”和“状态可观测”，而不是完整可恢复的超长媒体流水线。

#### 原理类可能提问

1. 为什么不让后端做代理上传？
   答法：因为媒体文件大，后端做代理会把应用服务变成带宽瓶颈，RT 和资源占用都很差。预签名直传能让应用服务只做签名和元数据管理。
2. 为什么要单独建 Publish Attempt，不直接更新 post 表？
   答法：因为发帖不是纯同步动作，它后面有风控、审核、转码、Feed、搜索等一整串异步链路。Attempt 承接的是“这次发布请求”的状态，而 post 表只承接“当前对外可见的版本”。
3. Outbox 为什么能提升一致性？
   答法：因为它把业务数据和待发送事件放在同一事务里。只要事务提交了，事件就一定会被后续重试发出去；如果事务没提交，消息也不会被错误消费。
4. 为什么详情可见和 Feed 可见不是同一个时间点？
   答法：因为详情查询直接读主记录，而 Feed 和搜索依赖后续的异步消费和投影更新，它们天然是最终一致，不可能和主记录提交完全同一时刻生效。

### 高频追问

#### Q1：为什么草稿 ID 和 postId 要复用同一个值？

答法：
这样后续发布、定时发布、历史版本、回滚都围绕同一个主键推进，不需要额外维护“草稿 ID 到正式 postId”的映射表，状态机会更简单。

#### Q2：你说的“渐进式发布”到底是什么意思？

答法：
不是说我已经做了一个完整的超长媒体异步流水线，而是说发布请求不会阻塞等所有下游完成，而是返回 `attemptId` 和中间状态，让客户端分阶段观察。这是“分阶段反馈”，不是“所有异步流程 fully productionized”。

#### Q3：如果风控命中了会怎样？

答法：
如果是直接拦截类决策，Attempt 会进入拒绝态，不推进正式发布；如果是 `REVIEW`，则主记录会先进入 `PENDING_REVIEW`，等审核回调再决定是否真正发布。这时候我会明确告诉面试官，详情真相源可能已经更新，但 Feed 和搜索仍是异步最终一致。

### 谨慎表述点

1. 不要把 `Attempt` 说成“attempt 事件”。它是持久化状态机，真正的事件解耦是 Outbox + MQ。
2. 不要说“整个发布完全事件驱动”。风控决策和转码 ready 判断仍有同步部分。
3. 不要说“完整可恢复转码流水线已经 fully productionized”。当前默认 `MediaTranscodePort` 基本直接返回 ready。
4. 不要说“处理中完全不会影响当前可见版本”。`PENDING_REVIEW` 分支会写主记录和历史记录。

---

## 3. Redis 计数、点赞幂等与 count-redis-module

### 简历上怎么说

使用 Redis bitmap + Lua 原子脚本维护点赞在线状态与计数，保证幂等和判重；同时实现独立的 `count-redis-module` 压缩计数方案，利用紧凑计数和 RoaringBitmap 降低后续大规模计数存储压力，并为普通 Redis key 迁移做准备。

### 业务上下文

点赞和计数是内容社区最典型的高频写场景之一，它有几个特点：

1. 请求量高，且会出现重复点击、抖动重试、幂等要求高。
2. 点赞状态和点赞数必须保持一致，不能出现“状态变了但计数没变”或者反过来的情况。
3. 详情、搜索、推荐、通知都会依赖点赞数。
4. 如果把点赞当前态完全放 DB，每次写都会很重，读也不适合高并发。
5. 长期演进上，普通 Redis key 的空间效率和结构开销会成为问题。

所以这个点其实有两层：

1. 当前在线主链路，已经用 Redis bitmap + Lua 扛住点赞状态和点赞数。
2. 进一步的 Count Redis / Redis Module 演进方向，已经做到模块代码级实现，但还没全面切主链路。

### 实际请求链路

#### 点赞在线主链路

1. 用户调用 `/api/v1/interact/reaction`。
2. `InteractionService.react()` 进入 `ReactionLikeService.applyReaction()`。
3. 服务端根据目标对象和 `desiredState` 调 `ReactionCachePort.applyAtomic()`。
4. `ReactionCachePort` 会把“某个用户对某个目标的点赞状态”映射到 bitmap shard。
5. Lua 脚本在一个原子操作里同时做两件事：
   1. `SETBIT` 改点赞状态。
   2. `INCR/DECR` 改对应计数 key。
6. 如果这次请求没有真正改变状态，比如重复点赞，脚本返回 `delta=0`，不会重复加计数。
7. 只有 `delta!=0`，后续才会继续发事件日志、通知、推荐反馈等副作用消息。

#### 恢复链路

1. 互动事件会被异步落到 `interaction_reaction_event_log`。
2. `ReactionRedisRecoveryRunner` 启动时根据 checkpoint 回放事件日志。
3. 这样 Redis 在线状态丢了以后，可以从 append-only 事件日志重建。

#### count-redis-module 演进链路

1. 仓库里额外实现了一个 Rust 写的 `count-redis-module`。
2. 模块里有 `CountInt` 紧凑计数结构和 `RoaringState` 状态结构。
3. 目标是把 `POST.like`、`COMMENT.like`、`COMMENT.reply`、`USER.following`、`USER.follower` 这类计数逐步迁到 Count Redis。
4. 但当前 Java 运行时的 Count Redis 适配层、配置层、命令执行器还没完全接好，所以它现在更像“已落代码的演进方案”，不是在线主链路。

### 设计动机

1. 把点赞当前态真相源放到 Redis，减少 DB 承压。
2. 用“状态 + 计数”同脚本原子更新，避免状态和计数分离。
3. 用 append-only 事件日志承担审计和恢复角色，而不是拿 DB 做实时当前态查询。
4. 继续演进出 `count-redis-module`，是为了后续把普通 Redis key 的空间开销和字段冗余压下去。

### 技术原理补充

#### 原理说明

1. 这里的幂等不是基于请求号，而是基于“用户对目标对象的最终状态有没有变化”。也就是状态型幂等。
2. Lua 原子脚本能保证 `SETBIT` 和 `INCR/DECR` 不会分离执行，因此点赞状态和点赞数不会出现中间态不一致。
3. bitmap shard 的价值是把“某个用户是否点赞过某对象”映射成紧凑位状态，单次读取和写入都很轻。
4. append-only 事件日志适合承担恢复和审计职责，因为它保留了变更过程，不要求每次都回写最终当前态。
5. `CountInt` 的思路是为固定 schema 计数做紧凑编码，减少 field name 和结构元数据开销。
6. `RoaringBitmap` 的思路是对稀疏或块状分布的数据做更高效压缩，适合未来替代单纯 bitmap 状态表示。

#### 原理类可能提问

1. 你这里的幂等为什么不是 requestId 幂等？
   答法：因为点赞这个场景真正关心的是“某个用户对某个目标最终是不是点赞状态”。只要最终状态没变化，就应该是 noop。这里是状态型幂等，不是请求号幂等。
2. 为什么点赞状态和计数一定要放在同一个脚本里？
   答法：因为如果拆成两次 Redis 操作，任何中间失败都可能导致状态和计数不一致。同脚本原子执行能避免这类问题。
3. 为什么 Redis 适合做在线真相源，而 MySQL 适合做事件日志？
   答法：Redis 更适合承接高频读写的当前态；MySQL 更适合承担可持久、可审计、可回放的事件日志角色。二者职责不同。
4. 你为什么还要做一个 Redis Module？
   答法：因为当计数量级上来以后，普通 Redis key + field name 的结构开销会很明显。Redis Module 可以把计数结构和状态结构做得更紧凑，后续也方便统一迁移。

### 高频追问

#### Q1：你这个 Redis Module 到底有没有在线上主链路用上？

答法：
我会很诚实地说，这块我做了两层。线上主链路已经是 Redis bitmap + Lua 原子方案；与此同时，我把 `count-redis-module` 做成了独立的 Redis Module，把 `CountInt` 和 `RoaringBitmap` 抽象出来，作为后续迁移方案。模块代码和模块内 smoke test 是完成的，但 Java 主链路还没有全面切换。

#### Q2：位图失效了会不会自动按需重建？

答法：
当前不能这么讲。仓库里明确能看到的是基于 `interaction_reaction_event_log + checkpoint` 的恢复 Runner，更像启动恢复或运维修复，而不是请求时按 miss 自动重建。

#### Q3：所有用户维度计数都已经完整在线了吗？

答法：
不能这么吹。当前明确在线的是 `FOLLOWING/FOLLOWER` 等链路；`POST/LIKE_RECEIVED/FAVORITE_RECEIVED` 这类类型在枚举里有预留，但不是都已经补齐到能扛源码追问的程度。

### 谨慎表述点

1. 不要说“计数链路已经全面切到 Redis Module”。当前在线主链路还是 bitmap + Lua。
2. 不要说“位图失效时已实现按需重建”。当前更准确是 checkpoint + replay 恢复。
3. 不要说“所有用户维度计数都已完整在线”。部分只是枚举和演进预留。

---

## 4. Post 高并发读：本地缓存 + Redis + DB/KV + SingleFlight

### 简历上怎么说

设计本地缓存 + Redis + DB/KV 的多级读链路，本地缓存承接热点稳定字段，Redis 缓存基础 Post 快照，DB/KV 作为最终真相源，并通过 SingleFlight、防穿透空值缓存、TTL 抖动和热点续命降低回源风暴。

### 业务上下文

内容详情页是典型的高频读场景，它会遇到几个问题：

1. 爆款 Post 会形成超热点，单靠 Redis 也会有网络 hop 和集中访问成本。
2. 如果 Redis 失效，很多并发请求会同时打到 DB，形成回源风暴。
3. 详情字段里既有稳定字段，也有动态字段，如果全量长缓存会导致用户看到旧数据。
4. 内容正文并不只在 MySQL 主表里，还涉及 KV 正文和类型信息，回源重建不是单表查询。

### 实际请求链路

以“用户打开内容详情页”为例：

1. 请求先进入 `ContentDetailQueryService.query()`。
2. `ContentDetailQueryService` 先查本地 `StableSnapshot` 缓存，这里缓存的是稳定字段快照。
3. 如果本地稳定快照 miss，就对这个 `postId` 走一次 `SingleFlight` 收口。
4. `SingleFlight` 内部调用 `ContentRepository.findPost(postId)`。
5. `ContentRepository` 如果判断是 hot key，先查本地 `Caffeine postCache`。
6. 本地没命中，再查 Redis `interact:content:post:{postId}`。
7. 如果 Redis 命中了 JSON，就直接反序列化基础 Post 快照。
8. 如果 Redis 命中了空值哨兵，就直接返回 null，防止缓存穿透。
9. 如果 Redis miss，则进入仓储级的 `SingleFlight.execute()` 再次 double check Redis。
10. 仍 miss 后，才从 `content_post + post_type + postContentKv` 重建完整 Post。
11. 回填 Redis，并带上 `60s + jitter` 或 `30s + jitter(null)` 的 TTL。
12. 如果是热点 key，再把结果回填本地 `Caffeine`。
13. 详情返回前，并不会直接把 Redis blob 原样返回，而是并行补查作者信息和 likeCount。
14. 当 Post 被更新或发布时，会通过 `ContentCacheEvictPort` 做本地缓存驱逐、Redis 双删和广播。

### 设计动机

1. L1 本地缓存用来抗超热点，减少 Redis 网络 hop。
2. Redis 负责跨实例共享缓存，挡住绝大多数普通流量。
3. DB + KV 作为最终真相源，保证缓存错了还能重建回来。
4. 把稳定字段和动态字段拆开缓存，兼顾性能和数据新鲜度。
5. 用 SingleFlight 把同 key 并发 miss 收敛成一次真实回源，避免 Redis 失效时直接把 DB 打穿。

### 技术原理补充

#### 原理说明

1. 多级缓存的本质不是简单堆组件，而是让不同层分别承担不同职责：本地缓存扛热点，Redis 扛共享缓存，DB/KV 承担最终真相。
2. 空值缓存的目的是防止对不存在 Post 的反复回源，也就是经典的缓存穿透治理。
3. TTL 抖动的目的是避免同一批 key 在同一时间集中过期，减少缓存雪崩。
4. 热点续命的目的是让真正热的 key 保持更长寿命，进一步减少重建频率。
5. `copy` 返回的目的是避免调用方误修改缓存对象，导致本地缓存被污染。
6. StableSnapshot + 动态字段补齐的思路，本质上是在做“冷热字段拆分”。

#### 原理类可能提问

1. 为什么不直接全靠 Redis？
   答法：Redis 适合做共享缓存，但热点访问仍然要走网络 hop。本地缓存能进一步降低热点路径时延，也能减少 Redis 自身压力。
2. 为什么要把作者昵称、头像、点赞数拆出来动态补齐？
   答法：因为这些字段变化频率高，如果和正文一起长时间缓存，会让详情页展示明显滞后。拆开以后，稳定字段走缓存，动态字段走更实时的读取。
3. SingleFlight 为什么能防回源风暴？
   答法：因为同一个 key 的并发 miss 会被收敛成一次真实回源，其余请求等待同一个结果，而不是同时把 DB 打穿。
4. 为什么你说最终真相源是 DB/KV，而不是单一 MySQL？
   答法：因为正文内容并不只在 MySQL 主表里，还涉及 KV 正文和类型信息。严格讲应该说 MySQL 主表 + KV 正文共同构成最终真相源。

### 高频追问

#### Q1：你这条链路的三级缓存具体是哪三级？

答法：
如果严格按实现讲，是“本地热点缓存 + Redis 基础快照缓存 + DB/KV 真相源”。其中本地缓存又分仓储级的 hot key cache 和详情聚合级的 stable snapshot cache。

#### Q2：Redis 里是不是存了所有详情字段？

答法：
不是。Redis 里更准确地说是基础 Post JSON 快照，不是把作者资料、点赞数这种动态字段都长期塞进同一个 blob。返回详情前还会额外补作者信息和 likeCount。

#### Q3：SingleFlight 有专门的并发证明吗？

答法：
实现是明确存在的，路径也是真正接进读链路里的。但如果面试官追到验证层，我会说我更愿意把它讲成“代码实现了并发收口机制”，而不会夸成“做过完整压测闭环证明”。

### 谨慎表述点

1. 不要说“Redis 存储多变字段”。更准确是 Redis 存基础 Post 快照，动态字段是单独补齐。
2. 不要说“DB 是唯一真相源”。更准确是 MySQL 主表 + KV 正文共同是真相源。
3. 不要把 SingleFlight 讲成你已经做了完整压测证明，如果面试官追到这层，诚实说是实现已接入主链路更稳妥。

---

## 5. Feed 与推荐：Gorse + 推拉结合 + 大 V 降扇出

### 简历上怎么说

利用 Gorse 落地个性化推荐、热点推荐和 item-to-item 相邻推荐；关注流采用推拉结合，普通作者通过 MQ 分片写扩散到在线粉丝 inbox，大 V 走 outbox 读时拉取和冷启动重建，降低扇出风暴。

### 业务上下文

内容社区的 Feed 至少有两类完全不同的需求：

1. 关注流，强调“我关注的人发了什么”。
2. 推荐流，强调“系统觉得我可能喜欢什么”。

如果都用一种链路处理，会有两个问题：

1. 全 push 在大 V 场景下会出现典型的 fanout storm。
2. 全 pull 在普通作者场景下又会导致读取阶段扫描过多 followee，首屏体验会很差。

所以这里要同时解决：

1. 关注流的推拉平衡。
2. 个性化推荐、热点推荐、相邻推荐三类推荐能力。
3. 发布、删除、互动反馈和推荐系统的数据闭环。

### 实际请求链路

#### Feed 类型

当前项目里的 Feed 不是一条时间线，而是四类：

1. `FOLLOW`
2. `RECOMMEND`
3. `POPULAR`
4. `NEIGHBORS`

#### Gorse 推荐链路

1. 个性化推荐走 `recommend(userId, n)`。
2. 热点推荐走 `nonPersonalized("trending", userId, n, offset)`。
3. 相邻推荐走 `itemToItem("similar", seedPostId, n)`。
4. 当内容发布时，会把 Item 同步到 Gorse。
5. 当内容删除时，会把 Item 从 Gorse 删除。
6. 当用户发生 read、like、comment、unlike 等行为时，会把 feedback 同步给 Gorse。

#### 关注流发布链路

1. Post 发布成功后，`post.published` 事件进入 `FeedFanoutDispatcherConsumer`。
2. Dispatcher 先做三件固定动作：
   1. 写作者自己的 outbox。
   2. 写作者自己的 inbox。
   3. 写全站 `global latest`。
3. 然后根据作者分类决定 fanout 路径。

普通作者：

1. 统计粉丝数。
2. 按 batch 切片投递多个 `FeedFanoutTask`。
3. Worker 只把内容写给“已有 inbox key 的用户”。

大 V 作者：

1. 不做全量写扩散。
2. 只保留作者 outbox。
3. 同时把内容写入 big-V pool，供部分推荐场景候选使用。

#### 关注流读取链路

1. 读取 `FOLLOW` 时间线时，先做 `rebuildIfNeeded()`。
2. rebuild 会把自己和普通作者最近内容 materialize 到 inbox。
3. 对大 V 作者，则在读时直接从对应作者 outbox 拉数据。
4. 最后把 `inbox + 各个大V outbox` 做归并排序。

所以这条链路的本质可以一句话概括：

`普通作者偏 push，大 V 偏 pull。`

### 设计动机

1. 关注流和推荐流分链路，避免一套 inbox/outbox 逻辑硬套所有场景。
2. 普通作者粉丝量有限，push 写扩散更快；大 V 粉丝量大，pull 更能避免写爆炸。
3. fanout 用 MQ 分片，失败时只重试某个 slice，不需要整批重跑。
4. 在线 inbox 用户优先 materialize，减少无效写。
5. Gorse 负责推荐算法侧，业务服务负责推荐结果承接和补充业务规则。

### 技术原理补充

#### 原理说明

1. push 的优势是读快，因为内容提前写到了用户 inbox；缺点是写放大。
2. pull 的优势是写轻，因为发布时只写作者 outbox；缺点是读时要做更多聚合和排序。
3. 推拉结合的本质是按作者类型做流量分层，把写扩散成本留给普通作者，把大 V 的 fanout 风险挪到读时分摊。
4. MQ 分片 fanout 的本质是把“大 fanout 任务”拆成多个小任务，提高重试粒度和可恢复性。
5. Gorse 本质上是外部推荐引擎，这里项目里真正做的是 Item 同步、Feedback 同步和推荐结果的业务接入，而不是自己手写一套协同过滤算法。

#### 原理类可能提问

1. 为什么不全 push？
   答法：因为大 V 发帖会引发典型的 fanout storm。如果几十万粉丝都在发布瞬间写 inbox，发布链路会非常重。
2. 为什么不全 pull？
   答法：因为普通作者场景下，全 pull 会让每次读取关注流都去扫大量 followee，读放大太重，首屏性能差。
3. 为什么推荐流和关注流不能复用一条链路？
   答法：因为它们的来源、排序、分页语义都不同。关注流是关系驱动，推荐流是算法驱动，底层结构和读写侧重点不一样。
4. Gorse 在这里到底做了什么？
   答法：它负责个性化推荐、热点推荐、item-to-item 推荐的召回能力；业务系统负责 Item 和 Feedback 的同步，以及最终结果的承接和补充业务约束。

### 高频追问

#### Q1：你简历里写“推拉结合”，具体是哪边推、哪边拉？

答法：
普通作者发布时偏 push，走 MQ 分片写扩散到已有 inbox 的活跃用户；大 V 作者发布时不做全量 push，而是在读关注流时从作者 outbox 拉取，所以是按作者类型做推拉结合。

#### Q2：你写“常驻粉丝优先推送”准确吗？

答法：
我不会这么说。更准确的表述是“已有 inbox key 的在线活跃用户优先写扩散”，离线用户不强行写 inbox，后续通过 rebuild 或 pull 补回来。

#### Q3：big-V pool 是关注流的主读路径吗？

答法：
不是。`FOLLOW` 时间线的主要读路径是用户 inbox 加各 big-V 作者 outbox 的归并。big-V pool 更像推荐流的候选来源之一，而不是关注流主读路径。

### 谨慎表述点

1. 不要说“常驻粉丝优先推送”。更准确是在线 inbox 用户优先写扩散。
2. 不要说“big-V pool 是关注流主路径”。真实主路径是 inbox + big-V outbox merge。
3. 不要把 Gorse 讲成你自己实现的推荐算法系统。你做的是业务接入和链路设计。

---

## 6. Elasticsearch 搜索：search_after + function_score + completion suggester

### 简历上怎么说

基于 Elasticsearch 构建内容搜索和搜索联想功能，使用 `function_score` 融合文本相关性与 like_count 等业务信号优化排序，并通过 `search_after` 游标分页降低深分页成本；利用 `completion suggester` 实现标题前缀联想，索引同步采用回填 + CDC + 异步 upsert/soft delete 链路。

### 业务上下文

内容社区搜索不是单纯“查 MySQL like”能解决的问题，因为它至少有这些要求：

1. 需要全文检索标题和正文。
2. 需要把文本相关性和互动热度一起考虑。
3. 需要稳定的滚动分页，而不是越翻越慢的深分页。
4. 需要联想建议来承接输入框交互。
5. 索引数据不会天然和主库同一时刻一致，必须设计同步链路。

### 实际请求链路

#### 搜索请求链路

1. API 层接收 `q / size / tags / after`。
2. `SearchService` 对参数做归一化处理。
3. `SearchEnginePort.search()` 构造 ES 查询。
4. 查询里会带上全文匹配、状态过滤、可选 tag 过滤和 `function_score`。
5. ES 返回命中文档和 sort 值。
6. 领域层再补一层更实时的 likeCount 和当前用户 liked 状态。
7. 最终返回 `items + nextAfter + hasMore`。

#### 排序和分页链路

当前项目里明确做了这些事：

1. 文本匹配字段是 `title^3` 和 `body`。
2. filter 里限制 `status=published`，并支持 tag 过滤。
3. `function_score` 叠加业务信号：
   1. `like_count` 走 `field_value_factor + log1p`。
   2. `view_count` 走 `field_value_factor + log1p`。
4. sort 顺序是：
   1. `_score desc`
   2. `publish_time desc`
   3. `like_count desc`
   4. `view_count desc`
   5. `content_id desc`
5. 分页使用 `search_after`，请求 `size = limit + 1`。
6. 多出来的一条用来判断 `hasMore`。
7. 最后一条可见记录的 sort 数组会编码成 `nextAfter` 返回给前端。

#### 搜索联想链路

1. `SearchIndexInitializer` 创建 `title_suggest` 字段，类型是 `completion`。
2. 文档装配时把 `titleSuggest=title` 写进索引。
3. `SearchEnginePort.suggest()` 通过 completion suggester 查前缀联想。

#### 索引同步链路

项目里主要有三条同步路径：

1. 历史数据回填：`SearchIndexBackfillRunner`
2. 内容 CDC 同步：
   1. Canal raw
   2. `SearchIndexCdcRawPublisher`
   3. `PostChangedCdcEvent`
   4. `SearchIndexCdcConsumer`
   5. `SearchIndexUpsertService`
   6. `SearchDocumentAssembler`
   7. `SearchEnginePort.upsert/softDelete`
3. 补充同步：
   1. 用户昵称变化补同步
   2. 点赞数快照补同步

### 设计动机

1. 用 ES 承接全文检索、复杂排序和联想，避免把 MySQL 当搜索引擎硬用。
2. 用 `function_score` 把文本相关性和业务热度融合起来，提升结果排序质量。
3. 用 `search_after` 代替深分页 `from + size`，降低越翻越深时的成本。
4. 用 `completion suggester` 做前缀联想，提升搜索输入交互。
5. 用回填 + CDC + 补同步三条链路，保证索引不是一次性导入后就不管。

### 技术原理补充

#### 原理说明

1. ES 适合做全文检索，是因为它天然支持倒排索引、多字段匹配和复杂排序；MySQL 更适合作主数据真相源。
2. `function_score` 的作用不是替代文本相关性，而是在默认文本相关性基础上叠加业务信号。
3. `search_after` 的核心是基于上一页最后一条记录的 sort 值继续往后查，比深分页 `from + size` 更适合滚动浏览。
4. `completion suggester` 更适合做前缀联想，而不是复杂语义推荐。
5. 索引同步天然是最终一致，业务系统通常要接受“主库已更新、索引稍后更新”的事实。

#### 原理类可能提问

1. 你这里是不是自己调了 BM25 权重？
   答法：我不会这么讲。BM25 是 ES 默认文本相关性的一部分，当前项目里没有显式改 similarity 配置。我真正做的是在默认文本相关性之上，用 `function_score` 融合业务信号。
2. 为什么 `search_after` 比 `from + size` 更适合深分页？
   答法：因为 `from + size` 越翻越深，ES 需要跳过前面越来越多的结果，成本会持续上升；`search_after` 是基于上一页最后一个 sort 值往后续查，更适合连续翻页。
3. 为什么搜索结果还要再补 likeCount？
   答法：因为 ES 索引是异步同步的，索引里的快照可能略旧，所以返回前会再叠一层更实时的互动数据，提升展示实时性。
4. completion suggester 和全文检索有什么区别？
   答法：全文检索更适合查结果列表；completion suggester 更适合做前缀联想，场景和数据结构都不一样。

### 高频追问

#### Q1：你这里能说“深分页稳定性保证”吗？

答法：
我会收着讲。我会说“通过 `search_after` 降低深分页成本、提升结果连续性”，但不会说“强一致稳定快照”，因为仓库里没看到 PIT。

#### Q2：你能说“我做了 BM25 调优”吗？

答法：
不能。我做的是 `function_score + multi_match` 这一层的业务排序融合，不是显式修改 ES 的 BM25 similarity 参数。

#### Q3：view_count 这个信号能强讲吗？

答法：
我会说它在索引排序字段里存在，但不会把它讲成当前最核心、最实时的在线排序信号，因为仓库里更能明确看到的是 like_count 这条补充和聚合链路。

### 谨慎表述点

1. 不要说“显式做了 BM25 权重调优”。更准确是默认文本相关性 + `function_score`。
2. 不要说“深分页稳定快照完全保证”。当前没看到 PIT。
3. 不要把 completion suggester 讲成语义搜索，它更接近标题前缀联想。

---

## 7. 最建议你背下来的两段“诚实版回答”

### 1. 面试官追问“Redis Module 到底上没上线”

你可以直接这样答：

`这块我做了两层。线上主链路已经用 Redis bitmap + Lua 原子脚本扛住了点赞幂等和计数一致性；与此同时，我又把 count-redis-module 做成了独立的 Redis Module，把 CountInt 和 RoaringBitmap 抽象出来，用于后续压缩存储和迁移。也就是说，工程演进方向我已经写到了代码级，但当前仓库的 Java 主链路还没有完全切换过去。`

### 2. 面试官追问“渐进式发布到底是什么意思”

你可以直接这样答：

`更准确地说，我做的是 Draft + Publish Attempt 的分阶段发布反馈，而不是一个已经 fully productionized 的超长媒体流水线。用户点击发布后会拿到 attemptId，可以看到审核中、处理中、已发布这些中间状态；事务内通过 Outbox 保护主记录和下游事件一致性，详情、Feed、搜索则是分阶段最终一致可见。`

---

## 8. 最后给你的面试策略

### 1. 开场不要直接背简历句子

建议你这样开口：

`这个项目我主要做了 6 条链路：统一鉴权、内容发布、Redis 计数与点赞、内容高并发读缓存、Feed 推荐、ES 搜索。每一条我都不是只做接口，而是把同步主链路、异步副作用、数据一致性和失败恢复一起考虑了。`

### 2. 回答层次按四层展开

第一层：业务目标

1. 为什么要做这件事

第二层：核心链路

1. 请求怎么流转

第三层：关键取舍

1. 为什么不用另一种方案

第四层：边界和遗留问题

1. 哪些是当前版本还没完全闭环的

### 3. 一句话原则

`宁可把“已设计/已实现但未完全接主链路”的内容主动讲清楚，也不要硬吹成已经完整上线。`
