# 搜索与发现服务实现方案（实现级文档，可直接照着写代码，执行者：Codex / 日期：2026-01-30）

> 目标：在 **不改变现有接口契约**（`/api/v1/search/*` 与现有 DTO 字段）的前提下，把“搜索与发现服务域”从占位实现落地为可上线的真实链路：**ES 倒排检索 + MQ 驱动索引更新 + Redis 热搜/联想/历史**。  
> 本文档的详细程度目标：**任何一个新来的 Codex agent 不需要猜测，就能按本文实现出一致的 Search & Discovery 服务域**。

---

## 0. 你需要先知道的三件事（不懂也没关系，照做就行）

### 0.1 项目结构（Maven 多模块 + DDD 分层）

项目根目录：`project/nexus`

- `project/nexus/nexus-trigger`：HTTP/MQ 入口（Controller、@RabbitListener）
- `project/nexus/nexus-api`：接口与 DTO（契约层）
- `project/nexus/nexus-domain`：领域服务（业务编排），只依赖 `nexus-types`
- `project/nexus/nexus-infrastructure`：MyBatis/Redis/RabbitMQ/ES 等技术实现（domain 的接口实现）
- `project/nexus/nexus-types`：全局枚举/事件/异常

分层规范参考：`.codex/DDD-ARCHITECTURE-SPECIFICATION.md`

### 0.2 搜索接口已存在，但实现是“占位”

现有入口与占位实现（**必须保持 HTTP 契约不变**）：

- HTTP：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/SearchController.java`
- API：`project/nexus/nexus-api/src/main/java/cn/nexus/api/social/ISearchApi.java`
- DTO：`project/nexus/nexus-api/src/main/java/cn/nexus/api/social/search/dto/*`
- Domain：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/ISearchService.java`
- Domain 实现：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/SearchService.java`（当前返回 demo 数据）

你的工作是：**不改 Controller 路由、不改 DTO 字段**，把 SearchService 换成真实实现，并补齐索引链路与缓存链路。

### 0.3 复用现成事件，不要自己造轮子

内容发布/删除事件已经存在并通过 RabbitMQ 投递（这是搜索索引的写入入口）：

- 生产者：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/ContentDispatchPort.java`
  - exchange：`social.feed`
  - routingKey：`post.published` / `post.deleted`
- 事件：`project/nexus/nexus-types/src/main/java/cn/nexus/types/event/PostPublishedEvent.java`
- 事件：`project/nexus/nexus-types/src/main/java/cn/nexus/types/event/PostDeletedEvent.java`
- 交换机/JSON converter 已在 `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/config/FeedFanoutConfig.java` 声明（**搜索侧复用**）

为保证索引不陈旧（你已选择：3B），本次额外新增并约定两类事件（仍投递到 exchange=`social.feed`）：  

- routingKey：`post.updated` → `PostUpdatedEvent {postId, operatorId, tsMs}`（本项目负责生产）  
- routingKey：`user.nickname_changed` → `UserNicknameChangedEvent {userId, tsMs}`（由写 `user_base` 的服务生产；本项目只消费）  

事件类文件路径（写死，避免 JSON type 映射炸裂）：  

- `project/nexus/nexus-types/src/main/java/cn/nexus/types/event/PostUpdatedEvent.java`
- `project/nexus/nexus-types/src/main/java/cn/nexus/types/event/UserNicknameChangedEvent.java`

> 重要：内容发布事件是 after-commit 触发的，避免“消费者读到未提交数据导致索引误删/误写”的线上鬼故事。不要改回“事务内直接发事件”。

### 0.4 本次只做 POST 搜索（不做 USER/GROUP/圈子搜索）

本次实现范围写死（避免你“想做很大”最后什么都做不成）：

- 搜索只返回帖子（POST），不支持搜用户/圈子
- `type` 参数仍然保留（HTTP 契约不变），但行为变更为：
  - 空/`ALL`/`POST`：按 `POST` 处理
  - `USER`/`GROUP`/其它：视为 `UNSUPPORTED`，返回空列表（不抛错）

这样做的好处：Search 域仍只依赖一个 exchange（`social.feed`）；但为避免索引陈旧，本次约定消费 `post.published/post.updated/post.deleted/user.nickname_changed` 四类事件（不引入 USER/GROUP 上游依赖）。

---

## 1. 需求确认（保持接口不变）

### 1.1 对齐《社交接口.md》的搜索域契约（以代码为准）

代码侧既有路由：

- `GET /api/v1/search/general` → `ISearchService.search(...)`
- `GET /api/v1/search/suggest` → `ISearchService.suggest(...)`
- `GET /api/v1/search/trending` → `ISearchService.trending(...)`
- `DELETE /api/v1/search/history` → `ISearchService.clearHistory(userId)`

对应 DTO 字段（**不允许新增/删除字段**）：

- `SearchGeneralRequestDTO`: `keyword`, `type`, `sort`, `filters`
- `SearchGeneralResponseDTO`: `items[] {id,type,title,summary}`, `facets`
- `SearchSuggestRequestDTO`: `keyword`
- `SearchSuggestResponseDTO`: `suggestions[]`
- `SearchTrendingRequestDTO`: `category`
- `SearchTrendingResponseDTO`: `keywords[]`
- `SearchHistoryDeleteRequestDTO`: `userId`（注意：SearchController 当前忽略它，使用 `X-User-Id`）

用户可见行为（Never break userspace）：

- 路由不变：`/api/v1/search/*`
- DTO 字段不变
- Response 包装不变（`Response.success(code, info, data)`）

### 1.2 本次上线只支持 POST（`type` 参数仍保留）

本次上线口径（写死，避免实现者猜）：

- `type` 参数仍然接受（HTTP 契约不变），但只做 POST 搜索：
  - 空/`ALL`/`POST`：按 `POST` 处理
  - `USER`/`GROUP`/其它：视为 `UNSUPPORTED`，返回空列表（items=[]），不抛错；facets 返回固定 shape：`meta + 空对象`：`{"meta":{"reason":"UNSUPPORTED_TYPE","normalizedType":"UNSUPPORTED","offset":0,"limit":20},"mediaType":{},"postTypes":{}}`

字段匹配口径（写死）：

- `POST`：按帖子文本（contentText）+ 帖子类型（postTypes）+ 作者昵称（authorNickname）+ 帖子 ID（postId）召回

---

## 2. 总体架构（只保留必要概念）

一句话：**MySQL 是真相源，ES 是检索读库，MQ 驱动 ES 更新，Redis 管“用户体验类状态”（历史/热搜/联想）。**

```mermaid
flowchart TD
  subgraph WriteSide[写侧：索引更新]
    P[内容发布/删除] -->|after-commit| MQ1[(RabbitMQ: social.feed)]
    MQ1 --> C1[SearchIndexConsumer]
    C1 --> DB[(MySQL: content_post/content_post_type + user_base)]
    C1 --> ES[(Elasticsearch)]
  end

  subgraph ReadSide[读侧：查询]
    U[用户请求 /api/v1/search/*] --> SC[SearchController]
    SC --> DS[SearchService(domain)]
    DS --> ES
    DS --> R[(Redis: history/trending)]
  end
```

关键原则（为了一致性而写死）：

- 搜索结果只来自 ES（不回 MySQL 做兜底查询）
- 索引增量更新优先由 MQ 触发；首次上线/索引损坏用 backfill runner 全量回灌（不做 CDC）
- Redis 写入是 best-effort（失败不影响搜索结果返回），但必须打印关键日志字段以便定位

---

## 3. 核心数据结构（必须按此实现）

### 3.1 Elasticsearch：索引命名与版本策略

索引（Index）与别名（Alias）口径：

- 写索引名：`social_search_v1`
- 读别名：`social_search`（始终指向当前版本索引）

升级（v1 -> v2）口径（写死）：

1. 创建新索引 `social_search_v2`
2. 执行 reindex（或离线全量重建）
3. 原子切换 alias：`social_search` 从 v1 切到 v2
4. 保留 v1 一段时间（用于回滚）

### 3.2 Elasticsearch：文档 ID 规则（幂等基础）

- 本次只索引 POST，因此 ES `_id` 统一为：`POST:{postId}`（例：`POST:1234567890123`）

> 这样 delete/upsert 都是幂等的；MQ 重投不会造成重复数据或写放大失控。

### 3.3 Elasticsearch：mapping（可直接复制执行）

说明：

- `contentText` 用 `cjk` analyzer（内置，避免依赖第三方分词插件）
- `postTypes` 用 `keyword`（过滤/聚合）
- `createTimeMs` 存 long（毫秒），同时冗余 `createTime` date（方便 ES 衰减函数）

创建索引（示例，生产建议用 index template 管理）：

```json
PUT social_search_v1
{
  "settings": {
    "number_of_shards": 3,
    "number_of_replicas": 1,
    "refresh_interval": "1s"
  },
  "mappings": {
    "dynamic": "strict",
    "properties": {
      "entityIdStr": { "type": "keyword" },

      "createTimeMs": { "type": "long" },
      "createTime": { "type": "date" },

      "postId": { "type": "long" },
      "authorId": { "type": "long" },
      "authorNickname": {
        "type": "text",
        "analyzer": "standard",
        "fields": {
          "kw": { "type": "keyword", "ignore_above": 128 }
        }
      },
      "contentText": { "type": "text", "analyzer": "cjk" },
      "postTypes": { "type": "keyword" },
      "mediaType": { "type": "integer" }
    }
  }
}
```

建立 alias（必须做，不然后续版本升级会变成灾难）：

```json
POST /_aliases
{
  "actions": [
    { "add": { "index": "social_search_v1", "alias": "social_search" } }
  ]
}
```

### 3.4 Redis：Key 设计（历史/热搜/联想）

Redis 只存“体验数据”，不存业务真相。

#### 3.4.1 搜索历史（每用户一个 LIST）

- key：`search:history:{userId}`
- 类型：LIST（字符串成员）
- 规则：
  - 去重：新关键词写入前，先 `LREM key 0 keyword`
  - 头插：`LPUSH key keyword`
  - 截断：`LTRIM key 0 (maxSize-1)`
  - 过期：`EXPIRE key ttlSeconds`
- 固定参数（写死）：
  - `maxSize = 20`
  - `ttlDays = 90`

#### 3.4.2 热搜（按天一个 ZSET，7 天滑窗聚合）

- 日热搜 key：`search:trend:{category}:{yyyyMMdd}`（ZSET，member=keyword，score=count）
- 聚合热搜 key：`search:trend:{category}:7d:{yyyyMMdd}`（ZSET，临时聚合结果）
- `yyyyMMdd` 的时区：`Asia/Shanghai`（写死；避免运行环境时区不同导致热搜“跨天乱跳”）（你已选择：6A）
- 固定参数（写死）：
  - `windowDays = 7`
  - `dailyTtlDays = 8`（比 window 多一天，避免边界）
  - `unionTtlSeconds = 120`（聚合结果缓存 2 分钟）
  - `topKForSuggestScan = 200`（联想扫描上限）

category 口径（写死）：

- `category` 为空时按 `POST`
- 允许值：`POST` / `ALL`（`ALL` 当成 `POST`；其它值也当成 `POST`，避免客户端传参导致热搜空）

> 联想（suggest）直接复用“聚合热搜”，做前缀过滤即可，别引入第二套数据结构。

---

### 3.5 MySQL：真相源表（Search 写侧必须依赖）

Search 写侧（MQ consumer/backfill runner）需要回表组装索引文档，因此这些表必须存在：

- `content_post` / `content_post_type`：帖子真相源（已存在）
- `user_base`：用于补全 `authorNickname`（nickname），已存在（见 `project/nexus/docs/social_schema.sql`）

> 本次只做 POST 搜索；不依赖 `community_group`。

### 3.6 内容状态枚举（必须新增到 nexus-types，禁止魔法数字）

为了避免在 SearchIndexConsumer 里写一堆 `status==2`/`visibility==0` 这种垃圾魔法数字，本次必须把“内容状态/可见性/媒体类型”的权威值提炼成公共枚举（放在 `nexus-types`，供 domain/infrastructure/trigger 共用）。

这些枚举在当前仓库中已经存在（直接复用，禁止重复造）：

- `project/nexus/nexus-types/src/main/java/cn/nexus/types/enums/ContentPostStatusEnumVO.java`
  - `DRAFT=0`，`PENDING_REVIEW=1`，`PUBLISHED=2`，`REJECTED=3`，`DELETED=6`
- `project/nexus/nexus-types/src/main/java/cn/nexus/types/enums/ContentPostVisibilityEnumVO.java`
  - `PUBLIC=0`，`FRIEND=1`，`PRIVATE=2`
- `project/nexus/nexus-types/src/main/java/cn/nexus/types/enums/ContentMediaTypeEnumVO.java`
  - `TEXT=0`，`IMAGE=1`，`VIDEO=2`

如果你在某个裁剪版本里缺失它们，按上面的值与路径补齐即可（仍然禁止在业务代码里写裸数字）。

使用规则（写死）：

- 所有与 “是否进入搜索索引” 相关的判断，必须使用这些枚举的 `code`，禁止在代码里写 `2/0/1/2` 之类的裸数字

---

## 4. 索引构建与更新链路（必须按此实现）

### 4.1 事件来源（复用 social.feed；新增两类事件契约）

本次索引更新复用 exchange=`social.feed`，并约定消费以下四类事件：

- exchange：`social.feed`
- `post.published` → `PostPublishedEvent {postId, authorId, publishTimeMs}`
- `post.updated` → `PostUpdatedEvent {postId, operatorId, tsMs}`
- `post.deleted` → `PostDeletedEvent {postId, operatorId, tsMs}`
- `user.nickname_changed` → `UserNicknameChangedEvent {userId, tsMs}`

事件类文件路径（写死，避免有人把事件放到奇怪的包里导致 JSON type 映射炸裂）：

- `project/nexus/nexus-types/src/main/java/cn/nexus/types/event/PostPublishedEvent.java`
- `project/nexus/nexus-types/src/main/java/cn/nexus/types/event/PostUpdatedEvent.java`
- `project/nexus/nexus-types/src/main/java/cn/nexus/types/event/PostDeletedEvent.java`
- `project/nexus/nexus-types/src/main/java/cn/nexus/types/event/UserNicknameChangedEvent.java`

字段类型约束（写死）：时间一律用 `Long` 毫秒时间戳（tsMs/publishTimeMs），禁止使用 `Date`（跨语言/时区坑）。

### 4.2 MQ 拓扑（新增队列，但复用既有 JSON converter）

新增（写死命名，避免团队各写各的）：

- queue：`search.post.published.queue`（bind：`social.feed` + `post.published`）
- queue：`search.post.updated.queue`（bind：`social.feed` + `post.updated`）
- queue：`search.post.deleted.queue`（bind：`social.feed` + `post.deleted`）
- queue：`search.user.nickname_changed.queue`（bind：`social.feed` + `user.nickname_changed`）

死信（写死）：

- 复用 DLX：`social.feed.dlx.exchange`
- DLQ：
  - `search.post.published.dlx.queue`
  - `search.post.updated.dlx.queue`
  - `search.post.deleted.dlx.queue`
  - `search.user.nickname_changed.dlx.queue`

### 4.3 索引写入规则（这是一致性的核心）

#### 4.3.1 PostPublishedEvent / PostUpdatedEvent → upsert / delete 的判定规则

写入目标（写死）：所有 ES `upsert/delete/update-by-query` 一律针对读别名（alias）=`social_search`（由配置 `search.es.indexAlias` 决定），禁止直接写 `social_search_v1`（你已选择：11A）。

消费者拿到 `postId` 后 **必须回表**（MySQL 是真相）：

1. `post = IContentRepository.findPost(postId)`（包含 postTypes）
2. 如果 post 不存在：`ES.delete(POST:{postId})` 并返回
3. 如果 `post.status != ContentPostStatusEnumVO.PUBLISHED.getCode()`：`ES.delete(...)` 并返回
4. 如果 `post.visibility != ContentPostVisibilityEnumVO.PUBLIC.getCode()`：`ES.delete(...)` 并返回
5. 否则组装索引文档并 `ES.upsert(...)`

> 关键点：`status/visibility` 只用于“是否应该出现在搜索里”的判定，不写入 ES。这样查询侧不需要再写一堆 filter 特殊情况（好品味：让特殊情况消失）。

#### 4.3.2 PostDeletedEvent → delete

直接 `ES.delete(POST:{postId})`（幂等）。

#### 4.3.3 UserNicknameChangedEvent → 批量更新 authorNickname

目标：用户改 nickname 后，旧帖子也能立刻按新 nickname 被搜到（否则索引会陈旧）。（你已选择：12B）

消费者拿到 `userId` 后：

1. 从 MySQL `user_base` 查当前 `nickname`（找不到则用 `""`）（你已选择：12B）
2. 调用 `ISearchEnginePort.updateAuthorNickname(userId, nickname)`，对 alias=`social_search` 执行 update-by-query：`term(authorId=userId)` 并把 `authorNickname` 覆盖写为新值
3. 失败处理按 7.4 的“可重试/不可重试 + 5 次退避重试”执行

> 说明：这条链路只更新 ES 中的冗余字段，不改业务真相源。

### 4.4 索引文档组装规则（字段必须一致）

索引文档字段赋值（写死；所有 null 都必须转成“类型安全”的默认值，禁止写入 null）：

**POST 文档：**

- `entityIdStr = String.valueOf(post.postId)`（用于按 ID 精确搜索）
- `postId = post.postId`
- `authorId = post.userId`
- `authorNickname`：
  - 从 `IUserBaseRepository.listByUserIds([post.userId])` 取 `nickname`
  - 取不到则写 `""`（空字符串）
- `contentText = post.contentText`（null → `""`）
- `postTypes = post.postTypes`（null → `[]`）
- `mediaType = post.mediaType`（null → `ContentMediaTypeEnumVO.TEXT.getCode()`）
- `createTimeMs`（语义=发布时间；字段名沿用 createTimeMs）（你已选择：10A）：
  - 优先使用真相源里的“发布时间”（如果表里没有 publish_time，则把 `post.createTime` 视为发布时间）
  - 真相源取不到时：`PostPublishedEvent.publishTimeMs` 优先；否则用事件 `tsMs` 兜底（仅用于避免写入 null，不代表“更新时间”）
- `createTime = createTimeMs` 转成 date（UTC）

---

## 5. 查询链路（必须按此实现）

### 5.1 输入归一化（避免“特殊情况”）

处理顺序（写死，避免实现分叉）：

1. 先解析并校验 `filters`（5.2）。只要 `filters` 非法，一律 `0002`（ILLEGAL_PARAMETER），即使 keyword 为空或 type 不支持（你已选择：1A）
2. 归一化 `type`。若归一化结果为 `UNSUPPORTED`：直接返回空结果（items=[]），`facets` 返回固定 shape：`meta + 空对象`（见 5.5），`meta.reason="UNSUPPORTED_TYPE"`
3. 归一化 `keyword`。若归一化后为空：直接返回空结果（items=[]），`facets` 返回固定 shape：`meta + 空对象`（见 5.5），`meta.reason="EMPTY_KEYWORD"`
4. 调 ES 查询；失败一律抛 `AppException(ResponseCode.UN_ERROR)`（你已选择：1A）
5. ES 查询成功后，才 best-effort 写 Redis 的 history/trend（你已选择：4A/5A）

keyword 归一化规则（写死）：

1. `trim()` 去前后空白
2. 将连续空白压缩为单个空格
3. 英文字母统一转小写（大小写不敏感；用于历史/热搜/联想一致性）（你已选择：5A）；长度上限 64（超过截断）
4. 归一化后为空：直接返回空结果（items=[]；facets 返回固定 shape：`meta + 空对象`，见 5.5）

type 归一化规则（写死）：

- 空/空白 → `POST`
- `ALL` / `POST` → `POST`（统一转大写后再归一化）
- 其它 → `UNSUPPORTED`（返回空结果，不抛错）

sort 归一化规则（写死）：

- 空/`RELEVANT` → `RELEVANT`
- `LATEST` → `LATEST`
- 其它 → `RELEVANT`

filters 解析规则（写死）：

- `filters` 为空或空白：当作 `{}`（无过滤）
- `filters` 非空：必须是 JSON object 字符串（URL 参数需先 decode）
  - 解析失败：抛 `AppException(ResponseCode.ILLEGAL_PARAMETER)`
  - 解析成功但字段类型不匹配/字段值非法：同样抛 `AppException(ResponseCode.ILLEGAL_PARAMETER)`（你已选择：5A）

### 5.2 filters JSON Schema（分页/过滤都在这里）

`filters` 的 JSON Schema（写死；未列字段一律忽略，避免前后端联调炸裂）：

```json
{
  "offset": 0,
  "limit": 20,
  "postTypes": ["guide","review"],
  "mediaType": 0,
  "timeRange": {
    "fromMs": 0,
    "toMs": 0
  },
  "category": "AUTO",
  "includeFacets": true
}
```

字段语义与默认值（写死）：

- `offset`：默认 0；必须为整数；范围 `[0,2000]`，否则抛 `AppException(ResponseCode.ILLEGAL_PARAMETER)`（你已选择：5A）
- `limit`：默认 20；必须为整数；范围 `[1,50]`，否则抛 `AppException(ResponseCode.ILLEGAL_PARAMETER)`（你已选择：5A）
- `postTypes`：可选；必须为字符串数组；最多 5 个，否则抛 `AppException(ResponseCode.ILLEGAL_PARAMETER)`（你已选择：5A）
- `mediaType`：可选；必须为整数；只允许 `0/1/2`，否则抛 `AppException(ResponseCode.ILLEGAL_PARAMETER)`（你已选择：5A）
- `timeRange.fromMs/toMs`：可选；必须为整数毫秒且 `>=0`；`0` 表示不限制；当 `fromMs>0 && toMs>0` 时必须 `fromMs<=toMs`，否则抛 `AppException(ResponseCode.ILLEGAL_PARAMETER)`（你已选择：5A）
- `category`：用于热搜归因；必须为字符串；只允许 `AUTO/POST/ALL`，否则抛 `AppException(ResponseCode.ILLEGAL_PARAMETER)`（你已选择：5A）
- `includeFacets`：默认 true；必须为布尔值，否则抛 `AppException(ResponseCode.ILLEGAL_PARAMETER)`（你已选择：5A）

### 5.3 ES Query（BM25 + 时效性 + ID 精确匹配）

只查 alias：`social_search`

通用参数（写死）：

- `from = filters.offset`
- `size = filters.limit`
- `track_total_hits = true`

> 注意：我们不做 deep pagination。`offset` 最大 2000 是硬限制（见 5.2）；超过就是 `0002`，不要“帮用户兜底”（你已选择：5A）。

失败语义（写死）：ES 查询只要抛异常/超时/返回不可解析结果，一律抛 `AppException(ResponseCode.UN_ERROR)`（你已选择：1A）。不要用“返回空列表”掩盖线上故障。

#### 5.3.1 type=POST 的 Query（只搜帖子）

Query JSON（可直接按结构实现，`<KEYWORD>` 表示归一化后的 keyword）：

```json
{
  "function_score": {
    "query": {
      "bool": {
        "must": [
          {
            "multi_match": {
              "query": "<KEYWORD>",
              "type": "best_fields",
              "operator": "and",
              "fields": [
                "contentText^3.0",
                "postTypes^2.0",
                "authorNickname^1.0"
              ]
            }
          }
        ],
        "should": [
          { "term": { "entityIdStr": { "value": "<KEYWORD>", "boost": 30.0 } } }
        ]
      }
    },
    "functions": [
      {
        "gauss": {
          "createTime": { "origin": "now", "scale": "7d", "decay": 0.5 }
        }
      }
    ],
    "score_mode": "sum",
    "boost_mode": "sum"
  }
}
```

额外过滤（只对 POST 生效）：

- `filters.mediaType`：`term` on `mediaType`
- `filters.postTypes`：`terms` on `postTypes`
- `filters.timeRange`：`range` on `createTimeMs`（from/to 为毫秒；0 表示不限制）

高亮（用于 summary，写死）：

- highlight field：`contentText`
- `number_of_fragments=1`
- `fragment_size=80`
- `pre_tags=["<em>"]`，`post_tags=["</em>"]`
排序（写死）：

- `RELEVANT`：默认 ES `_score desc`，再 `createTimeMs desc`，再 `postId desc`（稳定翻页；你已选择：9A）
- `LATEST`：`createTimeMs desc`，再 `_score desc`，再 `postId desc`（稳定翻页；你已选择：9A）

### 5.4 输出映射（SearchGeneralResponseDTO）

由于 DTO 字段有限（id/type/title/summary），输出口径必须写死：

- 本次只索引 POST：所有 hit 都按 POST 处理；如果 `_source.postId` 为空，直接丢弃该 hit（数据坏了就暴露，别胡塞）

**POST hit → SearchItemDTO：**

- `id = String.valueOf(_source.postId)`（null → `""`）
- `type = "POST"`
- `title`：取 `_source.contentText` 第一行，去掉换行后截断 30 字符（不足原样；null → `""`）
- `summary`：
  - 若 highlight 命中 `contentText`：取第一段 highlight（去掉多段）
  - 否则取 `_source.contentText` 截断 80 字符

`facets`：JSON 字符串（见 5.5）；当 `includeFacets=false` 时，仍然返回固定 shape：`meta + mediaType/postTypes 空对象`（但不执行 ES aggregations）（你已选择：7B）。

### 5.5 facets JSON Schema（写死）

`facets` 返回 JSON 字符串，Schema（写死）：

```json
{
  "meta": {
    "reason": "OK",
    "normalizedType": "POST",
    "offset": 0,
    "limit": 20
  },
  "mediaType": { "0": 10, "1": 20, "2": 93 },
  "postTypes": { "guide": 12, "review": 3 }
}
```

实现方式（写死）：

- `meta` 必须永远返回（即使 includeFacets=false 也要返回）：
  - `reason` 允许值（写死）：`OK` / `EMPTY_KEYWORD` / `UNSUPPORTED_TYPE`（你已选择：2A；filters 非法直接 `0002`，不返回 `ILLEGAL_FILTERS`）
  - `normalizedType`：5.1 归一化后的 type（含 `UNSUPPORTED`）
  - `offset/limit`：5.2 归一化后的分页参数
- 固定 shape（写死）：无论是否执行 aggs，都必须返回 `meta + mediaType + postTypes` 三个 key；当不执行 aggs 时，`mediaType={}` 且 `postTypes={}`（你已选择：7B）
- 仅当 `filters.includeFacets != false` 时执行 ES aggregations；否则返回 `meta + 空对象`（不打 aggs）
- aggs 口径（写死）：
  - `mediaType`：terms on `mediaType`（size=3）
  - `postTypes`：terms on `postTypes`（size=50）（你已选择：8A）
- 将 `meta + aggs` 序列化为 JSON 字符串塞进 `facets`

---

## 6. 联想（suggest）与热搜（trending）

### 6.1 search/general 里必须做两件“体验写入”（best-effort）

当 `userId != null` 且 keyword 非空且 type != `UNSUPPORTED` 且 **ES 查询成功** 时：（你已选择：4A）

重要口径（写死）：写 Redis 的 `keyword` 一律使用 5.1 归一化后的 keyword（含小写化），禁止用原始输入（你已选择：5A）。

1. 写搜索历史（3.4.1）
2. 写日热搜计数：
   - `trendCategory` 取值规则（写死）：
     - 一律用 `POST`（本次只支持 POST；`ALL` 已在 5.1 归一化为 POST）
   - 对 `search:trend:{trendCategory}:{yyyyMMdd}` 执行 `ZINCRBY 1 keyword` 并设置 TTL（若首次创建 key）
   - `yyyyMMdd` 一律按 `Asia/Shanghai` 计算（你已选择：6A）

失败策略（写死）：

- Redis 写失败：吞掉异常，但必须打印 warn 日志（含 userId/keyword/category）
- 不允许因为缓存失败导致搜索接口整体失败（否则线上体验会非常脆）

### 6.2 热搜接口：GET /api/v1/search/trending

输入：

- `category`：空/`ALL`/`POST` → `POST`；其它 → `POST`（避免客户端传参导致热搜空）

输出：

- `keywords`：取“近 7 天聚合热搜” top 10

聚合算法（写死）：

1. 计算最近 7 天的日 key 列表（含今天）：`search:trend:{category}:{yyyyMMdd}` * 7
2. 执行 `ZUNIONSTORE search:trend:{category}:7d:{today} 7 <keys...> AGGREGATE SUM`
3. 对聚合 key 设置 `EXPIRE 120`
4. `ZREVRANGE <unionKey> 0 9`

口径（写死）：`yyyyMMdd`/`today` 一律按 `Asia/Shanghai` 计算（你已选择：6A）。

失败策略（写死）：任何 Redis 读/聚合失败，一律返回 `keywords=[]`（`code=="0000"`），并打印 warn 日志（含 category/err）。不抛 `UN_ERROR`（你已选择：3A）。

### 6.3 联想接口：GET /api/v1/search/suggest

输入：

- `keyword`：用户输入前缀；可为空

输出：

- `suggestions`：最多 10 个

规则（写死）：

1. 若 keyword 为空：直接返回 trending(POST) 的 top10
2. 若 keyword 非空：
   - 取 trending(POST) 的 top200（`topKForSuggestScan`）
   - 逐个按“前缀匹配”过滤（区分大小写：不区分；按 5.1 归一化后的 keyword 进行）
   - 取前 10 个返回

失败策略（写死）：任何 Redis 读失败，一律返回 `suggestions=[]`（`code=="0000"`），并打印 warn 日志（含 keyword/err）。不抛 `UN_ERROR`（你已选择：3A）。

> 这套实现很朴素，但它足够稳定、可上线，而且不会引入 ES completion 的维护成本。想升级到 completion/term suggester，见 9.2。

---

## 7. 实现清单（按文件落地，不允许自由发挥）

> 这一章的目标：让执行者不需要“思考架构”，只需要按清单把东西写出来。

### 7.1 Domain：新增端口/仓储接口（只定义，不写实现）

新增/修改文件（nexus-domain）：（写死路径与方法签名，避免实现者“猜接口”）

1. `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/adapter/port/ISearchEnginePort.java`
   - 负责：ES query/upsert/delete（仅覆盖 POST）
   - 方法（写死）：
     - `SearchEngineResultVO search(SearchEngineQueryVO query)`
     - `void upsert(SearchDocumentVO doc)`
     - `void delete(String docId)`（docId 格式见 3.2）
     - `long updateAuthorNickname(Long authorId, String authorNickname)`（用于 4.3.3；返回影响文档数，0 也算成功）

2. `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/model/valobj/SearchEngineQueryVO.java`
   - 字段（写死）：
     - `String keyword`（已归一化）
     - `String sort`（RELEVANT/LATEST）
     - `int offset`
     - `int limit`
     - `boolean includeFacets`
     - `Integer mediaType`（可空；仅对 POST 生效）
     - `java.util.List<String> postTypes`（可空；仅对 POST 生效）
     - `Long timeFromMs` / `Long timeToMs`（可空；仅对 POST 生效）

3. `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/model/valobj/SearchEngineResultVO.java`
   - 字段（写死）：
     - `long tookMs`
     - `long totalHits`
     - `java.util.List<SearchHitVO> hits`
     - `java.util.Map<String, java.util.Map<String, Long>> aggs`（示例：`mediaType/postTypes`）
   - `SearchHitVO` 字段（写死）：
     - `String highlightContentText`（可空；仅 POST）
     - `SearchDocumentVO source`

4. `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/model/valobj/SearchDocumentVO.java`
   - 字段（写死；所有字段允许为空，但 `entityIdStr` 必须非空）：  
     - `String entityIdStr`
     - `Long createTimeMs`
     - POST：`Long postId`, `Long authorId`, `String authorNickname`, `String contentText`, `java.util.List<String> postTypes`, `Integer mediaType`

5. `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/adapter/repository/ISearchHistoryRepository.java`
   - `void record(Long userId, String keyword)`
   - `void clear(Long userId)`

6. `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/adapter/repository/ISearchTrendingRepository.java`
   - `void incr(String category, String keyword)`
   - `java.util.List<String> top(String category, int limit)`
   - `java.util.List<String> topAndFilterPrefix(String category, String prefix, int scanTopK, int limit)`

### 7.2 Domain：改造 SearchService（占位实现替换）

改造文件：

- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/ISearchService.java`
  - 将 `search/suggest` 增加 userId 参数（SearchController 读取 `UserContext` 后传参；HTTP 契约与 DTO 不变）：  
    `SearchResultVO search(Long userId, String keyword, String type, String sort, String filters)`  
    `SearchSuggestVO suggest(Long userId, String keyword)`

- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/SearchService.java`
  - 注入新端口/仓储：`ISearchEnginePort`、`ISearchHistoryRepository`、`ISearchTrendingRepository`
  - 按第 5/6 章规则实现 search/suggest/trending/clearHistory

### 7.3 Trigger：SearchController 只做“传参”，不做业务

改造文件：

- `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/SearchController.java`

改造规则（写死）：

- `search()`：读取 `Long userId = UserContext.getUserId()` 并传给 domain
- `suggest()`：同上
- `trending()`：不需要 userId
- `clearHistory()`：保持现状（requireUserId）

### 7.4 Trigger：新增 MQ config + consumer（索引更新）

新增文件：

1. `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/config/SearchIndexMqConfig.java`
   - 声明：
     - `search.post.published.queue` bind `social.feed` + `post.published`
     - `search.post.updated.queue` bind `social.feed` + `post.updated`
     - `search.post.deleted.queue` bind `social.feed` + `post.deleted`
     - `search.user.nickname_changed.queue` bind `social.feed` + `user.nickname_changed`
   - DLQ 策略：
     - 复用 `FeedFanoutConfig.DLX_EXCHANGE`（`social.feed.dlx.exchange`）
     - 为 search 新增 DLQ queue（命名写死）：
       - `search.post.published.dlx.queue`
       - `search.post.updated.dlx.queue`
       - `search.post.deleted.dlx.queue`
       - `search.user.nickname_changed.dlx.queue`
     - DLX routingKey（写死，模式与 FeedFanoutConfig 一致）：
       - `search.post.published.dlx`
       - `search.post.updated.dlx`
       - `search.post.deleted.dlx`
       - `search.user.nickname_changed.dlx`
   - Listener 并发/预取（你已选择：6C）：必须做成配置化，默认值写死为 `concurrency=2`、`prefetch=20`（见 7.5.2）
   - 失败重试（你已选择：2B）：必须配置 RabbitListener 的重试退避，默认写死为：`maxAttempts=5` + 指数退避（`initialInterval=1000ms, multiplier=3.0, maxInterval=60000ms`）。重试耗尽后必须 reject（进入 DLQ）。
   - 依赖（写死）：`project/nexus/nexus-trigger/pom.xml` 增加 `org.springframework.retry:spring-retry`（否则重试拦截器不可用）。

2. `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/SearchIndexConsumer.java`
   - `@RabbitListener(queues = ...)` 监听四条队列（见 4.2）
   - 处理规则按 4.3（回表 → upsert/delete）
   - 失败策略（写死，避免“自由发挥”）：
     - 不可重试：直接抛 `AmqpRejectAndDontRequeueException` 进入 DLQ（例如 JSON 反序列化失败、缺字段、参数非法）
     - 可重试：直接抛原始异常，交给 7.4 的“5 次退避重试”；重试耗尽后 reject 进入 DLQ（例如 ES/DB 暂时不可用/超时）

### 7.5 Infrastructure：Elasticsearch Client 与实现

#### 7.5.1 Maven 依赖（写死版本）

在 `project/nexus/nexus-infrastructure/pom.xml` 增加依赖（固定使用 ES 8 官方 Java Client）：

- groupId：`co.elastic.clients`
- artifactId：`elasticsearch-java`
- version：`8.12.2`

> 版本升级策略：只允许“同大版本小升级”；升级时必须同时验证 mapping 与 query DSL（见第 8 章验收）。

#### 7.5.2 配置键（application-dev.yml 必须给示例）

在 `project/nexus/nexus-app/src/main/resources/application-dev.yml` 增加（写死键名）：

```yaml
search:
  es:
    endpoints: http://127.0.0.1:9200
    indexAlias: social_search
  mq:
    concurrency: 2
    prefetch: 20
    retry:
      maxAttempts: 5
      initialIntervalMs: 1000
      multiplier: 3.0
      maxIntervalMs: 60000
  result:
    defaultLimit: 20
    maxLimit: 50
  backfill:
    enabled: false
    pageSize: 500
    checkpoint:
      enabled: true
      redisKey: search:backfill:cursor
  history:
    maxSize: 20
    ttlDays: 90
  trending:
    windowDays: 7
    dailyTtlDays: 8
    unionTtlSeconds: 120
    topKForSuggestScan: 200
```

#### 7.5.3 基础设施实现文件清单

新增文件（nexus-infrastructure）建议路径（写死）：

- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/config/SearchElasticsearchConfig.java`
  - 创建 `ElasticsearchClient` Bean（从配置读取 endpoints）

- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/SearchEnginePort.java`
  - 实现 `ISearchEnginePort`

- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/SearchHistoryRepository.java`
  - 实现 `ISearchHistoryRepository`（Redis LIST）

- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/SearchTrendingRepository.java`
  - 实现 `ISearchTrendingRepository`（Redis ZSET + ZUNIONSTORE）

---

### 7.6 App：新增 SearchIndexBackfillRunner（首次上线/重建索引用）

你必须考虑一个现实：**服务上线前已经存在大量已发布内容**。如果不做回灌，你的搜索结果会“看起来像坏了”（只能搜到上线后新发布的内容）。

参照现有 `FeedRecommendItemBackfillRunner` 的模式（一次性 runner，默认不执行），新增：

参考实现（写死，避免你去全局搜）：

- `project/nexus/nexus-app/src/main/java/cn/nexus/config/FeedRecommendItemBackfillRunner.java`：游标分页 + best-effort + 可重跑
- 分页 SQL 权威：`project/nexus/nexus-infrastructure/src/main/resources/mapper/social/ContentPostMapper.xml` 的 `selectPublishedPage`（`ORDER BY create_time DESC, post_id DESC` + cursor 条件）

- `project/nexus/nexus-app/src/main/java/cn/nexus/config/SearchIndexBackfillRunner.java`
  - `search.backfill.enabled=false` 默认关闭
  - `search.backfill.pageSize=500`（写死默认）
  - 断点续跑（你已选择：4C，写死实现）：使用 Redis 保存 checkpoint
    - key：`search:backfill:cursor`（内容为 `{lastCreateTimeMs}:{lastPostId}`；为空表示从最新开始）
    - 启动时：若 checkpoint 存在，则自动从该 cursor 继续跑；若不存在，从头开始
    - 跑完一页后：立刻覆盖写 checkpoint（允许进程崩溃后继续）
    - 全量结束后：删除 checkpoint key（表示回灌完成）
    - 如果你想“从头重跑”，手动删除 `search:backfill:cursor` 再启动
  - 显式开启后只回灌 POST 索引（本次范围写死：不做 USER/GROUP）
  - POST 回灌（写死分页协议）：  
    - `IContentPostDao.selectPublishedPage(cursorTime,cursorPostId,limit)`（cursor 协议同 7.6 现有实现：`{lastCreateTimeMs}:{lastPostId}`）
    - 注意：分页 SQL 只保证 `status=PUBLISHED`，不保证 `visibility=PUBLIC`；因此每条 post 仍必须按 4.3.1（status/visibility）决定 upsert 或 delete（用 3.6 的枚举）
    - 批量加载 postTypes（`IContentPostTypeDao.selectByPostIds`）与作者昵称（`IUserBaseDao.selectByUserIds`）
    - 逐条组装 `SearchDocumentVO(entityIdStr=postId, ...)` 并调用 `ISearchEnginePort.upsert(doc)`
  - best-effort：单条失败只 warn，不阻断回灌；runner 可重跑（幂等 docId）

> 这不是“业务逻辑”，是上线必需的运维能力；把它做成可控的一次性 runner 是最小复杂度方案。

### 7.7 可观测性（上线必须有，不然你等着挨打）

日志格式（你已选择：7A，写死）：单行 `key=value`（空格分隔），第一个字段必须是 `event=...`；`keyword` 必须用双引号包起来（避免包含空格导致 grep 解析错位）。

口径（写死）：日志里的 `keyword` 一律打印 5.1 归一化后的 keyword（你已选择：5A）。

必须打印的关键日志（写死字段，方便 grep/告警）：

- `search.general`（INFO）：`userId, keyword, type, sort, offset, limit, esTookMs, totalHits, returned, costMs, facetsEnabled`
- `search.suggest`（INFO）：`userId, keyword, returned, costMs`
- `search.trending`（INFO）：`category, returned, costMs`
- `search.history.clear`（INFO）：`userId, success, costMs`
- `search.index.upsert`（INFO）：`docId, postId, costMs`
- `search.index.delete`（INFO）：`docId, postId, reason, costMs`
- Redis best-effort 写失败（WARN）：`op=history|trend, userId, keyword, category, err`

必须有的最小告警信号（不讨论具体平台，口径写死）：

- DLQ 深度 > 0：`search.post.published.dlx.queue` / `search.post.updated.dlx.queue` / `search.post.deleted.dlx.queue` / `search.user.nickname_changed.dlx.queue`
- `search.index.*` 连续失败（1 分钟内 > 10 次）：说明 ES 或 DB 出问题
- `search.general` P95 延迟 > 200ms：说明 ES 查询或网络出问题

## 8. 本地启动与验收（可复制执行）

### 8.1 Docker 依赖（ES + Redis + RabbitMQ）

示例 `docker-compose.yml`（执行者可放到 `.codex/tmp/search/` 自用；不强制入库）：

```yaml
version: \"3.8\"
services:
  elasticsearch:
    image: docker.elastic.co/elasticsearch/elasticsearch:8.12.2
    environment:
      - discovery.type=single-node
      - xpack.security.enabled=false
      - ES_JAVA_OPTS=-Xms1g -Xmx1g
    ports:
      - \"9200:9200\"

  redis:
    image: redis:7
    ports:
      - \"6379:6379\"

  rabbitmq:
    image: rabbitmq:3-management
    ports:
      - \"5672:5672\"
      - \"15672:15672\"
```

> 说明：本地为了省事关闭 ES 安全；生产环境按你们基础设施要求启用。本文档不讨论鉴权细节（网关负责）。

### 8.2 初始化索引（curl）

先把 **3.3 的创建索引 JSON** 保存为 `mapping.json`（文件内容就是那段 JSON，不要自己改字段名）。

```bash
curl -X PUT http://127.0.0.1:9200/social_search_v1 -H 'Content-Type: application/json' -d @mapping.json
curl -X POST http://127.0.0.1:9200/_aliases -H 'Content-Type: application/json' -d '{\"actions\":[{\"add\":{\"index\":\"social_search_v1\",\"alias\":\"social_search\"}}]}'
```

### 8.3 冒烟用例（写死请求与预期结构）

1) 搜索 POST（无 filters）

```bash
curl \"http://127.0.0.1:8080/api/v1/search/general?keyword=你好&type=POST&sort=RELEVANT\"
```

预期（结构，不要求内容一致）：

- `code == \"0000\"`
- `data.items` 为数组
- `data.items[*].type == \"POST\"`

2) 搜索 POST（type=ALL 等同 POST）

```bash
curl \"http://127.0.0.1:8080/api/v1/search/general?keyword=你好&type=ALL&sort=RELEVANT\"
```

预期（结构）：`data.items[*].type == \"POST\"`

3) 不支持的 type（type=USER → items=[]）

```bash
curl \"http://127.0.0.1:8080/api/v1/search/general?keyword=alice&type=USER&sort=RELEVANT\"
```

预期：

- `code == \"0000\"`
- `data.items` 为空数组
- `data.facets` 可解析为 JSON，且 `meta.reason == \"UNSUPPORTED_TYPE\"`

4) 搜索 POST（filters JSON，注意 URL encode）

```bash
curl \"http://127.0.0.1:8080/api/v1/search/general?keyword=游戏&type=POST&sort=LATEST&filters=%7B%22limit%22%3A10%2C%22postTypes%22%3A%5B%22game_news%22%5D%7D\"
```

5) 热搜

```bash
curl \"http://127.0.0.1:8080/api/v1/search/trending?category=POST\"
```

6) 联想（空 keyword → top10）

```bash
curl \"http://127.0.0.1:8080/api/v1/search/suggest\"
```

7) 清空历史（必须带 header X-User-Id）

```bash
curl -X DELETE \"http://127.0.0.1:8080/api/v1/search/history\" -H \"Content-Type: application/json\" -H \"X-User-Id: 100\" -d \"{}\"
```

8) 异常：filters 非法 JSON → 0002

```bash
curl \"http://127.0.0.1:8080/api/v1/search/general?keyword=hi&filters=not_json\"
```

预期：

- `code == \"0002\"`（ILLEGAL_PARAMETER）

---

### 8.4 初次上线/重建索引（回灌 Runner 用法）

启动参数（示例）：

```bash
java -jar nexus-app.jar --spring.profiles.active=dev --search.backfill.enabled=true --search.backfill.pageSize=500
```

预期：

- 日志出现一条 warn：`search index backfill runner enabled, pageSize=...`
- ES 索引文档数量逐步增长（可用 `_cat/count/social_search_v1` 观察）

## 9. 参考与溯源（必须可追溯）

> 你要借鉴大厂经验，就别“凭感觉”。这里给出本方案每个关键点对应的公开来源。

### 9.1 借鉴点映射表

| 设计点 | 我们怎么做 | 溯源（URL） |
| --- | --- | --- |
| 倒排索引/Posting Lists 是搜索性能核心 | 读侧只查 ES（倒排），不回 MySQL | GitHub Code Search 架构：inverted index primer（https://github.blog/2023-02-06-the-technology-behind-githubs-new-code-search/） |
| 大规模搜索系统的核心挑战：爬取/索引/查询吞吐 | 用 MQ 驱动索引更新，ES 承担查询吞吐 | Google 论文《The Anatomy of a Large-Scale Hypertextual Web Search Engine》（http://infolab.stanford.edu/~backrub/google.html） |
| “数据库更新 + 发消息”需要原子性（避免不一致） | 发布事件在事务 after-commit；消费者回表再写 ES | Transactional Outbox Pattern（https://microservices.io/patterns/data/transactional-outbox.html） |
| 相关性打分可被业务因子调整（例如时效性） | 对 POST 使用 `function_score` + `gauss(createTime)` 做 recency | Elasticsearch Function score query（https://www.elastic.co/guide/en/elasticsearch/reference/current/query-dsl-function-score-query.html） |
| 语义/向量检索是可选增强，不应污染 MVP | MVP 先 BM25+recency；向量检索作为 Phase2 | Elasticsearch kNN search（https://www.elastic.co/guide/en/elasticsearch/reference/current/knn-search.html） |
| 搜索联想/纠错属于独立能力 | MVP 先用热搜+前缀过滤；后续可引入 term/phrase suggester | Elasticsearch Suggesters（https://www.elastic.co/guide/en/elasticsearch/reference/current/search-suggesters.html） |

### 9.2 Phase 2（可选增强，非本次上线范围，但写清楚怎么做）

如果你们明确要做“语义搜索”，按这个口径扩展（仍然不允许自由发挥）：

- 在 ES mapping 中新增 `dense_vector` 字段（例如 `contentVector`，dims 固定）
- 索引侧在 MQ consumer 中生成 embedding（建议异步：避免把外部模型延迟塞进 MQ 消费线程）
- 查询侧做“混合召回”：
  - BM25 召回 topN（N=200）
  - kNN 召回 topM（M=200）
  - 合并去重后按统一 score rerank（rerank 规则必须写死，不许拍脑袋）

---

## 10. Linus 式自检（上线前你必须过这一关）

【品味评分】🟡 凑合（MVP 保守可上线；Phase2 明确分离避免污染主链路）

【致命问题】如果你试图在 search/general 里“顺便”做用户搜索、群搜索、纠错、向量检索、AB 实验……你会得到一坨不可维护的垃圾。

【改进方向】

- USER/GROUP 搜索明确不在本次上线范围：如果要做，先把上游事件与真相源数据补齐，再另开一期实现；别试图“先查 MySQL 兜底”，那就是垃圾
- 把“联想/纠错”当成独立模块：不许在 SearchService 里堆 if/else
- 保持索引幂等：docId 写死，别引入随机 ID
