# Nexus 搜索系统替换为 Zhiguang 方案（最终实施方案）

文档日期：2026-03-07  
适用仓库：`project/nexus`  
输出目的：**只写实现说明，不改代码**。本文是给后续 Codex 执行的实施蓝图。

---

## 0. 已拍板，后续实现不得偏离

用户已经明确选择：

- `1B`：对外搜索 API 改成 `zhiguang` 风格
- `2A`：删除 `history` 和 `trending`
- `3B`：搜索索引增量链路改成 `Outbox -> RabbitMQ -> Search Consumer -> ES`
- `4A`：不做 `trending`
- 搜索接口返回形式：**继续沿用 Nexus 统一 `Response<T>` 包装**
- `title` 规则：**草稿允许不填；发布时必须必填**
- 数据兼容规则：**当前 MySQL、Redis、ES、Outbox、MQ 等组件里没有历史数据，不需要处理任何旧数据兼容、旧索引兼容、历史记录迁移或回填修补**
- 缺失字段处理：**当前项目没有 `title` 字段，必须补充标题字段**；其余当前项目没有真值的字段，按固定兜底规则处理
- 当前轮次：**只产出文档，不改代码**

这意味着：

1. 旧搜索接口会下线，前端必须切到新接口。  
2. 旧的 Redis 搜索历史 / 热搜能力全部删除，不做兼容。  
3. 搜索写链改成 `Outbox -> RabbitMQ -> SearchIndexConsumer -> ES`，复用现有 Outbox、重试任务和 Search RabbitMQ 消费者。  
4. 搜索接口不再沿用旧搜索 DTO，但**继续保持 Nexus 统一 `Response<T>` 风格**。  
5. 为了让 `suggest` 和搜索结果里的 `title` 成立，**内容域必须先补 `title` 字段**。  
6. 为了复刻 `zhiguang` 的排序语义，**内容域还必须补 `publish_time` 字段**；不能继续拿 `create_time` 冒充发布时间。  
7. 因为当前 MySQL、Redis、ES、Outbox、MQ 等组件里没有历史数据，**不需要做旧帖子兼容、旧索引迁移、旧标题修补**。

---

## 1. 一句话结论

这次不是“修一下搜索”，而是做 3 件事：

1. **删掉 Nexus 旧搜索壳子**：`/api/v1/search/general`、`/trending`、`/history`、Redis 热搜/历史。  
2. **把搜索读链换成 Zhiguang**：`GET /api/v1/search`、`GET /api/v1/search/suggest`、ES `function_score`、`search_after`、高亮、Completion Suggester。  
3. **把内容模型补齐到“能支撑这个搜索”**：最少补 `title` 和 `publish_time`，否则结果结构和排序都站不住。

---

## 2. 当前事实，不允许后续实现者自己脑补

### 2.1 当前 Nexus 搜索系统真实情况

当前入口和实现：

- HTTP：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/SearchController.java`
- API：`project/nexus/nexus-api/src/main/java/cn/nexus/api/social/ISearchApi.java`
- Domain：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/ISearchService.java`
- Domain 实现：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/SearchService.java`
- ES 端口：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/SearchEnginePort.java`
- Redis 历史：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/SearchHistoryRepository.java`
- Redis 热搜：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/SearchTrendingRepository.java`
- RabbitMQ 搜索索引消费者：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/SearchIndexConsumer.java`

当前对外接口：

- `GET /api/v1/search/general`
- `GET /api/v1/search/suggest`
- `GET /api/v1/search/trending`
- `DELETE /api/v1/search/history`

这些接口这次都不保留兼容壳，直接换。

### 2.2 当前 Nexus 内容域真实情况

当前内容域没有标题字段，只有：

- `content_post.content_uuid`
- `content_post` 对应实体里还有 `contentText`
- `content_post.summary`
- `content_post.summary_status`
- `content_post.post_types`
- `content_post.media_type`
- `content_post.media_info`
- `content_post.location_info`
- `content_post.status`
- `content_post.visibility`
- `content_post.version_num`
- `content_post.is_edited`
- `content_post.create_time`

这些字段会直接影响后续搜索文档映射：

- `body` 来源于 `contentText`
- `description` 默认来源于 `summary`
- `tags` 来源于 `postTypes`
- `coverImage` 来源于 `mediaInfo`
- 是否允许入索引，取决于 `status + visibility`

对应实体：

- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/model/entity/ContentPostEntity.java`
- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/dao/social/po/ContentPostPO.java`

当前内容发布请求 DTO 也没有标题字段：

- `project/nexus/nexus-api/src/main/java/cn/nexus/api/social/content/dto/PublishContentRequestDTO.java`
- `project/nexus/nexus-api/src/main/java/cn/nexus/api/social/content/dto/SaveDraftRequestDTO.java`
- `project/nexus/nexus-api/src/main/java/cn/nexus/api/social/content/dto/DraftSyncRequestDTO.java`

当前搜索结果所需字段里，Nexus 没有真值的有：

- `title`：没有，必须补字段
- `favoriteCount`：没有，固定返回 `0`
- `faved`：没有，固定返回 `false`
- `tagJson`：没有，固定返回 `null`
- `isTop`：没有，固定返回 `null`

### 2.3 当前 Nexus 已有可复用的 Outbox -> RabbitMQ 搜索增量骨架

当前项目现状：

- **有**内容域 Outbox：`content_event_outbox`
- **有**用户域 Outbox：`user_event_outbox`
- **有**afterCommit 触发投递
- **有**Outbox 重试任务
- **有**RabbitMQ 发布，exchange 就是 `social.feed`
- **有**现成 Search RabbitMQ 消费者：`SearchIndexConsumer`
- **没有必要为了搜索再额外引入 Canal**

所以 `3B` 的含义不是“打开一个现成开关”，而是：

1. 复用现有 `content_event_outbox` / `user_event_outbox` 作为唯一事件源  
2. 继续走当前项目已有的 `afterCommit + retry job + RabbitMQ` 发布链  
3. 保留并改写 `SearchIndexMqConfig` / `SearchIndexConsumer` / `SearchIndexBackfillRunner`，补齐 zhiguang 的索引字段、查询 DSL、标题与发布时间语义

---

## 3. 最终目标状态

完成后，搜索系统必须变成下面这样：

```text
HTTP GET /api/v1/search
    -> SearchController
    -> SearchService
    -> Elasticsearch
    -> search_after 游标分页
    -> 高亮 title/body
    -> 返回 items + nextAfter + hasMore

HTTP GET /api/v1/search/suggest
    -> SearchController
    -> SearchService
    -> Elasticsearch completion suggester
    -> 返回标题联想

内容发布/更新/删除、昵称变更
    -> 写 MySQL + Outbox
    -> afterCommit / retry job
    -> RabbitMQ exchange social.feed
    -> SearchIndexConsumer
    -> Elasticsearch upsert / soft delete / updateByQuery nickname

应用启动
    -> SearchIndexInitializer 建索引
    -> 若索引为空则 SearchIndexBackfillRunner 全量回灌
```

---

## 4. 对外接口，后续必须按这个写

## 4.1 `GET /api/v1/search`

### 请求参数

- `q`：`String`，必填，搜索关键词
- `size`：`int`，可选，默认 `20`，最小值 `1`
- `tags`：`String`，可选，逗号分隔，例如 `java,并发`
- `after`：`String`，可选，Base64URL 编码的游标

### Controller 入参形式

- `SearchController` 直接使用 `@RequestParam` 接收 `q/size/tags/after`
- **不再使用** `SearchGeneralRequestDTO`
- **不再使用** `SearchSuggestRequestDTO`
- **不再保留** 旧 `filters` JSON 入参模式

### 登录语义

- 允许匿名调用
- `userId` 从 `UserContext.getUserId()` 取，可为空
- 匿名用户时：`liked=false`，`faved=false`

### 返回形式

- `SearchController` 返回 `Response<SearchResponseDTO>`
- 成功时：`Response.success(code, info, dto)`
- 失败时：沿用当前项目其它 Controller 的 `try/catch + Response.builder()` 风格

### 响应结构

新增 DTO 文件，固定使用：

- `project/nexus/nexus-api/src/main/java/cn/nexus/api/social/search/dto/SearchResponseDTO.java`
- `project/nexus/nexus-api/src/main/java/cn/nexus/api/social/search/dto/SearchItemDTO.java`

`SearchResponseDTO`：

- `List<SearchItemDTO> items`
- `String nextAfter`
- `boolean hasMore`

`SearchItemDTO`：

- `String id`
- `String title`
- `String description`
- `String coverImage`
- `List<String> tags`
- `String authorAvatar`
- `String authorNickname`
- `String tagJson`
- `Long likeCount`
- `Long favoriteCount`
- `Boolean liked`
- `Boolean faved`
- `Boolean isTop`

### 字段固定映射规则

- `id = String.valueOf(postId)`
- `title = content_post.title`
- `description = 高亮 snippet；若没有高亮，则取 content_post.summary`
- `coverImage = mediaInfo 拆分后的第一项；若不是 http/https，则调用 MediaStoragePort.generateReadUrl(token)`
- `tags = postTypes`
- `authorAvatar = user.avatarUrl`
- `authorNickname = user.nickname`
- `tagJson = null`
- `likeCount = 当前帖子点赞数`
- `favoriteCount = 0`
- `liked = 当前用户是否点赞该 post；匿名固定 false`
- `faved = false`
- `isTop = null`

### 不允许的自由发挥

- 不允许继续返回旧的 `facets`
- 不允许保留 `/general` 作为兼容路由
- 不允许把 `filters` JSON 兼容进新接口
- 不允许保留旧 `SearchGeneralResponseDTO` / `SearchSuggestResponseDTO` / `SearchTrendingResponseDTO` 结构

## 4.2 `GET /api/v1/search/suggest`

### 请求参数

- `prefix`：`String`，必填
- `size`：`int`，可选，默认 `10`，最小值 `1`

### Controller 入参形式

- `SearchController` 直接使用 `@RequestParam` 接收 `prefix/size`
- **不再使用** 旧 `SearchSuggestRequestDTO`

### 响应结构

新增 DTO 文件，固定使用：

- `project/nexus/nexus-api/src/main/java/cn/nexus/api/social/search/dto/SuggestResponseDTO.java`

字段：

- `List<String> items`

### 返回形式

- `SearchController` 返回 `Response<SuggestResponseDTO>`
- 成功时：`Response.success(code, info, dto)`

### 联想来源

- 只用 `title_suggest`
- `title_suggest` 的来源是新增后的 `content_post.title`
- 不是 `summary`
- 不是 `contentText` 前 32 个字

## 4.3 删除的接口

下列接口必须删除，不做兼容：

- `GET /api/v1/search/general`
- `GET /api/v1/search/trending`
- `DELETE /api/v1/search/history`

---

## 5. 标题字段改造，这是本次必须先做的前置条件

这一节是硬前置。**不先补标题字段，就不要开始做搜索替换。**

## 5.1 为什么必须补

因为目标系统依赖：

- 搜索权重：`title^3`
- 高亮字段：`title`
- 联想字段：`title_suggest`

而当前 Nexus 根本没有 `title`。如果不补，后续实现者只能偷懒拿 `summary` 或 `contentText` 伪装标题，这就是“自己做决定”，本方案禁止。

## 5.2 标题字段规格，写死

标题字段规格固定按 `zhiguang` 主表走：

- 字段名：`title`
- 类型：`VARCHAR(256)`
- 可空：`NULL`
- 字符集：沿用表默认 `utf8mb4`

### 标题输入规则，写死

- `PUT /api/v1/content/draft`：`title` 可以为空
- `POST /api/v1/content/publish`：`title` 必填
- 发布时若 `title == null` 或 `title.trim().isEmpty()`：直接返回参数错误
- 因为当前组件里没有历史数据，**不提供任何“自动从 summary/正文补 title”的兼容逻辑**

## 5.3 还必须同时补 `publish_time`

因为目标排序链路是：

1. `_score`
2. `publish_time`
3. `like_count`
4. `view_count`
5. `content_id`

当前 Nexus 没有真实 `publish_time`，只有 `create_time`。而 `postId=draftId` 的模式下，`create_time` 可能是草稿创建时间，不是实际发布时间。  
所以必须新增：

- 字段名：`publish_time`
- 类型：`DATETIME NULL`
- 写入时机：**post 真正进入可搜索状态时** 写入
- 禁止用 `create_time` 继续冒充 `publish_time`

## 5.4 必改数据库表

新增 migration 文件，固定路径：

- `project/nexus/docs/migrations/20260307_01_add_title_and_publish_time_to_content.sql`

该 SQL 必须至少包含：

1. `content_post` 新增：
   - `title VARCHAR(256) NULL`
   - `publish_time DATETIME NULL`
2. `content_draft` 新增：
   - `title VARCHAR(256) NULL`
3. `content_history` 新增：
   - `snapshot_title VARCHAR(256) NULL`
4. `content_publish_attempt` 新增：
   - `snapshot_title VARCHAR(256) NULL`

因为当前组件里没有历史数据，这个 migration **不需要**：

- 旧帖子标题回填 SQL
- 旧索引兼容 SQL
- 旧数据补偿脚本

## 5.5 必改内容 API / Domain / Repository

### API DTO

以下 DTO 必须新增 `title` 字段：

- `project/nexus/nexus-api/src/main/java/cn/nexus/api/social/content/dto/PublishContentRequestDTO.java`
- `project/nexus/nexus-api/src/main/java/cn/nexus/api/social/content/dto/SaveDraftRequestDTO.java`
- `project/nexus/nexus-api/src/main/java/cn/nexus/api/social/content/dto/DraftSyncRequestDTO.java`

以下 DTO 必须新增 `title` 响应字段：

- `project/nexus/nexus-api/src/main/java/cn/nexus/api/social/content/dto/ContentDetailResponseDTO.java`
- `project/nexus/nexus-api/src/main/java/cn/nexus/api/social/content/dto/ContentHistoryResponseDTO.java`（如果内部有 item 列表，则 item 也加 `title`）

### Domain 接口

以下方法签名必须改：

- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/IContentService.java`

固定改法：

- `saveDraft(Long userId, Long draftId, String title, String contentText, List<String> mediaIds)`
- `publish(Long postId, Long userId, String title, String text, String mediaInfo, String location, String visibility, List<String> postTypes)`
- `syncDraft(Long draftId, Long userId, String title, String diffContent, Long clientVersion, String deviceId, List<String> mediaIds)`

### Domain 实体

以下实体必须新增 `title`：

- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/model/entity/ContentPostEntity.java`
- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/model/entity/ContentDraftEntity.java`
- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/model/entity/ContentHistoryEntity.java`
- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/model/entity/ContentPublishAttemptEntity.java`

其中 `ContentPostEntity` 还必须新增：

- `Long publishTime`

### Repository / MyBatis

以下 PO / DAO / Mapper 必须同步改：

- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/dao/social/po/ContentPostPO.java`
- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/dao/social/po/ContentHistoryPO.java`
- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/dao/social/po/ContentPublishAttemptPO.java`
- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/dao/social/IContentPostDao.java`
- `project/nexus/nexus-infrastructure/src/main/resources/mapper/social/ContentPostMapper.xml`
- `project/nexus/nexus-infrastructure/src/main/resources/mapper/social/ContentHistoryMapper.xml`
- `project/nexus/nexus-infrastructure/src/main/resources/mapper/social/ContentPublishAttemptMapper.xml`
- `project/nexus/nexus-infrastructure/src/main/resources/mapper/social/ContentDraftMapper.xml`

### 业务规则固定写法

1. `title` 参与发布幂等 token 计算  
2. `title` 写入 `content_draft` 以支持草稿恢复  
3. `title` 写入 `content_history.snapshot_title` 以支持版本审计  
4. `title` 写入 `content_publish_attempt.snapshot_title` 以支持排障  
5. post 真正进入可搜索状态时，写 `publish_time = 当前毫秒时间`  
6. `ContentDetailQueryService` 返回详情时带上 `title`
7. `publish_time` 只在 post 真正进入“可搜索态”时写入，禁止继续复用草稿创建时间

---

## 6. 旧搜索系统要删什么，后续实现不得留尾巴

## 6.1 直接删除的接口能力

- 搜索历史
- 热搜
- 旧综合搜索

## 6.2 需要删除或彻底改写的文件

### Trigger

- `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/SearchController.java`（重写，不保留旧路由）
- `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/config/SearchIndexMqConfig.java`（保留并改写为 Outbox -> RabbitMQ 搜索索引链）
- `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/SearchIndexConsumer.java`（保留并改写，继续负责索引更新）

### API

- `project/nexus/nexus-api/src/main/java/cn/nexus/api/social/ISearchApi.java`（重写）
- `project/nexus/nexus-api/src/main/java/cn/nexus/api/social/search/dto/SearchGeneralRequestDTO.java`（删除）
- `project/nexus/nexus-api/src/main/java/cn/nexus/api/social/search/dto/SearchGeneralResponseDTO.java`（删除）
- `project/nexus/nexus-api/src/main/java/cn/nexus/api/social/search/dto/SearchSuggestRequestDTO.java`（删除）
- `project/nexus/nexus-api/src/main/java/cn/nexus/api/social/search/dto/SearchSuggestResponseDTO.java`（删除）
- `project/nexus/nexus-api/src/main/java/cn/nexus/api/social/search/dto/SearchTrendingRequestDTO.java`（删除）
- `project/nexus/nexus-api/src/main/java/cn/nexus/api/social/search/dto/SearchTrendingResponseDTO.java`（删除）
- `project/nexus/nexus-api/src/main/java/cn/nexus/api/social/search/dto/SearchHistoryDeleteRequestDTO.java`（删除）

### Domain

- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/ISearchService.java`（重写）
- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/SearchService.java`（重写）
- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/model/valobj/SearchTrendingVO.java`（删除）
- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/adapter/repository/ISearchHistoryRepository.java`（删除）
- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/adapter/repository/ISearchTrendingRepository.java`（删除）

### Infrastructure

- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/SearchHistoryRepository.java`（删除）
- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/SearchTrendingRepository.java`（删除）
- `project/nexus/nexus-app/src/main/java/cn/nexus/config/SearchIndexBackfillRunner.java`（保留并重写为“先 count、空索引才回灌”的新版）
- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/SearchEnginePort.java`（重写为新 DSL）

### 配置

从 `project/nexus/nexus-app/src/main/resources/application-dev.yml` 删除：

- `search.history.*`
- `search.trending.*`
- 旧搜索 `result.defaultLimit/maxLimit`（如果新实现不再使用）

保留或新增：

- `search.es.*`
- `search.backfill.*`
- `search.mq.*`

---

## 7. 新搜索读链，后续实现必须照着写

## 7.1 Elasticsearch 索引固定名

- 索引名固定：`zhiguang_content_index`

不要发明 `social_search_v2`、`nexus_search_post` 之类新名字。

## 7.2 ES Mapping 固定字段

新增类，固定路径：

- `project/nexus/nexus-app/src/main/java/cn/nexus/config/SearchIndexInitializer.java`

索引字段固定如下：

- `content_id`：`long`
- `content_type`：`keyword`，固定写 `POST`
- `description`：`text`，`ik_max_word`
- `title`：`text`，`analyzer=ik_max_word`，`search_analyzer=ik_smart`
- `body`：`text`，`ik_max_word`
- `tags`：`keyword`
- `author_id`：`long`
- `author_avatar`：`keyword`
- `author_nickname`：`keyword`
- `author_tag_json`：`keyword`
- `publish_time`：`date`
- `like_count`：`integer`
- `favorite_count`：`integer`
- `view_count`：`integer`
- `status`：`keyword`
- `img_urls`：`keyword`
- `is_top`：`keyword`
- `title_suggest`：`completion`

注意：

- 新索引字段名统一使用 **下划线风格**，例如 `author_id`、`author_nickname`、`publish_time`
- 不再沿用旧搜索索引里的 camelCase 字段名，例如 `authorId`、`authorNickname`、`createTime`

### 固定写值规则

- `favorite_count = 0`
- `view_count = 0`
- `author_tag_json = null`
- `is_top = null`

## 7.3 IK 插件要求

ES 集群必须安装 `analysis-ik`。  
没有这个插件，就不要假装和目标系统一致。

## 7.4 查询 DSL 固定写法

新增或重写 ES 端口类：

- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/SearchEnginePort.java`

搜索 DSL 固定如下：

1. `multi_match`
   - `query = q`
   - `fields = ["title^3", "body"]`

2. `bool.filter`
   - `term status = published`
   - 若有 tags：`terms tags in {tags}`

3. `function_score`
   - `field_value_factor(like_count, log1p) weight=2.0`
   - `field_value_factor(view_count, log1p) weight=1.0`
   - `boost_mode = sum`

4. `highlight`
   - `title`
   - `body`

5. `sort`
   - `_score desc`
   - `publish_time desc`
   - `like_count desc`
   - `view_count desc`
   - `content_id desc`

6. `size = 请求参数 size`

7. `search_after`
   - 有 `after` 才带

8. `aggregations`
   - 新搜索链路**不做任何 aggregations**
   - 不返回 `facets`
   - 不再保留旧搜索的 `mediaType/postTypes` 聚合输出

## 7.5 游标编码规则固定写法

编码规则完全照目标系统：

1. 取最后一条 hit 的 `sort` 值数组  
2. 转成逗号拼接字符串  
3. 用 `Base64 URL Safe` 编码  
4. 去掉 padding  
5. 写到 `nextAfter`

解码规则固定如下：

- 第 1 个 sort 值：按 `double` 解析（`_score`）
- 第 2~5 个 sort 值：按 `long` 解析

不要自创 JSON cursor，也不要改成 pageNo。

## 7.6 高亮合并规则固定写法

1. 先取 `title` 高亮片段  
2. 再取 `body` 高亮片段  
3. 两段中间用一个空格拼接  
4. 若高亮为空，则 `description = description` 字段原值  
5. 若 `description` 也为空，则返回 `null`

---

## 8. 搜索结果字段如何从 Nexus 内容域映射，后续不得猜

## 8.1 文档组装规则

新增搜索文档组装逻辑时，固定按下面映射：

- `content_id = post.postId`
- `content_type = "POST"`
- `title = post.title`
- `description = post.summary`
- `body = post.contentText`
- `tags = post.postTypes`
- `author_id = post.userId`
- `author_avatar = user.avatarUrl`
- `author_nickname = user.nickname`
- `author_tag_json = null`
- `publish_time = post.publishTime`
- `like_count = reaction like count`
- `favorite_count = 0`
- `view_count = 0`
- `status = published / deleted`
- `img_urls = mediaInfo 解析结果`
- `is_top = null`
- `title_suggest = title`

## 8.1.1 Nexus 数值状态到 ES 字符串状态的映射，必须写死

- 当 `post.status == ContentPostStatusEnumVO.PUBLISHED.getCode()` 且 `post.visibility == ContentPostVisibilityEnumVO.PUBLIC.getCode()` 时：
  - `status = "published"`
- 其它所有情况：
  - `status = "deleted"`

注意：

- ES 里的 `status` 是字符串，不是 Nexus 的数值状态原样写入
- `post.deleted` 事件也统一落 `status = "deleted"`
- 不允许实现者自己发明第三种状态

## 8.2 `img_urls` 解析固定规则

当前项目里 `mediaInfo` 的现有语义是“逗号分隔 token/URL 列表”，见：

- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/ContentService.java`

固定解析规则：

1. `mediaInfo == null/blank` -> `img_urls = []`  
2. 按 `,` 分割  
3. `trim()` 后去掉空串  
4. 每个 token：
   - 如果以 `http://` 或 `https://` 开头，直接使用
   - 否则调用 `MediaStoragePort.generateReadUrl(token)` 转可读 URL
   - 若返回空，则保留原 token

复用类：

- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/MediaStoragePort.java`

## 8.3 用户态字段固定规则

- `liked`：复用现有点赞链路
- `faved`：固定 `false`

`liked` 的复用来源：

- 现有 `IReactionRepository.exists(...)`
- 现有 `IPostLikeCachePort.cacheState(...)`
- 现有 `ReactionLikeService` 的“先缓存、再 DB 回退”读法

实现时不要自己新造一套点赞状态存储。

---

## 9. 索引初始化与回灌，后续必须按这个流程落地

## 9.1 建索引

新增类：

- `project/nexus/nexus-app/src/main/java/cn/nexus/config/SearchIndexInitializer.java`

启动时：

1. 检查 `zhiguang_content_index` 是否存在  
2. 不存在则按第 7 章 Mapping 创建  
3. 任何异常只记录日志，不阻塞应用启动

配置键名固定规则：

- **沿用当前项目已有配置键名**：`search.es.indexAlias`
- 固定值改成：`zhiguang_content_index`
- 不要另起 `search.es.index`
- 虽然配置键名叫 `indexAlias`，但这次实现里**直接把它当实际索引名使用**
- 本次不做单独 alias 管理，不做 `alias -> concrete index` 双层切换

## 9.2 回灌触发规则

保留并重写类：

- `project/nexus/nexus-app/src/main/java/cn/nexus/config/SearchIndexBackfillRunner.java`

注意：

- 当前项目里已经有同名旧类
- 这次不是平行再建一份，而是**重写现有 `SearchIndexBackfillRunner`**

固定规则：

1. 应用启动后执行  
2. 先 `count(index)`  
3. 若 `count > 0`，直接跳过  
4. 若 `count == 0`，启动全量回灌  
5. 不要保留旧版“默认关闭、只靠手工开 `search.backfill.enabled` 才跑一次”的语义；如果保留这个配置，它只能作为紧急关闭开关，默认必须开启

## 9.3 回灌分页规则

固定使用：

- 批大小：`500`
- 顺序：按 `publish_time asc, post_id asc`
- 只回灌：`status = published` 且 `visibility = public`

如果某条 post：

- 没有 `title`：跳过并打告警日志
- 没有 `publish_time`：跳过并打告警日志
- 没有 `contentText`：允许写入，但 `body = ""`

因为当前组件里没有历史数据，所以这里的“没有 `title`”只表示开发/测试阶段脏数据，不表示要做历史回填兼容。

## 9.4 回灌数据来源

优先复用当前项目已经存在的批量读取能力：

- `IContentPostDao`：分页拉已发布公开内容
- `IContentPostTypeDao`：批量取 tags
- `IUserBaseDao`：批量取作者昵称/头像
- `IPostContentKvPort`：批量取正文
- `IReactionRepository` 或现有计数读取端口：取点赞数

不要为了“形式统一”强行把回灌塞进在线搜索服务；回灌是启动批任务，允许继续使用当前项目已有的 DAO/端口组合。

---

## 10. 增量索引链路，固定用现有 Outbox 复用，不新建 search_outbox

## 10.1 为什么必须复用现有 Outbox

当前项目已经有：

- `content_event_outbox`
- `user_event_outbox`

以及对应写入点：

- `IContentEventOutboxPort`
- `IUserEventOutboxPort`

所以这次**不要**再建 `search_event_outbox`。  
直接复用现有内容域 / 用户域 outbox，减少一套重复数据结构。

## 10.2 现有上游事件，固定使用这些

内容域事件：

- `post.published`
- `post.updated`
- `post.deleted`

用户域事件：

- `user.nickname_changed`

## 10.3 当前实现原则：不引入 Canal

原因很简单：

- 当前 `ContentService` / `UserService` 已经在事务内写 Outbox
- 提交后已经会 `tryPublishPending()`
- 当前项目已经有 `ContentEventOutboxRetryJob` / `UserEventOutboxRetryJob`
- `ContentEventOutboxPort` / `UserEventOutboxPort` 已经把 payload 反序列化成强类型事件，再发布到 RabbitMQ
- `SearchIndexConsumer` 现在监听的就是这些强类型事件

参考 `搜索系统全链路与复现方案.md` 可以知道，原始 zhiguang 开源链路里的 `Outbox -> Canal -> Kafka` 更像“预留方案”，而且还存在多事件类型混流风险。  
对 Nexus 来说，继续走现成的 `Outbox -> RabbitMQ` 更短、更稳，也更符合当前项目代码。

## 10.4 RabbitMQ Exchange / RoutingKey / Queue，写死

- Exchange：`social.feed`（direct）
- RoutingKey：`post.published` / `post.updated` / `post.deleted` / `user.nickname_changed`
- Queue：`search.post.published.queue`
- Queue：`search.post.updated.queue`
- Queue：`search.post.deleted.queue`
- Queue：`search.user.nickname_changed.queue`

不要再发明 `canal-outbox`、`search-cdc-topic`、`outbox-search-topic-v2`、`search-index-consumer-v2` 之类名字。

## 10.5 RabbitMQ 配置、发布端口与消费者位置

- 保留并改写，固定路径：

- `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/config/SearchIndexMqConfig.java`
- `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/SearchIndexConsumer.java`
- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/ContentEventOutboxPort.java`
- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/user/port/UserEventOutboxPort.java`
- `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/job/social/ContentEventOutboxRetryJob.java`
- `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/job/user/UserEventOutboxRetryJob.java`

## 10.6 RabbitMQ 消费规则，固定写法

### `post.published`

- 回表取最新 post
- 若 post 不存在 / 非 published / 非 public -> 写索引 `status=deleted`
- 否则组装完整文档并 upsert

### `post.updated`

- 规则与 `post.published` 一样

### `post.deleted`

- 不做物理 delete
- 固定改成 soft delete：写同一文档 ID，`status=deleted`

### `user.nickname_changed`

- 调用 `updateByQuery`
- 条件：`author_id = userId`
- 脚本：`author_nickname = 新昵称`

## 10.7 文档 ID 固定规则

- ES 文档 ID 固定：`String.valueOf(postId)`

不要再用旧搜索里的 `POST:{postId}`。

因为当前组件里没有历史数据、也没有旧索引，所以这里**不需要**任何文档 ID 兼容逻辑，也不需要双写两套 ID。

---

## 11. 新旧搜索文件改造清单，按模块拆给后续 Codex

## 11.1 `nexus-api`

### 必删

- 旧 `SearchGeneral*`
- 旧 `SearchSuggestRequestDTO`
- 旧 `SearchSuggestResponseDTO`
- 旧 `SearchTrending*`
- 旧 `SearchHistoryDeleteRequestDTO`

### 必新增

- `SearchResponseDTO`
- `SearchItemDTO`
- `SuggestResponseDTO`

### 必改

- `ISearchApi.java`

固定改成两个方法：

- `Response<SearchResponseDTO> search(String q, Integer size, String tags, String after)`
- `Response<SuggestResponseDTO> suggest(String prefix, Integer size)`

说明：

- 保留 `ISearchApi` 这个文件
- 搜索接口仍然使用 `Response<T>` 包装
- 变化点在于：内部 `data` 结构改成新的 `SearchResponseDTO` / `SuggestResponseDTO`

## 11.2 `nexus-trigger`

### 必改

- `SearchController.java`

固定路由：

- `@GetMapping("/search")`
- `@GetMapping("/search/suggest")`

### 必保留并改写

- `SearchIndexMqConfig.java`
- `SearchIndexConsumer.java`

### 非必新增

- 无。优先复用现有搜索 MQ 配置与消费者，不新增新的搜索消费者文件。

## 11.3 `nexus-domain`

### 必改

- `ISearchService.java`
- `SearchService.java`
- `SearchResultVO.java`（重写为新结构）
- `SearchSuggestVO.java`（重写为 `items`）
- `SearchDocumentVO.java`（重写为新 ES 文档结构）

### 必删

- `SearchTrendingVO.java`
- `ISearchHistoryRepository.java`
- `ISearchTrendingRepository.java`

## 11.4 `nexus-infrastructure`

### 必改

- `SearchEnginePort.java`

### 必删

- `SearchHistoryRepository.java`
- `SearchTrendingRepository.java`

### 必新增

- ES client 配置（若当前搜索专用 Bean 不够用）
- `SearchIndexInitializer.java`（若放 app 层则不在这里）
- 搜索回灌 runner 配套依赖

## 11.5 `nexus-app`

### 必改配置

- `application-dev.yml`

调整为：

```yaml
search:
  es:
    endpoints: http://127.0.0.1:9200
    indexAlias: zhiguang_content_index
  mq:
    concurrency: 2
    prefetch: 20
    retry:
      maxAttempts: 5
      initialIntervalMs: 1000
      multiplier: 3.0
      maxIntervalMs: 60000
  backfill:
    enabled: true
    pageSize: 500
    checkpoint:
      enabled: true
      redisKey: search:backfill:cursor
```

删除：

- `search.history.*`
- `search.trending.*`

---

## 12. 分阶段实现计划，后续 Codex 必须按顺序做

## 阶段 1：补标题与发布时间字段

目标：先把内容域补成“可被 zhiguang 搜索使用”的样子。

必须完成：

1. DB migration：补 `title` / `publish_time` / `snapshot_title`
2. 内容 DTO：补 `title`
3. 内容实体 / PO / Mapper：补 `title` / `publishTime`
4. 发布逻辑：写 `title`、写 `publish_time`
5. 草稿逻辑：保存 `title`
6. 详情接口：返回 `title`
7. 发布接口：校验 `title` 必填

验收：

- 新发一条 post，详情接口能看到 `title`
- DB 中 `content_post.title` 有值
- 真正发布后 `content_post.publish_time` 有值，不等于草稿初建时间
- `POST /api/v1/content/publish` 不传 `title` 会直接失败

## 阶段 2：替换对外搜索合同

目标：旧接口下线，新接口上线。

必须完成：

1. 删旧 DTO / 旧路由
2. 上新 DTO / 新路由
3. SearchController 改成 `q/size/tags/after` 与 `prefix/size`

验收：

- `GET /api/v1/search/general` 返回 404 或不再暴露
- `GET /api/v1/search/trending` 不再存在
- `DELETE /api/v1/search/history` 不再存在
- `GET /api/v1/search?q=xxx` 可正常进入新 Controller
- 搜索接口响应体继续套 `Response<T>`，但 `data` 改成新结构

## 阶段 3：替换读链到 ES zhiguang 语义

目标：搜索结果、排序、游标、联想全部切到 zhiguang 读法。

必须完成：

1. `SearchEnginePort` 改成新 DSL
2. `SearchService` 改成 `search_after` 读链
3. `suggest` 改成 completion suggester
4. 返回结构改成 `items + nextAfter + hasMore`

验收：

- 能按 `title` 和 `body` 命中
- `title` 权重大于 `body`
- `after` 翻页稳定，不重复、不漏项
- `suggest` 返回标题联想，不返回 Redis 热词

## 阶段 4：建索引 + 全量回灌

目标：空索引启动后能自举。

必须完成：

1. 建索引
2. count=0 时回灌
3. 从内容域回表 + 用户域查作者 + 点赞数

验收：

- 清空 ES 后重启应用，索引会被重新创建
- 已发布公开帖子都会进入索引
- 没有标题的测试脏数据不会进入索引，并有告警日志

## 阶段 5：改造现有增量链路到 Outbox -> RabbitMQ

目标：以后通过当前项目已经存在的 `Outbox -> RabbitMQ -> SearchIndexConsumer` 更新索引，不额外引入 Canal，也不再保留 Kafka 草案。

因为当前组件里没有历史消息和历史数据，这一阶段不需要考虑：

- 旧 RabbitMQ 搜索事件重放
- Canal 位点迁移
- 为 Kafka 额外保留兼容层

必须完成：

1. 保留内容域 / 用户域 Outbox 写入点
2. 保留 `afterCommit + retry job` 的投递机制
3. 改写 `SearchIndexMqConfig` 绑定搜索索引所需队列
4. 复用 `SearchIndexConsumer` 消费并更新 ES
5. 让 `ContentEventOutboxPort` / `UserEventOutboxPort` 继续发布强类型事件到 `social.feed`

验收：

- 发布帖子后，新增文档进入 ES
- 更新帖子标题后，ES title 同步更新
- 删除帖子后，ES 文档 `status=deleted`
- 改昵称后，历史搜索结果作者昵称同步更新

## 阶段 6：清理旧搜索遗留

目标：代码树里不留两套搜索系统并行。

必须完成：

1. 删除旧 Redis 搜索历史/热搜仓储
2. 删除旧 DTO/VO
3. 删除旧配置项
4. 清理 Kafka 方案残留命名与注释

验收：

- 全仓库搜索 `SearchTrending` / `SearchHistory` 不再有运行时代码引用
- 全仓库搜索索引增量链路只保留一套 RabbitMQ 实现，不再出现 Kafka 方案残留命名

---

## 13. 风险和必须提醒后续实现者的坑

## 13.1 不能漏掉 `title` 前置改造

如果直接开始写搜索，不补 `title`，后面一定会变成：

- suggest 没来源
- title 高亮失效
- title 权重失效
- 结果结构靠伪字段拼接

这是本方案明确禁止的。

## 13.2 不能拿 `create_time` 代替 `publish_time`

否则会出现：

- 草稿很早创建，今天才发布，却被当成老内容
- `search_after` 排序稳定性和用户预期不一致

## 13.3 不能保留旧 `/general` 再包一层新逻辑

用户已经选了 `1B`。  
所以这次允许破坏旧搜索 userspace，不允许再搞兼容壳。

## 13.4 不能把 `favoriteCount/faved/tagJson/isTop` 偷偷补成“猜测值”

固定规则已经写死：

- `favoriteCount = 0`
- `faved = false`
- `tagJson = null`
- `isTop = null`

不要再做任何“聪明猜测”。

## 13.5 不能新建 `search_event_outbox`

当前项目已经有：

- `content_event_outbox`
- `user_event_outbox`

继续新建搜索 outbox 只会制造重复链路和重复状态。

---

## 14. 最终交付检查单

后续真正改代码时，完成标准固定如下：

- 内容域已补 `title` 和 `publish_time`
- 新搜索接口为 `/api/v1/search` 和 `/api/v1/search/suggest`
- 旧 `/general`、`/trending`、`/history` 已删除
- 搜索接口继续返回 `Response<T>`，其中 `data` 为新的搜索结果结构
- 结果结构为 `items + nextAfter + hasMore`
- 搜索字段含 `title`、`body`，联想字段是 `title_suggest`
- ES 查询为 `multi_match + function_score + highlight + search_after`
- 空索引时能自动回灌
- 增量链路为 `Outbox -> RabbitMQ -> SearchIndexConsumer -> ES`
- `post.deleted` 走 soft delete
- `user.nickname_changed` 能批量回写索引作者昵称
- 代码树里不再并行存在旧 Search Redis 实现，也不再出现 Kafka 版搜索增量链路残留

---

## 15. 给后续 Codex 的执行顺序（最短版）

如果你是后续执行这个方案的 Codex，固定按下面顺序做：

1. 先改内容域：补 `title` / `publish_time`  
2. 再改搜索 API 合同  
3. 再改搜索读链  
4. 再加 ES 建索引和回灌  
5. 再接 RabbitMQ 索引消费者  
6. 最后删旧 Search Redis 与 Kafka 草案残留  

不要反过来。  
先删旧搜索、后补标题字段，会把自己困死。
