# Nexus Redis 缓存落地实现方案

- 日期：2026-03-09
- 执行者：Codex
- 关联文档：`缓存/nexus-redis-cache-analysis-2026-03-09.md`
- 范围：`project/nexus`

## 一句话结论

这次不是再发明一套缓存框架，而是把 Nexus 项目里已经验证过的 Redis 用法，系统化地复用到还没覆盖到的高频读链路上。最重要的原则只有一句话：**复用现有 Repository/Port 的缓存模式，优先补齐对象读、聚合读、首屏列表读、外部结果读，不把个性化字段塞进公共缓存。**

## 先说结论：项目里已经有的“正确姿势”

从当前代码看，Nexus 已经有几种很明确的缓存实现风格，这些都应该直接沿用：

1. **对象读缓存**
   - 代表：`UserBaseRepository`、`ContentDetailQueryService`
   - 做法：`StringRedisTemplate + JSON`，命中失败再回源 DB/下游。

2. **L1 + L2 双层缓存**
   - 代表：`FeedCardRepository`、`ContentDetailQueryService`
   - 做法：进程内 `Caffeine` 扛瞬时热点，Redis 扛跨实例共享热点。

3. **Hash 计数缓存**
   - 代表：`RelationCachePort`
   - 做法：一个用户一个 Redis Hash，字段保存 `followingCount/followerCount`。

4. **ZSET 邻接表/时间序列表**
   - 代表：`RelationAdjacencyCachePort`
   - 做法：关注/粉丝关系直接存在 ZSET，用 score 做时间排序和游标翻页。

5. **空值缓存 + TTL 抖动**
   - 代表：`ContentDetailQueryService`
   - 做法：不存在的数据写短 TTL 的 NULL 哨兵；正常数据用 `baseTTL + randomJitter`。

6. **缓存失败不影响主流程**
   - 代表：当前几乎所有 Redis 接入点
   - 做法：Redis 挂了就当 miss，主流程继续回源，不让缓存成为单点。

## 统一设计原则

### 1）优先复用现有组件，不新造缓存框架

最蠢的做法，就是为了“统一缓存”再封一层自研 `CacheService`，最后把简单问题搞复杂。这里直接复用当前项目已经在用的：

- `StringRedisTemplate`
- `ObjectMapper`
- `Caffeine`（只给极热点对象加，不是所有点位都上）
- 现有 `Repository / Port / QueryService`

### 2）缓存放在读路径内部，不暴露给 Controller

缓存应该对调用方透明。调用方只关心“我要一个结果”，不关心这个结果来自 Redis、MySQL、Cassandra 还是搜索服务。

所以新增缓存的最佳位置只有三个：

- `Repository`：单对象、批量对象、布尔值
- `Port`：外部依赖结果、计数、关系邻接
- `QueryService`：聚合页、首屏页、搜索结果拼装

### 3）公共缓存与个性化字段必须拆开

下面这些字段不应该进公共缓存：

- `isFollow`
- `liked`
- `seen`
- `isRead`
- `isBlocked`

原因很简单：这些字段跟“谁在看”有关。你把它们塞进公共缓存，key 数量会膨胀，失效会变脏，命中率会变差。

正确做法：

- 公共部分单独缓存：任何人看同一个对象，结果都一样
- 个性化部分单独查或短 TTL 缓存：只覆盖最后一层

### 4）首屏优先，不缓存深分页

列表类接口最有价值的是“第一页”和“首屏 10~20 条”，因为复用率最高。深分页天然复用差，还会制造大量低命中 key。

所以策略是：

- 优先缓存第一页 / 首屏
- 关系链路优先复用现有 ZSET 游标分页
- 深分页保留 DB/索引服务原路径

### 5）统一防护策略

- **防穿透**：不存在的对象写短 TTL NULL 哨兵
- **防击穿**：热点 key 用互斥重建，或者逻辑过期 + 后台刷新
- **防雪崩**：所有 TTL 加随机抖动
- **防污染**：写路径只做删除或覆盖，不做复杂双写事务
- **防拖垮**：Redis 失败直接降级回源

## 通用实现模板

### 模板 A：单对象 cache-aside

适用：用户资料、用户状态、风控规则、提示词。

1. 先查进程内 L1（如果这个对象足够热）
2. 再查 Redis
3. 命中 NULL 哨兵则直接返回不存在/默认值
4. miss 后回源 DB/配置表/外部服务
5. 查到结果则写 Redis，没查到则写短 TTL NULL
6. 写路径更新成功后删除缓存 key

### 模板 B：聚合页拆层缓存

适用：用户主页、评论首屏、通知首页。

1. 先缓存公共聚合结果
2. 个性化字段单独查或短 TTL 缓存
3. 返回前把公共结果与个性化状态拼起来
4. 写路径只删公共 key 和少量个性化 key

### 模板 C：列表首屏缓存

适用：个人主页 feed 首页、评论首屏、点赞人列表。

1. Redis 存首屏 ID 列表或聚合后的精简响应
2. 详情字段继续复用已有对象缓存
3. 只缓存第一页，第二页开始回源
4. 点赞/删除/新增评论时删除第一页相关 key

## P0：应该先做的落地方案

### 1）用户资料读取：不要新造两份缓存，直接对齐 `social:userbase`

- 目标文件：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/user/repository/UserProfileRepository.java`
- 最佳实现：不要再造一份 `user:profile:{uid}`，而是直接复用现有 `social:userbase:{uid}` 思路。
- 原因：`UserProfileRepository.updatePatch()` 现在已经在删 `social:userbase:{uid}`，说明项目实际上已经默认这份 key 是用户基础信息缓存。
- 建议做法：
  - 扩展 `UserBaseRepository` 缓存载荷，补上 `username`
  - `UserProfileRepository.get()` 先读 Redis，再回源 `IUserBaseDao`
  - 保持写路径继续删除 `social:userbase:{uid}`
- TTL：`30m + 0~10m 抖动`
- 防护：
  - 用户不存在时写 `NULL`，TTL `60~120s`
  - 极热点用户资料可加本地 `Caffeine 1~2s`
- 解决问题：
  - 消掉用户主页、评论作者补全、内容详情作者补全的重复查库
  - 避免同一份用户基础信息出现两套缓存 key，降低失效复杂度

### 2）用户状态读取：缓存默认值，不要每次回表

- 目标文件：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/user/repository/UserStatusRepository.java`
- key：`user:status:{uid}`
- 值：直接存状态字符串，比如 `ACTIVE / BANNED / DEACTIVATED`
- TTL：`10m + 0~5m 抖动`
- 写路径：`upsertStatus()` 成功后删除 key 或直接覆盖新值
- 特殊点：`user_status` 表里没有记录时，业务语义等于 `ACTIVE`，这个默认值也要缓存
- 防护：
  - 不需要复杂 NULL 哨兵，直接把缺省结果缓存成 `ACTIVE`
  - 热点封禁用户可以短 L1，避免活动时重复打表
- 解决问题：
  - 这是标准“读多写少、小对象、语义稳定”场景，命中率会很高
  - 还能顺手挡住“缺省行不存在导致每次都 miss 回库”的伪穿透

### 3）用户主页聚合：拆成公共基底缓存 + 用户态覆盖

- 目标文件：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/user/service/UserProfilePageQueryService.java`
- 公共 key：`user:profile:page:base:{targetUid}`
- 个性 key：`relation:follow:state:{viewerUid}:{targetUid}`
- 公共载荷只包含：
  - `profile`
  - `status`
  - `followCount`
  - `followerCount`
  - `risk`
- 明确不要放进去：`isFollow`- TTL：
  - 公共基底 `60~180s + 抖动`
  - `isFollow` `30~60s`
- 失效来源：
  - 用户资料修改
  - 用户状态变更
  - 风控处罚状态变化
  - 关注关系变化（只影响个性 key）
- 推荐实现：
  - `query()` 先查公共基底缓存
  - miss 时再 fan-out 读取，并用互斥 key 防止热点击穿
  - `isFollow` 单独短 TTL 或继续直查关系表
- 解决问题：
  - 个人主页是典型 fan-out 聚合查询，缓存能直接减少多跳读放大
  - 拆层后不会因为不同 viewer 导致公共缓存爆 key

### 4）关注/粉丝列表：直接接通现有 `RelationAdjacencyCachePort`

- 目标文件：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/RelationQueryService.java`
- 最佳实现：不要再新建列表缓存 key，直接复用已经存在的 ZSET 邻接缓存。
- 现成能力已经有：
  - `pageFollowing()`
  - `pageFollowers()`
  - `rebuildFollowing()`
  - `rebuildFollowers()`
- 具体做法：
  - 列表页先从 ZSET 拿一页用户 ID
  - 再批量走 `UserBaseRepository.listByUserIds()` 补昵称头像
  - 如果 ZSET 缺失或数量落后 DB，就触发现有 rebuild 逻辑
- TTL：这里不需要额外 TTL，ZSET 作为关系读模型长期存在更合适
- 解决问题：
  - 大 V 用户关系页的分页压力会从 DB 转到 Redis
  - 这是复用现成基础设施，ROI 非常高，没有理由继续走数据库分页

### 5）登录读路径：复用已有用户缓存，而不是直接打 DAO

- 目标文件：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/auth/AuthController.java`
- 现状问题：`login()` 直接调用 `selectByUserId/selectByUsername`，绕过了 `UserBaseRepository` 里已经存在的 Redis 加速。- 推荐改法：
  - 把登录读路径收口到 `IUserBaseRepository`
  - 如果现有接口不够用，就给仓储补 `getByUserId()` / `getByUsername()`，内部复用现有 key
- 继续沿用现有 key：
  - `social:userbase:{uid}`
  - `social:userbase:uid:{username}`
- TTL：维持现有 `1h` 即可
- 解决问题：
  - 登录是典型高频入口，尤其是开发环境或脚本压测时
  - 这里不该再绕过项目已经有的缓存层

### 6）搜索建议与搜索原始命中：缓存“公共结果”，不要缓存最终个性化响应

- 目标文件：
  - `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/SearchService.java`
  - `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/SearchEnginePort.java`
- 建议拆成两层：
  - `search:suggest:{normalizedQuery}`
  - `search:raw:{normalizedQuery}:{sort}:{page}`（只建议前 1~2 页）
- 缓存内容：
  - suggest：建议词列表
  - raw：搜索引擎返回的文档 ID、分数、基础元信息
- 不要缓存：最终拼装后的完整响应，因为里面容易混入用户态字段
- TTL：
  - suggest `30~120s + 抖动`
  - raw hits `15~60s + 抖动`
- 防护：
  - 高频 query miss 时加互斥重建
  - 空结果也缓存 `15~30s`，防止热门脏词/冷词穿透
- 解决问题：
  - 搜索提示和热门 query 的前几页天然重复度高
  - 把“公共搜索命中”缓存下来，比缓存最终响应干净得多

### 7）风控活跃规则/提示词：缓存解析后的结果，不要每次读表 + 反序列化

- 目标文件：
  - `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/RiskService.java`
  - `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/RiskRuleVersionRepository.java`
  - `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/RiskPromptVersionRepository.java`
- key：
  - `risk:rule:active:{scene}` 或 `risk:rule:active`
  - `risk:prompt:active:{scene}` 或 `risk:prompt:active`
- 值：直接存“已解析好的规则对象 JSON / prompt 文本”
- TTL：`5~10m + 抖动`
- 失效：发布新版本规则/提示词后删除 active key
- 防护：
  - 这里最有价值的是缓存“解析后的结果”，不是缓存原始表记录
  - 如果读取失败，继续回源，不影响风控正确性
- 解决问题：
  - 风控判定通常在热点写路径上，少一次 DB + JSON parse 就是直接省延迟
  - 这是低写高读配置，非常适合缓存

### 8）推荐上游结果：先缓存非个性化与 item-to-item，个性化只做短抗抖

- 目标文件：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/GorseRecommendationPort.java`
- 建议优先级：
  - 第一优先：`item-to-item` 相似内容结果
  - 第二优先：公共热门/非个性化推荐结果
  - 第三优先：用户个性化推荐结果只做 `5~15s` 短缓存
- key 示例：
  - `rec:item:{itemId}`
  - `rec:popular:{scene}`
  - `rec:user:{uid}:{scene}`
- TTL：
  - item-to-item `1~5m`
  - popular `30~120s`
  - personalized `5~15s`
- 解决问题：
  - 顶住推荐服务偶发抖动
  - 降低相同请求在短时间内反复打上游的成本

## P1：第二批值得加，但优先级略低

### 1）评论首屏聚合

- 目标：`CommentQueryService`
- 做法：只缓存某个帖子评论第一页的评论 ID 列表或首屏聚合响应
- 依赖：继续复用已有 `CommentRepository` 的评论详情缓存
- 失效：新增评论、删除评论、热榜变化时删第一页 key
- 原因：评论详情已经缓存了，但“第一页怎么拼出来”还没有缓存闭环

### 2）个人主页 feed 第一页

- 目标：`FeedService` / `ContentRepository`
- 做法：只缓存用户主页 feed 第一页 postId 列表，卡片详情继续复用 `FeedCardRepository`
- 失效：发新帖子、删帖、置顶/取消置顶时删除首页 key
- 原因：主页第一页复用率高，深分页复用率差，不值得一起缓存

### 3）通知第一页

- 目标：`InteractionService` / `InteractionNotificationRepository`
- 做法：缓存通知第一页聚合结果，`isRead` 单独从未读计数或读状态补齐
- 原因：通知页也是聚合读，且第一页访问最集中

### 4）评论点赞人第一页

- 目标：`ReactionLikeService` / `ReactionRepository`
- 做法：缓存点赞用户 ID 首屏，再复用 `UserBaseRepository` 批量补齐昵称头像
- 原因：点赞人弹窗非常像“轻量关系页”，适合首屏缓存

## 明确不建议现在做的事

### 1）不要缓存所有深分页结果

这会制造大量低复用 key，内存利用率会很差，最后只是把 Redis 当垃圾堆。

### 2）不要缓存带强个性化状态的最终响应

像 `isFollow`、`liked`、`isRead` 这种字段，应该是最后覆盖，不该跟公共结果绑定在一起。

### 3）不要做复杂双写事务缓存

这个项目现在的正确风格是：**写成功后删缓存，读请求自然回填**。别把简单问题搞成分布式事务。

### 4）不要为低 QPS 管理接口加缓存

后台管理、规则编辑、低频配置页不是 QPS 瓶颈，别浪费时间。

## 推荐实施顺序
1. `UserProfileRepository.get()` 复用 `social:userbase`
2. `UserStatusRepository.getStatus()` 加小对象缓存
3. `UserProfilePageQueryService.query()` 做公共基底缓存拆层
4. `RelationQueryService` 接上 `RelationAdjacencyCachePort`
5. `AuthController.login()` 收口到已缓存仓储
6. `SearchService/SearchEnginePort` 加 suggest/raw hits 缓存
7. `RiskService` 加 active rule/prompt 缓存
8. 第二批再做评论首屏、主页 feed 首屏、通知第一页、点赞人第一页

## 验收标准

如果这些方案落地后，至少要看到下面几件事，否则说明缓存是白加的：

- 用户资料、用户状态、主页聚合接口在压测下 Redis 命中率明显上升
- 关系页和登录入口的 DB 查询次数明显下降
- 搜索 suggest 和热门 query 的上游调用次数下降
- 风控热路径的读配置/反序列化耗时下降
- Redis 故障时主流程仍可用，只是退化为回源

## 最后的判断

这份实现方案真正有价值的地方，不是“又多加了几个 Redis key”，而是**把 Nexus 现有缓存用法收敛成统一规则**：

- 单对象走 cache-aside
- 列表只做首屏
- 聚合结果拆公共层和个性层
- 计数继续用 Hash
- 关系继续用 ZSET
- 热点用 L1 + L2
- 空值防穿透，抖动防雪崩，互斥防击穿

这就是好品味：**不增加概念数量，只把已经证明有效的东西，复制到真正值得加速的地方。**

## 消除实现歧义后的最终约束

这一节优先级高于本文前面所有“建议式”表述。实现时如果前文和本节冲突，**以本节为准**。目的只有一个：不让 Codex 或开发者在落地时临时拍脑袋。

### 1）统一技术选型：不允许再做二次选择

- Redis 客户端：统一使用 `StringRedisTemplate`
- 序列化：统一使用项目现有 `ObjectMapper` 输出 JSON 字符串
- L1 本地缓存：统一使用 `Caffeine`
- 单对象缓存结构：统一使用 `String key -> JSON`
- 计数缓存结构：统一使用 `Hash`
- 有序列表/邻接关系：统一使用 `ZSET`
- 失效策略：统一使用 **cache-aside**，即“写成功后删缓存，读请求回填”
- 不允许新增通用 `CacheService`、`CacheFacade`、`RedisHelper` 之类的二次抽象

### 2）统一 key 命名：只允许这一套

- 用户基础信息：`social:userbase:{uid}`
- 用户名到 uid：`social:userbase:uid:{username}`
- 用户状态：`user:status:{uid}`
- 用户主页公共基底：`user:profile:page:base:{targetUid}`
- 用户主页关注态：`relation:follow:state:{viewerUid}:{targetUid}`
- 搜索建议：`search:suggest:{normalizedQuery}`
- 搜索原始命中：`search:raw:{normalizedQuery}:{sort}:{page}`
- 风控活跃规则：`risk:rule:active`
- 风控活跃提示词：`risk:prompt:active`
- item-to-item 推荐：`rec:item:{itemId}`
- 公共热门推荐：`rec:popular:{scene}`
- 用户短时推荐：`rec:user:{uid}:{scene}`
- 评论首屏：`comment:first:{targetType}:{targetId}`
- 个人主页 feed 首屏：`feed:profile:first:{uid}`
- 通知首屏：`notify:first:{uid}`
- 评论点赞人首屏：`comment:likers:first:{commentId}`

### 3）统一 NULL 哨兵：只允许一个值

- NULL 哨兵字符串固定为：`__NULL__`
- 不允许对不同模块使用不同空值标记
- NULL TTL 固定规则：`90s + 0~30s 抖动`
- 只有“单对象缓存”和“搜索空结果缓存”允许写 NULL 哨兵
- ZSET / Hash 不写 NULL 哨兵

### 4）统一 TTL：不再给区间，直接给固定值

实现时不要自己挑 TTL，直接按下面写：

- `social:userbase:{uid}`：`40 分钟 + 0~10 分钟抖动`
- `social:userbase:uid:{username}`：`40 分钟 + 0~10 分钟抖动`
- `user:status:{uid}`：`15 分钟 + 0~5 分钟抖动`
- `user:profile:page:base:{targetUid}`：`120 秒 + 0~30 秒抖动`
- `relation:follow:state:{viewerUid}:{targetUid}`：`45 秒 + 0~15 秒抖动`
- `search:suggest:{normalizedQuery}`：`90 秒 + 0~30 秒抖动`
- `search:raw:{normalizedQuery}:{sort}:{page}`：`30 秒 + 0~10 秒抖动`
- `risk:rule:active`：`10 分钟 + 0~2 分钟抖动`
- `risk:prompt:active`：`10 分钟 + 0~2 分钟抖动`
- `rec:item:{itemId}`：`3 分钟 + 0~60 秒抖动`
- `rec:popular:{scene}`：`60 秒 + 0~20 秒抖动`
- `rec:user:{uid}:{scene}`：`10 秒 + 0~3 秒抖动`
- `comment:first:{targetType}:{targetId}`：`30 秒 + 0~10 秒抖动`
- `feed:profile:first:{uid}`：`30 秒 + 0~10 秒抖动`
- `notify:first:{uid}`：`20 秒 + 0~5 秒抖动`
- `comment:likers:first:{commentId}`：`30 秒 + 0~10 秒抖动`

### 5）统一 L1 规则：只有这几个点允许加 Caffeine

不要见缓存就加本地 L1，这会导致更多失效复杂度。只有下面这些点可以加：

- `social:userbase:{uid}`：L1 `2 秒`
- `user:status:{uid}`：L1 `2 秒`
- `user:profile:page:base:{targetUid}`：L1 `1 秒`
- `risk:rule:active`：L1 `5 秒`
- `risk:prompt:active`：L1 `5 秒`

其余点位**不加 L1**，只走 Redis。

### 6）统一锁策略：只给会被热点击穿的点加互斥重建

互斥 key 统一命名：`lock:{业务key}`

只允许下面这些 key 做互斥重建：

- `user:profile:page:base:{targetUid}`
- `search:suggest:{normalizedQuery}`
- `search:raw:{normalizedQuery}:{sort}:{page}`
- `risk:rule:active`
- `risk:prompt:active`

锁 TTL 固定为 `3 秒`。

获取不到锁时的处理也定死：

- 第一次 miss 且拿不到锁：睡眠 `50ms` 后重读 Redis 一次
- 第二次还 miss：直接回源，但**不重复写锁**

### 7）统一查询归一化规则：搜索 key 不允许实现时各写各的

`normalizedQuery` 固定处理规则：

1. `trim()`
2. 转小写
3. 连续空白折叠为一个空格
4. 长度超过 `64` 直接截断到 `64`
5. 归一化后为空字符串则**不查缓存，直接返回空结果**

### 8）统一“首屏”定义：不允许自己决定页大小

- 评论首屏：固定第一页，`limit <= 20`
- 个人主页 feed 首屏：固定第一页，`limit <= 20`
- 通知首屏：固定第一页，`limit <= 20`
- 评论点赞人首屏：固定第一页，`limit <= 20`
- 搜索 raw hits 只缓存 `page=1` 和 `page=2`
- 第二页以后、或者 `limit > 20`，统一不缓存

### 9）统一写路径策略：只删，不做双写覆盖

下面这些场景统一采用“写成功后删缓存”策略，不允许做 DB + Redis 双写保持强一致：

- 用户资料更新
- 用户状态更新
- 关注关系新增/取消
- 评论新增/删除
- 帖子发布/删除/置顶变更
- 通知写入/已读变更
- 风控规则发布
- 风控提示词发布

唯一例外只有一个：

- `user:status:{uid}` 允许在 `upsertStatus()` 成功后直接覆盖新值，同时仍删除 L1

### 10）统一缓存载荷：每个 key 存什么，写死

- `social:userbase:{uid}`：`userId + username + nickname + avatarUrl`
- `user:status:{uid}`：纯字符串状态值
- `user:profile:page:base:{targetUid}`：`profile + status + followCount + followerCount + risk`
- `relation:follow:state:{viewerUid}:{targetUid}`：布尔值字符串 `1/0`
- `search:suggest:{normalizedQuery}`：字符串数组
- `search:raw:{normalizedQuery}:{sort}:{page}`：搜索引擎返回的 `docId + score + type + authorId` 精简数组
- `risk:rule:active`：已解析规则 JSON
- `risk:prompt:active`：最终 prompt 文本
- `rec:item:{itemId}` / `rec:popular:{scene}` / `rec:user:{uid}:{scene}`：推荐 itemId 数组
- `comment:first:{targetType}:{targetId}`：评论 ID 数组
- `feed:profile:first:{uid}`：postId 数组
- `notify:first:{uid}`：通知 ID 数组
- `comment:likers:first:{commentId}`：userId 数组

### 11）每个点位的唯一实施选择

#### A. `UserProfileRepository`

- **唯一选择**：复用 `social:userbase:{uid}`，不允许新建 `user:profile:{uid}`
- **必须修改**：`UserBaseRepository` 缓存值结构增加 `username`
- **读取顺序**：L1 -> Redis -> `IUserBaseDao.selectByUserId()`
- **空值处理**：用户不存在时写 `__NULL__`
- **写失效责任方**：`UserProfileRepository.updatePatch()`

#### B. `UserStatusRepository`

- **唯一选择**：单独使用 `user:status:{uid}`
- **读取顺序**：L1 -> Redis -> `IUserStatusDao.selectByUserId()`
- **缺省值处理**：数据库没行时，直接缓存 `ACTIVE`，不是 `NULL`
- **写后处理**：`upsertStatus()` 成功后覆盖 Redis，并清空 L1

#### C. `UserProfilePageQueryService`

- **唯一选择**：只缓存公共基底，不缓存最终完整响应
- **公共部分来源**：`userProfileRepository + userStatusRepository + relationCachePort + riskService`
- **个性部分来源**：`relationRepository.findRelation()`
- **禁止行为**：把 `isFollow` 放进 `user:profile:page:base:{targetUid}`

#### D. `RelationQueryService`

- **唯一选择**：分页关系页强制走 `RelationAdjacencyCachePort`
- **禁止行为**：保留一套新的 Redis list/page key
- **详情补全**：统一调用 `UserBaseRepository.listByUserIds()`

#### E. `AuthController`

- **唯一选择**：登录读路径收口到缓存仓储，不再直接读 `IUserBaseDao`
- **允许保留**：创建用户时的 `insert` 和冲突重试逻辑
- **禁止行为**：继续让 `selectByUsername()` 绕过缓存层

#### F. `SearchService` / `SearchEnginePort`

- **唯一选择**：只缓存 suggest 和 raw hits，不缓存最终个性化响应
- **禁止行为**：把最终返回 DTO 整体写进 Redis
- **页码规则**：只缓存 `page=1/2`

#### G. `RiskService`

- **唯一选择**：缓存“已解析结果”，不是原始表记录
- **禁止行为**：每次都 `findActive()` 后再 JSON parse

#### H. 评论/通知/feed/点赞人首屏

- **唯一选择**：缓存 ID 列表，不缓存完整大对象 DTO
- **详情补全**：继续走现有对象缓存仓储
- **禁止行为**：把完整个性化响应直接塞进 Redis

### 12）统一失效矩阵：谁改数据，谁删哪些 key

- 用户资料更新：删 `social:userbase:{uid}`、删 `user:profile:page:base:{uid}`
- 用户名变更（如果以后开放）：额外删 `social:userbase:uid:{oldUsername}` 和 `social:userbase:uid:{newUsername}`
- 用户状态更新：删/覆盖 `user:status:{uid}`、删 `user:profile:page:base:{uid}`
- 关注关系新增/取消：
  - 调整 `RelationCachePort` 计数
  - 更新或重建 `RelationAdjacencyCachePort`
  - 删 `relation:follow:state:{viewerUid}:{targetUid}`
  - 删 `user:profile:page:base:{targetUid}`
- 评论新增/删除：删 `comment:first:{targetType}:{targetId}`
- 评论点赞变更：删 `comment:likers:first:{commentId}`
- 帖子发布/删除/置顶变更：删 `feed:profile:first:{uid}`
- 通知新增/已读：删 `notify:first:{uid}`
- 风控规则发布：删 `risk:rule:active`
- 风控提示词发布：删 `risk:prompt:active`

### 13）统一异常降级：不允许模块各写各的

- Redis 读异常：记录 warn，按 miss 处理
- Redis 写异常：记录 warn，不影响主流程返回
- Redis 删异常：记录 warn，不回滚 DB 写入
- JSON 反序列化失败：当作 miss，重新回源并覆盖旧值
- 外部搜索/推荐服务异常：如果 Redis 有旧值，用旧值；没有旧值再抛原业务错误

### 14）实现时禁止再自行判断的事项

下面这些东西已经定死，实现时不要再自己做取舍：

- 不要新增第二套用户资料 key
- 不要把个性化字段写进公共缓存
- 不要缓存深分页
- 不要缓存最终完整个性化 DTO
- 不要为了“统一”新增一个总缓存抽象层
- 不要用双写事务替代删缓存
- 不要给所有点位都加 L1
- 不要把 NULL TTL 拉长到分钟级以上
- 不要给搜索 query 使用未归一化原字符串做 key

### 15）如果实现时出现本文未覆盖的新分歧

处理顺序固定如下：

1. 先看是否能复用已有 key 风格和数据结构
2. 先选更少概念、更少 key、更少失效路径的方案
3. 如果两个方案都不破坏行为，选对现有代码改动更小的那个
4. 如果仍无法判断，**中断实现并让用户选择**
