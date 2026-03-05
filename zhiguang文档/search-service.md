# Scooter-WSVA 搜索服务复现文档（基于仓库现有实现）

日期：2026-03-05  
范围：仅覆盖“视频搜索”链路（写入 ES 的索引链路 + 读取 ES 的查询链路），目标是让另一个 Codex agent 按本文档在本地复现同等功能。

## 0. 你要复现的到底是什么

Scooter 的“搜索服务”不是一个独立微服务，而是三段拼出来的链路：

1. **入口（HTTP）**：`feed-api` 暴露 REST 接口 `POST /feed/searcheEs`  
2. **核心查询（RPC）**：`feed-rpc` 实现 `SearchES`，去 Elasticsearch 查命中，再回 MySQL 补齐视频/作者/点赞收藏等信息  
3. **索引更新（异步）**：`mq-api` 作为 Kafka consumer 消费 **Canal 推来的 MySQL binlog 变更**，把 `title/content/label` 写进 ES 的 `video-index` 索引

一句话总结：**写链路 = MySQL → (binlog) → Canal → Kafka → mq-api → ES；读链路 = 前端 → feed-api → feed-rpc → ES → MySQL/其它RPC → 返回。**

---

## 1. 组件与代码入口一览（可直接跳转定位）

### 1.1 REST 入口：feed-api

- API 定义：`backend/feed/api/feed.api`
  - 搜索接口：`post /feed/searcheEs (SearchEsReq) returns (SearchEsResp)`（注意拼写：`searcheEs`）
  - 请求体：`{"content": "关键词"}`
- 路由注册：`backend/feed/api/internal/handler/routes.go`
  - 路由：`POST /feed/searcheEs`
  - 需要 JWT：`rest.WithJwt(serverCtx.Config.Auth.AccessSecret)`
- Handler：`backend/feed/api/internal/handler/searcheshandler.go`
- 业务逻辑：`backend/feed/api/internal/logic/searcheslogic.go`
  - 从 JWT claims 取 `uid`：`l.ctx.Value("uid")`
  - 调用 RPC：`l.svcCtx.FeedRpc.SearchES(...)`
- RPC 客户端注入：`backend/feed/api/internal/svc/servicecontext.go`
- 配置：`backend/feed/api/etc/feed-api.yaml`

### 1.2 RPC 核心：feed-rpc

- Proto：`backend/feed/rpc/feed.proto`
  - 方法：`rpc SearchES(Es_search_req) returns(Es_search_resp);`
  - 请求：`userId + content`
- Server 路由（goctl 生成）：`backend/feed/rpc/internal/server/feedserver.go`
  - `SearchES(...)` → `logic.NewSearchESLogic(...).SearchES(...)`
- 业务逻辑：`backend/feed/rpc/internal/logic/searcheslogic.go`
  - ES 查询索引：`video-index`
  - 查询方式：`multi_match`，字段 `title/content/label`
  - 去重：按 `video_id` 去重，再用 MySQL 回查视频详情
- ES 客户端封装（带 OTel trace/metric）：`backend/pkg/es/es.go`
- feed-rpc ServiceContext 注入 ES + Model + UserRpc 等：`backend/feed/rpc/internal/svc/servicecontext.go`
- 配置：`backend/feed/rpc/etc/feed.yaml`

### 1.3 异步索引写入：mq-api（Kafka consumer）

- 消费者注册：`backend/mq/internal/logic/mqs.go`
  - `kq.MustNewQueue(c.KqVideoConsumerConf, NewVideoUpLogic(...))`
- ES 写入逻辑：`backend/mq/internal/logic/videouplogic.go`
  - 消费 Kafka topic（配置里叫 `testVideos`）
  - 解析 Canal JSON（只用 `data` 字段）
  - 把 `title/content/label` upsert 到 ES 索引 `video-index`
- Canal 消息结构：`backend/mq/types/video.go`（`CanalArticleMsg`）
- mq ServiceContext 注入 ES + RPC 客户端：`backend/mq/internal/svc/servicecontext.go`
- 配置：`backend/mq/etc/mq-api.yaml`
- Kafka 封装（consumer/pusher）：`backend/kq/*`

---

## 2. 读链路（用户发起搜索请求）全链路拆解

下面按“真实调用顺序”写，照着走就能复现。

### 2.1 请求进入：HTTP `POST /feed/searcheEs`

定义见 `backend/feed/api/feed.api`：

- Request：`SearchEsReq{ Content string }`
- Response：`SearchEsResp{ StatusCode, StatusMsg, VideoList []VideoInfo }`

路由注册见 `backend/feed/api/internal/handler/routes.go`：

- `Path: "/feed/searcheEs"`
- 被 `rest.WithJwt(...)` 包裹：必须带 JWT（否则 401）

JWT 的 `uid` claim 来自用户登录发 token：`backend/common/jwtx/jwt.go` + `backend/user/api/internal/logic/loginlogic.go`  
其中 `jwtx.GetToken` 明确写入 `claims["uid"] = uid`，所以 feed-api 才能用 `ctx.Value("uid")` 拿到。

### 2.2 feed-api Handler/Logic

1) Handler：`backend/feed/api/internal/handler/searcheshandler.go`

- `httpx.Parse(r, &req)` 解析 JSON 请求体
- 调 `logic.NewSearchEsLogic(...).SearchEs(&req)`

2) Logic：`backend/feed/api/internal/logic/searcheslogic.go`

关键动作：

- 从 JWT 上下文取登录用户：
  - `uid, _ := l.ctx.Value("uid").(json.Number).Int64()`
- 调 feed-rpc 的 `SearchES`：
  - `FeedRpc.SearchES(ctx, &feed.EsSearchReq{ UserId: int32(uid), Content: req.Content })`
- 把 RPC 返回的 `feed.VideoInfo` 映射成 API 层 `types.VideoInfo` 并返回

这里 API 层其实只是“转发 + DTO 映射”，真正的搜索发生在 feed-rpc。

### 2.3 feed-rpc SearchES：ES 查命中 + MySQL/其它RPC补齐

核心实现：`backend/feed/rpc/internal/logic/searcheslogic.go`

步骤：

1) 组装 ES 查询（`multi_match`）：

- index：固定 `"video-index"`
- query JSON（伪代码）：

```json
{
  "query": {
    "multi_match": {
      "query": "<content>",
      "fields": ["title", "content", "label"]
    }
  }
}
```

2) 调 ES：

- `l.svcCtx.Es.Client.Search(WithIndex("video-index"), WithBody(body))`
- 读取 `response.Body` 后 `json.Unmarshal` 到本地 `Response` 结构

3) 从 ES hits 提取 `video_id`：

- `responses.Hits.Hits[i].Source.VideoID`
- 然后 `removeDuplicates(videoIds)` 去重（保留原顺序）

4) 对每个 `video_id` 回查 MySQL（视频详情）：

- `l.svcCtx.VideoModel.FindOne(ctx, videoID)`（模型见 `backend/feed/gmodel/videos.go`，表 `videos`）

5) 补齐作者信息（跨服务 RPC）：

- `l.svcCtx.UserRpc.UserInfo(ctx, &user.UserInfoRequest{ UserId: in.UserId, ActorId: video.AuthorId })`

6) 补齐用户对该视频的状态：

- 是否点赞：`l.svcCtx.FavorModel.IsFavorite(ctx, uid, vid)`（表 `favorites`，见 `backend/favorite/gmodel/favorites.go`）
- 是否收藏：`l.svcCtx.StarModel.IsStarExist(ctx, uid, vid)`（表 `stars`，见 `backend/favorite/gmodel/stars.go`）

7) 组装 `feed.VideoInfo` 列表返回给 feed-api

到这里，读链路闭环：`feed-api` 只是入口，**ES 只负责“命中哪些视频”**，最终返回给用户的完整视频对象仍以 **MySQL + RPC** 为准。

---

## 3. 写链路（ES 索引怎么来的）全链路拆解

这一段是复现的关键：没有索引，搜索就是空的。

### 3.1 索引里到底存什么

`mq` 写 ES 的结构：`backend/mq/types/video.go`

```go
type VideoEsMsg struct {
  VideoId int64  `json:"video_id"`
  Title   string `json:"title"`
  Content string `json:"content"`
  Label   string `json:"label"`
}
```

所以 ES `video-index` 里最少要能搜三类字段：

- `title`：视频标题（来自 MySQL `videos.title`）
- `content`：评论内容（来自 MySQL `comments.content`，其中包含系统自动写入的“摘要评论”）
- `label`：标签（来自 MySQL `categories.label`，注意 LabelModel 的 TableName 写的是 `categories`）

### 3.2 数据变更从哪里来（为什么要 Canal）

仓库的设计目标是：**数据库一变，ES 也要跟着变**，否则用户搜索到的还是旧数据。

他们选的方案是标准套路：

- MySQL 负责“真数据”
- binlog 记录每次 insert/update/delete
- Canal 订阅 binlog，转成 JSON 消息推到 Kafka
- `mq-api` 消费 Kafka，更新 ES

### 3.3 Canal → Kafka：你必须满足的输入格式

`mq` 的 `VideoUpLogic` 只解析一个字段：`data`（数组）。其它 Canal 标准字段（如 `table/type/database/ts`）即使存在也会被忽略。

代码位置：`backend/mq/internal/logic/videouplogic.go` + `backend/mq/types/video.go`

`CanalArticleMsg` 结构（简化）：

```json
{
  "data": [
    {
      "id": "123",
      "vid": "123",
      "title": "xxx",
      "content": "yyy",
      "label": "zzz"
    }
  ]
}
```

注意两点（否则写 ES 写不进去）：

1) `videouplogic.go` 先做了一个很粗暴的过滤：如果原始 JSON 字符串里不包含 `title/content/name/dec/label` 这些子串，就直接 `return nil`。  
所以 Canal 输出的字段名必须是这些小写字段（至少命中一个）。

2) 它用两种方式取 `video_id`：

- 写 `title` 时：`video_id = parseInt(d.id)`（假设消息来自 `videos` 表）
- 写 `content/label` 时：`video_id = parseInt(d.vid)`（假设消息来自 `comments/categories` 等带外键 `vid` 的表）

也就是说：**评论/标签表的消息必须带 `vid` 字段**。

### 3.4 mq-api 消费 Kafka 并 Upsert 到 ES

消费者注册：`backend/mq/internal/logic/mqs.go`

```go
kq.MustNewQueue(c.KqVideoConsumerConf, NewVideoUpLogic(ctx, svcContext))
```

Kafka topic 在 `backend/mq/etc/mq-api.yaml`：

- `KqVideoConsumerConf.Topic: testVideos`

写 ES 的核心：`backend/mq/internal/logic/videouplogic.go`

- 目标索引：常量 `videoEsIndex = "video-index"`
- 写入方式：`esutil.NewBulkIndexer` + `Action: "update"` + `doc_as_upsert=true`
- 写入 payload（实际发送到 ES 的 body）：

```json
{"doc": {"video_id":123,"title":"xxx"}, "doc_as_upsert": true}
```

实现里有一个非常重要的“事实”（会影响你复现结果的形态）：

- `DocumentID` 被写成了 `time.Now().UnixMicro()`  
这意味着每次写入几乎都会产生一个“新文档”，而不是用 `video_id` 覆盖同一个文档。  
后果：同一个视频可能在 ES 里出现多条文档（title 一条、content 一条、label 多条）。  
而 feed-rpc 搜索结果之所以还能正常返回，是因为 `feed/rpc/internal/logic/searcheslogic.go` 里做了 `video_id` 去重。

如果你要“复现仓库行为”，就照这个做；如果你要“做一个更像样的实现”，见本文最后的改进建议。

---

## 4. 关键配置清单（复现必改的点）

仓库里的 `*.yaml` 配置很多是作者内网 IP（`172.*`），你在本地跑一定要改成你自己的：

### 4.1 ES

- feed-rpc：`backend/feed/rpc/etc/feed.yaml` → `Es.Addresses/Username/Password`
- mq-api：`backend/mq/etc/mq-api.yaml` → `Es.*`

索引名必须一致：`video-index`

字段名也必须一致（否则 feed-rpc 解不出 `video_id`）：

- `video_id`：必须是数字类型（`long/int`）。如果你手动插入文档把它写成字符串，`json.Unmarshal` 到 `int` 会失败。
- `title/content/label`：至少要能做全文检索（`text`）

推荐 mapping（可选，但能减少“动态 mapping 随缘”的坑）：

```json
{
  "mappings": {
    "properties": {
      "video_id": { "type": "long" },
      "title": { "type": "text", "fields": { "keyword": { "type": "keyword", "ignore_above": 256 } } },
      "content": { "type": "text" },
      "label": { "type": "text", "fields": { "keyword": { "type": "keyword", "ignore_above": 256 } } }
    }
  }
}
```

### 4.2 Kafka

- mq-api：`backend/mq/etc/mq-api.yaml`
  - `KqVideoConsumerConf.Topic`：必须是 Canal 推消息的 topic（默认 `testVideos`）
  - `KqConsumerConf.Topic`：`uploadVideos`（非搜索必需，但如果你走“发布视频→自动摘要→打标签→触发索引”的完整链路会用到）
  - `KqConsumerJobConf.Topic`：`job`（同上）
- feed-rpc：`backend/feed/rpc/etc/feed.yaml` 里也有 `KqPusherJobConf.Topic: job`

### 4.3 Consul / RPC 依赖

feed-api 通过 Consul 找 feed-rpc：`backend/feed/api/etc/feed-api.yaml` 的 `Feed.Target`

feed-rpc 搜索需要：

- MySQL（视频表）
- user-rpc（作者信息）
- MySQL（favorites/stars 表）用于 `IsFavorite/IsStar`

如果你只想做“能搜到视频标题并返回基本信息”的最小复现，可以临时把这些“补齐信息”逻辑 stub 掉；但那就不再是“复现仓库实现”。

### 4.4 如果你用仓库的 `docker-compose.yml`（强烈建议按这个跑）

先把现实说清楚：仓库 `docker-compose.yml` 能起 MySQL/ES/Kafka/Consul 等基础设施，但 **它不会自动让“搜索索引写链路”工作**，原因有两点：

1) `docker-compose.yml` 里没有 Canal（binlog → Kafka 这段缺失）  
2) Go 服务镜像里带的 `*.yaml` 配置默认写的是作者内网 `172.*`，容器里根本访问不到

你要做的是把这些地址改成 compose 内网域名（用 service 名最稳）：

- `mysql`：`mysql:3306`
  - root 密码：`scooter`（见 `docker-compose.yml`）
  - 数据库：`scooter`（见 `docker-compose.yml`；初始化脚本 `backend/sql/qiniu.sql` 会在该库里建表）
- `elasticsearch`：`http://elasticsearch:9200`
- `kafka`：`kafka:9092`
- `consul`：`consul:8500`

以及把你看到的 DSN 里 `/qiniu` 改成 `/scooter`（否则连不上库）。

---

## 5. 最小可跑复现步骤（给另一个 Codex agent 的执行清单）

下面是“按仓库思路复现”的最短路径（不追求一键跑通全家桶，而是把搜索链路跑通）。

### Step 1：起基础依赖

你需要至少：

- MySQL（有 `videos/comments/categories/favorites/stars/users` 等表）
- Elasticsearch（8.x，能创建 `video-index`）
- Kafka（Canal 输出到 Kafka，mq-api 消费）
- Consul（如果你按仓库方式通过服务发现跑 feed-api/feed-rpc）

仓库提供了 `docker-compose.yml`，但它 **没有 Canal**，而且 Go 服务内置的 `*.yaml` 默认不是容器域名，需要你改。

### Step 2：准备 ES 索引 `video-index`

仓库没有显式创建索引的代码。你可以：

- 直接让 mq-api 第一次写入时由 ES 自动创建（动态 mapping），能跑但不可控；
- 或者你手动创建 index，并把 `title/content/label` 设成可全文检索的 `text` 字段（更稳）。

### Step 3：让 ES 里有数据（两条路任选其一）

#### 路 A（最贴近仓库）：MySQL → Canal → Kafka → mq-api → ES

1) 配 Canal 订阅 MySQL binlog  
2) 配 Canal 把变更消息写入 Kafka topic（例如 `testVideos`）  
3) 启动 mq-api（读取 `backend/mq/etc/mq-api.yaml`）  
4) 往 MySQL 插入/更新以下任一数据：
   - `videos.title`
   - `comments.content`（需要 `vid`）
   - `categories.label`（需要 `vid`）

看到 mq-api 打日志 `开始传输给ES` 且 ES 里能查到文档就算通了。

#### 路 B（跳过 Canal）：手动往 ES 插入同结构文档

直接往 `video-index` 插入文档：

```json
{"video_id": 123, "title":"hello", "content":"world", "label":"funny"}
```

这样可以先验证读链路（feed-api/feed-rpc）没问题，再回头补 Canal。

#### 路 C（推荐调试用）：跳过 Canal，但保留 Kafka → mq-api → ES

你不想配 Canal？没问题。`mq-api` 真正需要的只是“符合 `CanalArticleMsg` 的 JSON 消息”。

做法：

1) 启动 Kafka + mq-api（确保 `backend/mq/etc/mq-api.yaml` 的 `KqVideoConsumerConf.Topic` 指向你要投递的 topic，例如 `testVideos`）  
2) 直接往 Kafka topic 投递一条消息（示例）

示例 payload（模拟 `videos` 表 insert，写入 title）：

```json
{
  "data": [
    { "id": "123", "title": "hello world" }
  ]
}
```

示例 payload（模拟 `comments` 表 insert，写入 content，需要 `vid`）：

```json
{
  "data": [
    { "id": "1", "vid": "123", "content": "this is a summary" }
  ]
}
```

示例 payload（模拟 `categories` 表 insert，写入 label，需要 `vid`）：

```json
{
  "data": [
    { "id": "1", "vid": "123", "label": "funny" }
  ]
}
```

只要消息字符串里包含 `title/content/label` 任一字段名（见 `backend/mq/internal/logic/videouplogic.go` 的过滤逻辑），mq-api 就会消费并写入 ES。

### Step 4：跑通读链路：调用 `/feed/searcheEs`

1) 先用 `/user/login` 获取 JWT（token 里要有 `uid` claim）  
2) 带上 `Authorization: Bearer <token>` 调：

- `POST /feed/searcheEs`
- body：`{"content":"hello"}`

预期：返回 `video_list`，且每个视频来自 MySQL 回查（标题/播放地址等）。

---

## 6. Linus 风格代码审查（帮助你避坑，不是让你照抄）

### 品味评分

- feed-rpc 搜索主逻辑：黄色（凑合能跑，但很多“人为制造的特殊情况”）
- mq 写 ES：红色（数据结构选错了，导致必须在读链路做去重补锅）

### 致命问题（复现时你必须知道它们的存在）

1) ES 文档 ID 用时间戳：导致同一视频多文档 → 读链路必须去重  
   - 文件：`backend/mq/internal/logic/videouplogic.go`

2) ES 查询 JSON 用 `fmt.Sprintf` 拼字符串：不转义输入，遇到引号等字符就可能坏  
   - 文件：`backend/feed/rpc/internal/logic/searcheslogic.go`

3) `response.Body` 未显式 `Close()`：会泄漏连接（跑久了必炸）  
   - 文件：`backend/feed/rpc/internal/logic/searcheslogic.go`

4) feed-api DTO 映射直接解引用 optional 字段指针：nil 就 panic  
   - 文件：`backend/feed/api/internal/logic/searcheslogic.go`

### 改进方向（不影响“理解与复现”，但影响你未来能不能维护）

如果你要把它写成“正常工程”：

- ES 文档 ID 直接用 `video_id`，一个视频对应一条文档，title/content/labels 作为字段更新
- Canal 消费不要用 `strings.Contains` 过滤，应该按 `table` 字段或 schema 过滤
- ES 查询用结构体/Map 构造，再 json.Marshal，别拼字符串
- 统一索引 schema：`labels` 用数组字段，不要一条 label 一个 doc

以上是“好品味”的版本：消掉特殊情况，让去重逻辑消失。
