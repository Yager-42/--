# 两级评论“盖楼”系统（可照抄实现文档）

执行者：Codex（Linus mode）  
日期：2026-01-14  
适用形态：B站/抖音式 **两级展示**（一级评论 + 楼内回复扁平展示）

---

## 0. 已确认参数（实现不得偏离）

- 展示形态：两级展示（楼内所有回复统一归属同一个一级评论）
- 热榜范围：只排一级评论
- `root_id` 取值：一级评论为 `NULL`；回复为所属一级评论的 `comment_id`
- 点赞范围（已拍板）：只允许给**一级评论**点赞；楼内回复**不允许**被点赞
- 置顶规则（已拍板）：每个 `post_id` 只允许 1 条置顶，且只能置顶一级评论；置顶永远在最上面，但**不参与分页**（接口返回 `pinned` 单独字段，`items` 不包含置顶）
- 交付范围（已拍板）：除写入外，必须补齐读侧与删除接口：一级评论列表 / 楼内回复列表 / 热榜 / 软删（幂等）
- 读侧返回（已拍板）：所有读接口的评论条目必须包含作者 `nickname/avatarUrl`（写库只存 `userId`，读侧批量补全）

---

## 1. 数据模型（MySQL 真值）

### 1.1 表：`interaction_comment`（本仓库实际表名）

> 只要这张表的 `root_id` 语义写对，读侧永远不需要递归。

最小字段（建议保留）：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `comment_id` | BIGINT PK | 评论 ID |
| `post_id` | BIGINT NOT NULL | 归属内容/帖子 |
| `user_id` | BIGINT NOT NULL | 评论作者 |
| `root_id` | BIGINT NULL | 一级评论为 NULL；回复为一级评论 ID |
| `parent_id` | BIGINT NULL | 直接回复的评论 ID（仅展示/定位） |
| `reply_to_id` | BIGINT NULL | 显示“回复@谁”的目标评论 ID（仅展示） |
| `content` | LONGTEXT NOT NULL | 可包含结构化 @ 片段 |
| `status` | TINYINT NOT NULL | 1=正常；2=删除（软删） |
| `like_count` | BIGINT NOT NULL | 一级评论点赞数（最终一致即可） |
| `reply_count` | BIGINT NOT NULL | 一级评论回复数（最终一致即可） |
| `create_time` | DATETIME NOT NULL | 创建时间 |
| `update_time` | DATETIME NOT NULL | 更新时间 |

> 注意：`like_count/reply_count` 只要求“最终一致”，不要把它当银行余额。

可复制 DDL（最小可用，字段可按业务再扩展）：

```sql
CREATE TABLE `interaction_comment` (
  `comment_id` BIGINT NOT NULL,
  `post_id` BIGINT NOT NULL,
  `user_id` BIGINT NOT NULL,
  `root_id` BIGINT NULL,
  `parent_id` BIGINT NULL,
  `reply_to_id` BIGINT NULL,
  `content` LONGTEXT NOT NULL,
  `status` TINYINT NOT NULL,
  `like_count` BIGINT NOT NULL DEFAULT 0,
  `reply_count` BIGINT NOT NULL DEFAULT 0,
  `create_time` DATETIME NOT NULL,
  `update_time` DATETIME NOT NULL,
  PRIMARY KEY (`comment_id`),
  INDEX `idx_post_root_time` (`post_id`, `root_id`, `create_time`, `comment_id`),
  INDEX `idx_root_time` (`root_id`, `create_time`, `comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 1.2 必要索引（不然你就是在等死）

- `idx_post_root_time`：`(post_id, root_id, create_time, comment_id)`
- `idx_root_time`：`(root_id, create_time, comment_id)`（楼内回复分页会用到）

### 1.3 数据不变量（落库必须保证）

- 一级评论：`root_id IS NULL` 且 `parent_id IS NULL`
- 回复：`root_id IS NOT NULL` 且 `parent_id IS NOT NULL`
- 任何回复都满足：`root_id` 指向“那条一级评论”的 `comment_id`
- 禁止跨帖子串楼：`parent.post_id == post_id`

### 1.4 表：`interaction_comment_pin`（单帖单置顶）

> 置顶不应该靠“在评论表上加一个 is_pinned 字段”去凑合。你要的是“每个 post 只能置顶 1 条”的数据约束，应该让 **唯一键** 来保证。

最小字段（建议保留）：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `post_id` | BIGINT PK | 帖子 ID（唯一：一帖一条置顶） |
| `comment_id` | BIGINT NOT NULL | 被置顶的一级评论 ID |
| `create_time` | DATETIME NOT NULL | 创建时间 |
| `update_time` | DATETIME NOT NULL | 更新时间 |

可复制 DDL（最小可用）： 

```sql
CREATE TABLE `interaction_comment_pin` (
  `post_id` BIGINT NOT NULL,
  `comment_id` BIGINT NOT NULL,
  `create_time` DATETIME NOT NULL,
  `update_time` DATETIME NOT NULL,
  PRIMARY KEY (`post_id`),
  INDEX `idx_comment_id` (`comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

数据不变量（必须保证）：

- 一帖只允许 1 条置顶：由 `PRIMARY KEY(post_id)` 保证
- 只能置顶一级评论：`comment.root_id IS NULL` 且 `comment.post_id == post_id`
- 若置顶评论被软删，必须同步删除该帖的置顶记录（否则读侧会返回脏 `pinned`）

---

## 2. 写入链路（核心：root_id 计算一次算对）

### 2.1 创建评论（一级/回复统一入口）

pseudocode（领域服务，保持一层 if/else）：

```
createComment(postId, userId, parentId, content, mentions):
  nowMs = clock.nowMs()
  commentId = idPort.nextId()

  if parentId is null:
    // 一级评论
    rootId = null
    parentIdToSave = null
    replyToId = null
  else:
    // 回复（楼内扁平化）
    parent = commentRepo.getBrief(parentId) // {commentId, postId, rootId, status}
    assert parent.postId == postId
    assert parent.status == NORMAL // 不允许回复已删评论

    rootId = (parent.rootId == null) ? parent.commentId : parent.rootId
    parentIdToSave = parent.commentId
    replyToId = parent.commentId

  // status=1(NORMAL) 固定由仓储层写入；这里别把 status 当成可变入参
  commentRepo.insert(commentId, postId, userId, rootId, parentIdToSave, replyToId, content, nowMs)

  // 异步化：计数/热榜/@通知都别阻塞主链路
  mq.publish(CommentCreatedEvent(commentId, postId, rootId, userId, nowMs))
  if parentId != null:
    mq.publish(RootReplyCountChangedEvent(rootId, postId, delta=+1, nowMs))
  mentionedUserIds = mentions
  if mentionedUserIds not empty:
    mq.publish(MentionedUsersEvent(commentId, postId, rootId, mentionedUserIds, nowMs))

  return commentId
```

### 2.2 删除评论（软删）

规则（最小可用，已拍板）：

- 只做软删：把该行 `status` 从 `1` 改为 `2`，更新 `update_time`；不做物理删除
- 幂等：如果该评论已是删除状态，直接返回（不要重复扣计数）
- 不允许回复已删评论：写入时要求 `parent.status == NORMAL`
- 删除回复（`root_id IS NOT NULL`）：**需要把所属一级评论的 `reply_count` 减 1**
  - 触发事件：`RootReplyCountChangedEvent(rootCommentId=root_id, postId, delta=-1, tsMs)`
- 删除一级评论（`root_id IS NULL`）：热榜只排一级评论，因此需要从热榜移除
  - Redis：`ZREM comment:hot:{postId} comment_id`
  - 若该一级评论是置顶：必须清理置顶（删除 `interaction_comment_pin` 的该 `post_id` 记录）
  - 楼内回复：保持可见（UI 显示“一级评论已删除”的占位即可）

pseudocode：

```
deleteComment(commentId):
  nowMs = clock.nowMs()
  c = commentRepo.getBrief(commentId) // {commentId, postId, rootId, status}
  if c == null: return
  // 幂等必须以“写入成功”为准：只有 status:1->2 成功，才允许扣减 reply_count
  deleted = commentRepo.softDelete(commentId, nowMs) // returns true only when status 1->2
  if !deleted: return

  if c.rootId != null:
    mq.publish(RootReplyCountChangedEvent(c.rootId, c.postId, delta=-1, tsMs=nowMs))
  else:
    redis.zrem("comment:hot:" + c.postId, c.commentId)
    pinRepo.clearIfPinned(c.postId, c.commentId)
```

---

## 3. 读链路（时间序 + 楼内分页 + 预加载）

### 3.1 一级评论列表（按时间，游标分页）

游标建议：`{createTimeMs}:{commentId}`（避免同一时间戳重复/漏页）

```sql
SELECT * FROM interaction_comment
WHERE post_id = :postId
  AND root_id IS NULL
  AND status IN (1, 2)
  AND (:pinnedId IS NULL OR comment_id <> :pinnedId)
  AND (create_time < :cursorTime OR (create_time = :cursorTime AND comment_id < :cursorId))
ORDER BY create_time DESC, comment_id DESC
LIMIT :limit;
```

> 说明：一级评论列表建议包含 `status=2` 的占位评论（否则楼内回复会变“孤儿”）。读侧返回 DTO 时：`status=2` 的一级评论 `content` 置空/占位文案，不要把原内容吐给前端。

### 3.2 楼内回复列表（按时间正序，游标分页）

```sql
SELECT * FROM interaction_comment
WHERE root_id = :rootId
  AND status = 1
  AND (create_time > :cursorTime OR (create_time = :cursorTime AND comment_id > :cursorId))
ORDER BY create_time ASC, comment_id ASC
LIMIT :limit;
```

### 3.3 预加载（两级 UI 常用）

典型策略：一级评论列表每条预加载前 `N` 条回复（例如 3 条）。

最简单实现：

1) 先查一级评论分页（limit=20）  
2) 对每个一级评论 `rootCommentId`：查 `root_id = rootCommentId` 的前 3 条回复  

> 这是 1 + N 次查询；先跑起来，再决定要不要做批量化优化。

### 3.4 置顶（Pinned）：不参与分页（已拍板）

规则（必须执行）：

- 置顶单独返回：一级评论列表 / 热榜接口返回 `pinned` 字段；`items` **不包含**置顶评论
- 置顶只允许一级评论（`root_id IS NULL`）
- 置顶评论被软删/不存在：读侧应清理 `interaction_comment_pin`（避免长期返回脏置顶）

读侧最小实现（先能跑，再谈优化）：

1) 查置顶 `comment_id`：`interaction_comment_pin.post_id -> comment_id`  
2) 若存在：回表拿置顶评论详情（要求 `post_id` 匹配且 `root_id IS NULL`）  
3) 查一级评论分页时把 `pinnedId` 传入 3.1 的 SQL，并排除 `pinnedId`（避免重复）  
4) 预加载回复：建议对 `pinned` 也预加载前 `N` 条回复（与普通一级评论体验一致）

SQL 示例：

```sql
SELECT comment_id FROM interaction_comment_pin WHERE post_id = :postId;

SELECT * FROM interaction_comment
WHERE comment_id = :pinnedId
  AND post_id = :postId
  AND root_id IS NULL
  AND status = 1;
```

> 热榜读取时，如果 `pinnedId` 出现在 Redis ZSET 返回的 `commentIds` 中，读侧需要剔除（避免同一条评论重复展示）。

---

## 4. 热榜（只排一级评论：Redis ZSet）

### 4.1 Redis Key

- Key：`comment:hot:{postId}`（ZSET）
- member：`comment_id`（一级评论）

### 4.2 Score 口径（先能跑，再谈高级）

最小可用：

- `score = like_count * 10 + reply_count * 20`

可选（需要周期性重算或引入时间项）：

- `score = like_count * 10 + reply_count * 20 - ageHours`

### 4.3 读热榜（先拿 ID 再回表）

1) Redis：`ZREVRANGE comment:hot:{postId} 0 (limit-1)` 拿到 `commentIds`  
2) MySQL：`WHERE comment_id IN (...)` 回表拿详情  
3) 按 Redis 返回的 ID 顺序重排（避免 IN 查询顺序漂移）

### 4.4 更新热榜（全部异步）

热榜只需要在这些时刻更新分数：

- 一级评论创建完成
- 一级评论 `like_count` 变化
- 一级评论 `reply_count` 变化

---

## 5. MQ 事件契约（应用内必须固定）

> 这里只定义“事件名 + 最小字段 + 消费者职责”。实现可以复用你仓库现有 MQ 风格。

### 5.0 RabbitMQ 拓扑（推荐命名，可直接照抄）

- Exchange（Direct）：`social.interaction`
- RoutingKey → Queue（建议一事件一队列，解耦消费端扩展）：
  - `comment.created` → `interaction.comment.created.queue`
  - `comment.reply_count.changed` → `interaction.comment.reply_count.changed.queue`
  - `comment.like.changed` → `interaction.comment.like.changed.queue`（可选：如果你把点赞也接进热榜）
  - `comment.mentioned` → `interaction.comment.mentioned.queue`
- 消息体：建议用 `nexus-types` 的事件类（继承 `cn.nexus.types.event.BaseEvent`，天然 Serializable），生产端直接 `rabbitTemplate.convertAndSend(exchange, routingKey, event)`

### 5.1 CommentCreatedEvent

字段：
- `commentId`
- `postId`
- `rootId`（NULL 表示一级评论；非 NULL 表示回复归属的一级评论 ID）
- `userId`
- `createTimeMs`

消费者职责（建议）：
- 若 `rootId == NULL`：把该一级评论加入 `comment:hot:{postId}`，初始化 score

### 5.2 RootReplyCountChangedEvent

字段：
- `rootCommentId`
- `postId`
- `delta`（+1=新增回复；-1=删除回复）
- `tsMs`

消费者职责：
- MySQL：`reply_count = reply_count + delta`（只更新一级评论那行）
- Redis：重算该一级评论 score 并 `ZADD` 更新

### 5.3 CommentLikeChangedEvent（一级评论点赞 -> like_count -> 热榜）

字段：
- `rootCommentId`
- `postId`
- `delta`（+1/-1）
- `tsMs`

消费者职责：
- MySQL：`like_count = like_count + delta`
- Redis：重算 score 并更新 ZSet

约束（已拍板）：

- 只允许一级评论点赞：事件必须只针对一级评论（`root_id IS NULL`）
- `rootCommentId` 就是一级评论 ID（不要发“楼内回复点赞”的事件）

> 本仓库已存在点赞链路（targetType=COMMENT）。建议在“点赞最终一致同步完成”后投递该事件，由本消费者回写 `interaction_comment.like_count` 并刷新 `comment:hot:{postId}`。

### 5.4 MentionedUsersEvent

字段：
- `commentId`
- `postId`
- `rootId`
- `mentionedUserIds`（数组）
- `tsMs`

消费者职责：
- 通知服务消费并发送站内信/Push（不要阻塞“发评论”接口）

---

## 6. 在本仓库的落地点（给下一个 Codex agent）

### 6.0 接口口径（和《社交接口.md》对齐，避免实现歧义）

- 路径：trigger 层 Controller 实际挂载在 `/api/v1`；《社交接口.md》对外统一写 `/v1/...`。如果你没有网关做前缀改写，直接把 `/v1` 替换为 `/api/v1` 即可。
- 用户身份：统一从 Header `X-User-Id: <Long>` 获取；不要在 DTO 里传 `userId`。
- 字段命名：当前仓库未看到 Jackson 的 snake_case 全局配置，所以 HTTP JSON 默认按 **camelCase**（对齐 `nexus-api` 的 DTO 字段名）。《社交接口.md》里的 snake_case 仅用于文档表达。
- 响应包装：所有接口返回 `cn.nexus.api.response.Response<T>`，业务数据在 `data` 字段。

响应示例（成功）： 

```json
{
  "code": "0000",
  "info": "成功",
  "data": {}
}
```

### 6.1 新增读接口的 DTO 结构（最小可用，不然前端没法用）

> 你现在的文档只有 `items(List)` 这种“空气契约”，实现时一定会各写各的，最后互相对不上。下面给一份最小可用结构，先跑起来再扩展字段。

建议新增一个统一的展示 DTO（字段命名按 camelCase）：`CommentViewDTO`：
- `commentId`、`postId`、`userId`
- `nickname`、`avatarUrl`（必须返回；从用户基础信息批量补全，不建议落进评论表做冗余）
- `rootId`（一级为 null；回复为所属一级 commentId）
- `parentId`、`replyToId`
- `content`、`status`（1=正常；2=删除）
- `likeCount`（仅一级评论有意义；回复恒为 0/不返回均可）
- `replyCount`（仅一级评论有意义）
- `createTime`

一级评论列表/热榜的 `pinned/items` 建议用 `RootCommentViewDTO`（包含回复预览）：
- `root`：`CommentViewDTO`
- `repliesPreview`：`List<CommentViewDTO>`（长度 ≤ `preloadReplyLimit`，按时间正序）

用户信息补全（必须做，不然你会写出 N+1 查询的垃圾）：

```
enrichUserProfile(pinned, items):
  userIds = distinct(all CommentViewDTO.userId from pinned + items + repliesPreview)
  profiles = userBaseRepo.listByUserIds(userIds) // List<UserBriefVO>
  profileMap = toMap(profiles) // Map<Long, {nickname, avatarUrl}>
  for each comment in pinned/items/repliesPreview:
    p = profileMap.get(comment.userId)
    comment.nickname = p == null ? "" : p.nickname
    comment.avatarUrl = p == null ? "" : p.avatarUrl
```

数据来源建议（最小可用）：MySQL `user_base(user_id, username, avatar_url)`（见 `社交领域数据库.md`）；接口层字段命名为 `nickname/avatarUrl`，其中 `nickname = user_base.username`。

### 6.2 点赞约束落点（已拍板：只允许一级评论点赞）

约束不是写在文档里就自动生效的，你得在点赞链路里把它“卡死”：

```
validateCommentLike(targetCommentId):
  c = commentRepo.getBrief(targetCommentId)
  assert c != null
  assert c.status == NORMAL
  assert c.rootId == null  // 只允许一级评论点赞
```

推荐落点：点赞 domain 服务在 `targetType=COMMENT` 时，进入 Redis 写入前先做一次 `commentRepo.getBrief` 校验；校验失败直接抛 `ILLEGAL_PARAMETER`。

已存在：
- `POST /api/v1/interact/comment`（入口：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/InteractionController.java`）
- `POST /api/v1/interact/comment/pin`（入口同上；当前 `pinComment` 是占位实现）
- `CommentRequestDTO`（当前字段：`postId/parentId/content/mentions`）
- `PinCommentRequestDTO`（当前字段：`commentId/postId`）
- `InteractionService.comment` / `InteractionService.pinComment` 目前是占位实现

必须新增（按需求：读侧 + 删除 + 置顶不参与分页）：

- `GET /api/v1/comment/list`：一级评论列表（返回 `pinned` + `items` + `nextCursor`；`items` 不包含置顶）
- `GET /api/v1/comment/reply/list`：楼内回复列表（按时间正序，返回 `items` + `nextCursor`）
- `GET /api/v1/comment/hot`：评论热榜（只排一级评论；返回 `pinned` + `items`）
- `DELETE /api/v1/comment/{commentId}`：软删（幂等）

HTTP 契约（建议直接照抄到 `nexus-api` 的 DTO；游标规则见 3.1/3.2）：

| 接口名称 | Method | Path | 请求参数 | 响应数据 |
| --- | --- | --- | --- | --- |
| 一级评论列表（含置顶/预加载回复） | GET | `/api/v1/comment/list` | `postId`, `cursor` (Optional), `limit` (Int), `preloadReplyLimit` (Optional) | `pinned` (Optional), `items` (List), `nextCursor` |
| 楼内回复列表 | GET | `/api/v1/comment/reply/list` | `rootId`, `cursor` (Optional), `limit` (Int) | `items` (List), `nextCursor` |
| 评论热榜（只排一级评论） | GET | `/api/v1/comment/hot` | `postId`, `limit` (Int) | `pinned` (Optional), `items` (List) |
| 删除评论（软删/幂等） | DELETE | `/api/v1/comment/{commentId}` | - | `success` (Bool) |

约束（和接口强绑定，别“实现时再想”）：

- `pinned` 永远不计入 `items` 与 `limit`（否则分页会重复/漏页）
- `cursor/nextCursor` 永远只基于 `items`（不基于 `pinned`）

实现前必须解决的阻塞点：
- 服务端必须能拿到 `userId`（否则无法落库评论作者）。**`userId` 从登录态/网关上下文注入**，不允许从 `CommentRequestDTO` 传入。
  - 网关约定：每个请求携带 Header `X-User-Id: <Long>`（把它当真值，不做任何安全校验/签名校验）。
  - trigger 层提供 `UserContext`：从请求上下文取 `X-User-Id`，Controller 调用 `UserContext.requireUserId()` 再把 `userId` 传入 domain 服务。
    - 建议位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/support/UserContext.java`
    - 行为：缺 header 直接抛 `AppException(ResponseCode.ILLEGAL_PARAMETER)`（别写 fallback，问题就应该暴露）

建议新增（按 DDD 分层）：

- domain：`CommentService`（或扩展 `InteractionService`）负责 root_id 计算与事件发布
- infrastructure：`CommentRepository`（MyBatis）+ `CommentHotRankRepository`（Redis ZSet）
- trigger：MQ 配置 + Consumer（reply_count/like_count/hot rank/@通知）

---

## 7. 逐文件照抄清单（按这个顺序做，不会跑偏）

> 目标：让另一个 Codex agent **不看你项目现状**，只照本文就能把“两级评论写入 + 读接口 + 删除 + 置顶 + 热榜 + @通知事件”跑起来。

### 7.1 Step 0：准备工作（别跳）

- MySQL：执行 1.1 的 `interaction_comment` DDL + 1.4 的 `interaction_comment_pin` DDL（或用你项目的迁移工具建表）
- Redis：可用（用于热榜 ZSET）
- RabbitMQ：可用（用于异步计数/热榜/@通知）
> 注意：本仓库没有统一的全局异常处理（`@ControllerAdvice`），Controller 一般不 try/catch。
> 你要么：在 Controller 显式返回 `ResponseCode.ILLEGAL_PARAMETER`，要么接受“缺 header 时直接 500”。本文给的是“Controller 显式返回错误”的做法（用户可见更稳定）。

### 7.2 Step 1：types（事件类，给 MQ 消息体用）

新增目录：`project/nexus/nexus-types/src/main/java/cn/nexus/types/event/interaction/`

#### 7.2.1 `CommentCreatedEvent.java`

文件：`project/nexus/nexus-types/src/main/java/cn/nexus/types/event/interaction/CommentCreatedEvent.java`

```java
package cn.nexus.types.event.interaction;

import cn.nexus.types.event.BaseEvent;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 评论创建事件：用于异步热榜初始化、通知等。
 *
 * <p>rootId 为 NULL 表示一级评论；非 NULL 表示楼内回复归属的一级评论 ID。</p>
 *
 * @author codex
 * @since 2026-01-14
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class CommentCreatedEvent extends BaseEvent {
    private Long commentId;
    private Long postId;
    private Long rootId;
    private Long userId;
    private Long createTimeMs;
}
```

#### 7.2.2 `RootReplyCountChangedEvent.java`

文件：`project/nexus/nexus-types/src/main/java/cn/nexus/types/event/interaction/RootReplyCountChangedEvent.java`

```java
package cn.nexus.types.event.interaction;

import cn.nexus.types.event.BaseEvent;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 一级评论回复数变更事件：只更新一级评论的 reply_count，并驱动热榜更新。
 *
 * @author codex
 * @since 2026-01-14
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class RootReplyCountChangedEvent extends BaseEvent {
    private Long rootCommentId;
    private Long postId;
    private Long delta;
    private Long tsMs;
}
```

#### 7.2.3 `CommentLikeChangedEvent.java`（可选）

文件：`project/nexus/nexus-types/src/main/java/cn/nexus/types/event/interaction/CommentLikeChangedEvent.java`

```java
package cn.nexus.types.event.interaction;

import cn.nexus.types.event.BaseEvent;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 一级评论点赞数变更事件：可选（如果你把点赞链路的结果回写到评论热榜）。
 *
 * @author codex
 * @since 2026-01-14
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class CommentLikeChangedEvent extends BaseEvent {
    private Long rootCommentId;
    private Long postId;
    private Long delta;
    private Long tsMs;
}
```

#### 7.2.4 `MentionedUsersEvent.java`（可选）

文件：`project/nexus/nexus-types/src/main/java/cn/nexus/types/event/interaction/MentionedUsersEvent.java`

```java
package cn.nexus.types.event.interaction;

import cn.nexus.types.event.BaseEvent;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * @ 提及事件：异步通知，不阻塞“发评论”接口。
 *
 * @author codex
 * @since 2026-01-14
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class MentionedUsersEvent extends BaseEvent {
    private Long commentId;
    private Long postId;
    private Long rootId;
    private List<Long> mentionedUserIds;
    private Long tsMs;
}
```

### 7.3 Step 2：domain（仓储接口 + 值对象）

#### 7.3.1 新增值对象：`CommentBriefVO`

文件：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/model/valobj/CommentBriefVO.java`

```java
package cn.nexus.domain.social.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 评论最小信息：用于 rootId 计算与幂等删除判定（避免读整行）。
 *
 * @author codex
 * @since 2026-01-14
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CommentBriefVO {
    private Long commentId;
    private Long postId;
    private Long rootId;
    private Integer status;
    private Long likeCount;
    private Long replyCount;
}
```

#### 7.3.2 新增仓储接口：`ICommentRepository`

文件：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/adapter/repository/ICommentRepository.java`

```java
package cn.nexus.domain.social.adapter.repository;

import cn.nexus.domain.social.model.valobj.CommentBriefVO;
import java.util.List;

/**
 * 评论仓储接口：封装 MySQL interaction_comment 表读写。
 *
 * @author codex
 * @since 2026-01-14
 */
public interface ICommentRepository {

    CommentBriefVO getBrief(Long commentId);

    void insert(Long commentId, Long postId, Long userId, Long rootId, Long parentId, Long replyToId, String content, Long nowMs);

    /**
     * 软删（幂等）：仅当 status=1 时更新为 2。
     *
     * @return true=本次完成从 1->2 的状态变更；false=已删除或不存在
     */
    boolean softDelete(Long commentId, Long nowMs);

    void addReplyCount(Long rootCommentId, Long delta);

    void addLikeCount(Long rootCommentId, Long delta);

    /**
     * 一级评论分页（时间倒序，游标分页）。cursor 为空表示从最新开始。
     */
    List<Long> pageRootCommentIds(Long postId, String cursor, int limit);

    /**
     * 楼内回复分页（时间正序，游标分页）。cursor 为空表示从最早开始。
     */
    List<Long> pageReplyCommentIds(Long rootId, String cursor, int limit);
}
```

#### 7.3.3 新增仓储接口：`ICommentHotRankRepository`（Redis ZSET）

文件：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/adapter/repository/ICommentHotRankRepository.java`

```java
package cn.nexus.domain.social.adapter.repository;

import java.util.List;

/**
 * 评论热榜仓储接口：封装 Redis ZSET。
 *
 * <p>Key：comment:hot:{postId}，member=commentId（一级评论），score=热度分数。</p>
 *
 * @author codex
 * @since 2026-01-14
 */
public interface ICommentHotRankRepository {
    void upsert(Long postId, Long rootCommentId, double score);
    void remove(Long postId, Long rootCommentId);
    List<Long> topIds(Long postId, int limit);
}
```

#### 7.3.4 新增仓储接口：`ICommentPinRepository`（单帖单置顶）

文件：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/adapter/repository/ICommentPinRepository.java`

```java
package cn.nexus.domain.social.adapter.repository;

/**
 * 评论置顶仓储接口：一帖仅一条置顶。
 *
 * <p>注意：置顶不参与分页，读侧必须返回 pinned 字段，items 不包含置顶。</p>
 *
 * @author codex
 * @since 2026-01-14
 */
public interface ICommentPinRepository {

    /**
     * 查询某帖的置顶评论 ID。
     *
     * @param postId 帖子 ID {@link Long}
     * @return 置顶的一级评论 ID {@link Long}，无则返回 {@code null}
     */
    Long getPinnedCommentId(Long postId);

    /**
     * 置顶（upsert）：把某条一级评论设置为该帖唯一置顶。
     *
     * @param postId    帖子 ID {@link Long}
     * @param commentId 一级评论 ID {@link Long}
     * @param nowMs     当前毫秒时间戳 {@link Long}
     */
    void pin(Long postId, Long commentId, Long nowMs);

    /**
     * 取消置顶：清理该帖的置顶记录。
     *
     * @param postId 帖子 ID {@link Long}
     */
    void clear(Long postId);

    /**
     * 若该帖当前置顶等于 commentId，则清理（用于“删评论”链路避免脏置顶）。
     *
     * @param postId    帖子 ID {@link Long}
     * @param commentId 评论 ID {@link Long}
     */
    void clearIfPinned(Long postId, Long commentId);
}
```

#### 7.3.5 新增端口：`ICommentEventPort`（发布 MQ 事件）

文件：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/adapter/port/ICommentEventPort.java`

```java
package cn.nexus.domain.social.adapter.port;

import cn.nexus.types.event.interaction.CommentCreatedEvent;
import cn.nexus.types.event.interaction.CommentLikeChangedEvent;
import cn.nexus.types.event.interaction.MentionedUsersEvent;
import cn.nexus.types.event.interaction.RootReplyCountChangedEvent;

/**
 * 评论事件发布端口：domain 只调用端口，不依赖 RabbitTemplate。
 *
 * @author codex
 * @since 2026-01-14
 */
public interface ICommentEventPort {
    void publish(CommentCreatedEvent event);
    void publish(RootReplyCountChangedEvent event);
    void publish(CommentLikeChangedEvent event);
    void publish(MentionedUsersEvent event);
}
```

#### 7.3.6 新增值对象：`UserBriefVO`（读侧补全 nickname/avatar 用）

文件：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/model/valobj/UserBriefVO.java`

```java
package cn.nexus.domain.social.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户最小展示信息：给评论/通知等读接口补全 nickname/avatar。
 *
 * <p>注意：当前建议从 user_base 表读取，其中 nickname = username。</p>
 *
 * @author codex
 * @since 2026-01-20
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserBriefVO {
    private Long userId;
    private String nickname;
    private String avatarUrl;
}
```

#### 7.3.7 新增仓储接口：`IUserBaseRepository`（批量查用户昵称/头像）

文件：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/adapter/repository/IUserBaseRepository.java`

```java
package cn.nexus.domain.social.adapter.repository;

import cn.nexus.domain.social.model.valobj.UserBriefVO;
import java.util.List;

/**
 * 用户基础信息仓储：给读接口补全 nickname/avatar。
 *
 * <p>必须是批量接口，禁止对评论列表做 N+1 单查。</p>
 *
 * @author codex
 * @since 2026-01-20
 */
public interface IUserBaseRepository {

    /**
     * 批量查询用户基础信息（不存在的 userId 直接忽略）。
     *
     * @param userIds 用户 ID 列表
     * @return 用户基础信息列表
     */
    List<UserBriefVO> listByUserIds(List<Long> userIds);
}
```

### 7.4 Step 3：infrastructure（MyBatis DAO/XML + Redis + MQ port 实现）

#### 7.4.1 MyBatis PO：`CommentPO`

文件：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/dao/social/po/CommentPO.java`

```java
package cn.nexus.infrastructure.dao.social.po;

import java.util.Date;
import lombok.Data;

/**
 * 评论持久化对象，对应 interaction_comment。
 *
 * @author codex
 * @since 2026-01-14
 */
@Data
public class CommentPO {
    private Long commentId;
    private Long postId;
    private Long userId;
    private Long rootId;
    private Long parentId;
    private Long replyToId;
    private String content;
    private Integer status;
    private Long likeCount;
    private Long replyCount;
    private Date createTime;
    private Date updateTime;
}
```

#### 7.4.2 MyBatis DAO：`ICommentDao`

文件：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/dao/social/ICommentDao.java`

```java
package cn.nexus.infrastructure.dao.social;

import cn.nexus.infrastructure.dao.social.po.CommentPO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ICommentDao {

    int insert(CommentPO po);

    CommentPO selectBriefById(@Param("commentId") Long commentId);

    int softDelete(@Param("commentId") Long commentId, @Param("updateTime") java.util.Date updateTime);

    int addReplyCount(@Param("commentId") Long commentId, @Param("delta") Long delta);

    int addLikeCount(@Param("commentId") Long commentId, @Param("delta") Long delta);

    List<Long> pageRootIds(@Param("postId") Long postId,
                           @Param("cursorTime") java.util.Date cursorTime,
                           @Param("cursorId") Long cursorId,
                           @Param("limit") Integer limit);

    List<Long> pageReplyIds(@Param("rootId") Long rootId,
                            @Param("cursorTime") java.util.Date cursorTime,
                            @Param("cursorId") Long cursorId,
                            @Param("limit") Integer limit);
}
```

#### 7.4.3 MyBatis XML：`CommentMapper.xml`

文件：`project/nexus/nexus-infrastructure/src/main/resources/mapper/social/CommentMapper.xml`

```xml
<?xml version="1.0" encoding="UTF-8" ?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="cn.nexus.infrastructure.dao.social.ICommentDao">

    <insert id="insert" parameterType="cn.nexus.infrastructure.dao.social.po.CommentPO">
        INSERT INTO interaction_comment(comment_id, post_id, user_id, root_id, parent_id, reply_to_id, content, status, like_count, reply_count, create_time, update_time)
        VALUES(#{commentId}, #{postId}, #{userId}, #{rootId}, #{parentId}, #{replyToId}, #{content}, #{status}, #{likeCount}, #{replyCount}, #{createTime}, #{updateTime})
    </insert>

    <select id="selectBriefById" resultType="cn.nexus.infrastructure.dao.social.po.CommentPO">
        SELECT comment_id, post_id, root_id, status, like_count, reply_count
        FROM interaction_comment
        WHERE comment_id = #{commentId}
        LIMIT 1
    </select>

    <update id="softDelete">
        UPDATE interaction_comment
        SET status = 2, update_time = #{updateTime}
        WHERE comment_id = #{commentId} AND status = 1
    </update>

    <update id="addReplyCount">
        UPDATE interaction_comment
        SET reply_count = reply_count + #{delta}, update_time = NOW()
        WHERE comment_id = #{commentId}
    </update>

    <update id="addLikeCount">
        UPDATE interaction_comment
        SET like_count = like_count + #{delta}, update_time = NOW()
        WHERE comment_id = #{commentId}
    </update>

    <select id="pageRootIds" resultType="java.lang.Long">
        SELECT comment_id
        FROM interaction_comment
        WHERE post_id = #{postId}
          AND root_id IS NULL
          AND status IN (1, 2)
          <if test="cursorTime != null and cursorId != null">
            AND (create_time &lt; #{cursorTime} OR (create_time = #{cursorTime} AND comment_id &lt; #{cursorId}))
          </if>
        ORDER BY create_time DESC, comment_id DESC
        LIMIT #{limit}
    </select>

    <select id="pageReplyIds" resultType="java.lang.Long">
        SELECT comment_id
        FROM interaction_comment
        WHERE root_id = #{rootId}
          AND status = 1
          <if test="cursorTime != null and cursorId != null">
            AND (create_time &gt; #{cursorTime} OR (create_time = #{cursorTime} AND comment_id &gt; #{cursorId}))
          </if>
        ORDER BY create_time ASC, comment_id ASC
        LIMIT #{limit}
    </select>
</mapper>
```

#### 7.4.4 infrastructure 仓储实现：`CommentRepository`

文件：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/CommentRepository.java`

实现要点（照着写，不要发明新概念）：
- `getBrief`：调用 `ICommentDao.selectBriefById`，映射到 `CommentBriefVO`
- `insert`：把 `nowMs` 转为 `Date` 并写入（`status=1`，计数默认 0）
- `softDelete`：调用 `ICommentDao.softDelete`，用 affectedRows 判断幂等（=1 才算成功）
- `pageRootCommentIds/pageReplyCommentIds`：解析 cursor `{timeMs}:{commentId}` 为 `Date + Long`

可照抄实现（完整文件）：

```java
package cn.nexus.infrastructure.adapter.social.repository;

import cn.nexus.domain.social.adapter.repository.ICommentRepository;
import cn.nexus.domain.social.model.valobj.CommentBriefVO;
import cn.nexus.infrastructure.dao.social.ICommentDao;
import cn.nexus.infrastructure.dao.social.po.CommentPO;
import java.util.Date;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * 评论仓储 MyBatis 实现。
 *
 * @author codex
 * @since 2026-01-14
 */
@Repository
@RequiredArgsConstructor
public class CommentRepository implements ICommentRepository {

    private final ICommentDao commentDao;

    @Override
    public CommentBriefVO getBrief(Long commentId) {
        if (commentId == null) {
            return null;
        }
        CommentPO po = commentDao.selectBriefById(commentId);
        if (po == null) {
            return null;
        }
        return CommentBriefVO.builder()
                .commentId(po.getCommentId())
                .postId(po.getPostId())
                .rootId(po.getRootId())
                .status(po.getStatus())
                .likeCount(po.getLikeCount())
                .replyCount(po.getReplyCount())
                .build();
    }

    @Override
    public void insert(Long commentId, Long postId, Long userId, Long rootId, Long parentId, Long replyToId, String content, Long nowMs) {
        Date now = new Date(nowMs == null ? System.currentTimeMillis() : nowMs);
        CommentPO po = new CommentPO();
        po.setCommentId(commentId);
        po.setPostId(postId);
        po.setUserId(userId);
        po.setRootId(rootId);
        po.setParentId(parentId);
        po.setReplyToId(replyToId);
        po.setContent(content == null ? "" : content);
        po.setStatus(1);
        po.setLikeCount(0L);
        po.setReplyCount(0L);
        po.setCreateTime(now);
        po.setUpdateTime(now);
        commentDao.insert(po);
    }

    @Override
    public boolean softDelete(Long commentId, Long nowMs) {
        if (commentId == null) {
            return false;
        }
        Date now = new Date(nowMs == null ? System.currentTimeMillis() : nowMs);
        int affected = commentDao.softDelete(commentId, now);
        return affected > 0;
    }

    @Override
    public void addReplyCount(Long rootCommentId, Long delta) {
        if (rootCommentId == null || delta == null || delta == 0) {
            return;
        }
        commentDao.addReplyCount(rootCommentId, delta);
    }

    @Override
    public void addLikeCount(Long rootCommentId, Long delta) {
        if (rootCommentId == null || delta == null || delta == 0) {
            return;
        }
        commentDao.addLikeCount(rootCommentId, delta);
    }

    @Override
    public List<Long> pageRootCommentIds(Long postId, String cursor, int limit) {
        Cursor c = Cursor.parse(cursor);
        int normalizedLimit = Math.max(1, limit);
        return commentDao.pageRootIds(postId,
                c == null ? null : c.cursorTime,
                c == null ? null : c.cursorId,
                normalizedLimit);
    }

    @Override
    public List<Long> pageReplyCommentIds(Long rootId, String cursor, int limit) {
        Cursor c = Cursor.parse(cursor);
        int normalizedLimit = Math.max(1, limit);
        return commentDao.pageReplyIds(rootId,
                c == null ? null : c.cursorTime,
                c == null ? null : c.cursorId,
                normalizedLimit);
    }

    private static final class Cursor {
        private final Date cursorTime;
        private final Long cursorId;

        private Cursor(Date cursorTime, Long cursorId) {
            this.cursorTime = cursorTime;
            this.cursorId = cursorId;
        }

        private static Cursor parse(String cursor) {
            if (cursor == null || cursor.isBlank()) {
                return null;
            }
            String[] parts = cursor.split(\":\");
            if (parts.length != 2) {
                return null;
            }
            try {
                long timeMs = Long.parseLong(parts[0]);
                long id = Long.parseLong(parts[1]);
                return new Cursor(new Date(timeMs), id);
            } catch (Exception ignored) {
                return null;
            }
        }
    }
}
```

#### 7.4.5 MyBatis：置顶表 DAO/XML + 仓储实现（`CommentPinRepository`）

> 你已经在 2.2/3.4 用到了 `pinRepo`，那就别“实现时再想”。把置顶做成独立表 + 独立仓储，数据结构更干净。

最小实现（不要发明新概念）：

- 表：`interaction_comment_pin`（见 1.4）
- PO：`CommentPinPO`（字段：`postId/commentId/createTime/updateTime`）
- DAO：`ICommentPinDao`\n  - `selectByPostId(postId)`\n  - `insertOrUpdate(po)`（`ON DUPLICATE KEY UPDATE`）\n  - `deleteByPostId(postId)`\n  - `deleteByPostIdAndCommentId(postId, commentId)`（用于 `clearIfPinned`）
- 仓储实现：`CommentPinRepository implements ICommentPinRepository`\n  - `getPinnedCommentId`：select\n  - `pin`：upsert\n  - `clear`：deleteByPostId\n  - `clearIfPinned`：deleteByPostIdAndCommentId（不需要先读再判断）

MyBatis XML 关键 SQL（示例）：

```sql
INSERT INTO interaction_comment_pin(post_id, comment_id, create_time, update_time)
VALUES(#{postId}, #{commentId}, #{createTime}, #{updateTime})
ON DUPLICATE KEY UPDATE comment_id = VALUES(comment_id), update_time = VALUES(update_time);

DELETE FROM interaction_comment_pin WHERE post_id = #{postId} AND comment_id = #{commentId};
```

#### 7.4.6 Redis 仓储实现：`CommentHotRankRepository`

文件：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/CommentHotRankRepository.java`

实现要点（对齐 FeedTimelineRepository 风格）：
- key：`comment:hot:` + postId
- `upsert`：`ZADD`
- `remove`：`ZREM`
- `topIds`：`ZREVRANGE 0 limit-1` + parseLong

可照抄实现（完整文件）：

```java
package cn.nexus.infrastructure.adapter.social.repository;

import cn.nexus.domain.social.adapter.repository.ICommentHotRankRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * 评论热榜仓储 Redis 实现（ZSET）。
 *
 * <p>Key：comment:hot:{postId}</p>
 *
 * @author codex
 * @since 2026-01-14
 */
@Repository
@RequiredArgsConstructor
public class CommentHotRankRepository implements ICommentHotRankRepository {

    private static final String KEY_PREFIX = "comment:hot:";

    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public void upsert(Long postId, Long rootCommentId, double score) {
        if (postId == null || rootCommentId == null) {
            return;
        }
        stringRedisTemplate.opsForZSet().add(key(postId), rootCommentId.toString(), score);
    }

    @Override
    public void remove(Long postId, Long rootCommentId) {
        if (postId == null || rootCommentId == null) {
            return;
        }
        stringRedisTemplate.opsForZSet().remove(key(postId), rootCommentId.toString());
    }

    @Override
    public List<Long> topIds(Long postId, int limit) {
        if (postId == null) {
            return List.of();
        }
        int normalized = Math.max(1, limit);
        Set<String> set = stringRedisTemplate.opsForZSet().reverseRange(key(postId), 0, normalized - 1);
        if (set == null || set.isEmpty()) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>(set.size());
        for (String s : set) {
            if (s == null || s.isBlank()) {
                continue;
            }
            try {
                ids.add(Long.parseLong(s));
            } catch (Exception ignored) {
                // 跳过坏数据，避免整页失败
            }
        }
        return ids;
    }

    private String key(Long postId) {
        return KEY_PREFIX + postId;
    }
}
```

#### 7.4.7 MyBatis：用户基础表 DAO/XML + 仓储实现（`UserBaseRepository`）

> 目标：让评论读接口返回 `nickname/avatarUrl`，但评论表只存 `user_id`。所以你必须在读侧做一次“批量补全用户信息”。  
> 禁止对评论列表做 N+1 单查。

数据来源（最小可用）：MySQL `user_base` 表（见 `社交领域数据库.md`），字段：
- `user_id`（PK）
- `username`（作为展示 `nickname`）
- `avatar_url`（映射为 `avatarUrl`）

最小实现（不要发明新概念）：

- PO：`UserBasePO`（字段：`userId/username/avatarUrl`）
- DAO：`IUserBaseDao`  
  - `selectByUserIds(userIds)`：`WHERE user_id IN (...)`
- 仓储实现：`UserBaseRepository implements IUserBaseRepository`  
  - `listByUserIds`：批量查询并映射为 `UserBriefVO(nickname=username, avatarUrl=avatarUrl)`

MyBatis XML 关键 SQL（示例）：

```sql
SELECT user_id, username, avatar_url
FROM user_base
WHERE user_id IN
<foreach collection="userIds" item="id" open="(" separator="," close=")">
  #{id}
</foreach>
```

#### 7.4.6 MQ 发布端口实现：`CommentEventPort`

文件：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/CommentEventPort.java`

实现要点：
- Exchange 固定：`social.interaction`
- RoutingKey：与 5.0 一致（`comment.created` / `comment.reply_count.changed` / `comment.like.changed` / `comment.mentioned`）
- 直接 `rabbitTemplate.convertAndSend(exchange, routingKey, event)`

可照抄实现（完整文件）：

```java
package cn.nexus.infrastructure.adapter.social.port;

import cn.nexus.domain.social.adapter.port.ICommentEventPort;
import cn.nexus.types.event.interaction.CommentCreatedEvent;
import cn.nexus.types.event.interaction.CommentLikeChangedEvent;
import cn.nexus.types.event.interaction.MentionedUsersEvent;
import cn.nexus.types.event.interaction.RootReplyCountChangedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * 评论事件发布端口实现：使用 RabbitMQ 直接投递。
 *
 * @author codex
 * @since 2026-01-14
 */
@Component
@RequiredArgsConstructor
public class CommentEventPort implements ICommentEventPort {

    private static final String EXCHANGE = "social.interaction";

    private static final String RK_COMMENT_CREATED = "comment.created";
    private static final String RK_REPLY_COUNT_CHANGED = "comment.reply_count.changed";
    private static final String RK_COMMENT_LIKE_CHANGED = "comment.like.changed";
    private static final String RK_MENTIONED = "comment.mentioned";

    private final RabbitTemplate rabbitTemplate;

    @Override
    public void publish(CommentCreatedEvent event) {
        rabbitTemplate.convertAndSend(EXCHANGE, RK_COMMENT_CREATED, event);
    }

    @Override
    public void publish(RootReplyCountChangedEvent event) {
        rabbitTemplate.convertAndSend(EXCHANGE, RK_REPLY_COUNT_CHANGED, event);
    }

    @Override
    public void publish(CommentLikeChangedEvent event) {
        rabbitTemplate.convertAndSend(EXCHANGE, RK_COMMENT_LIKE_CHANGED, event);
    }

    @Override
    public void publish(MentionedUsersEvent event) {
        rabbitTemplate.convertAndSend(EXCHANGE, RK_MENTIONED, event);
    }
}
```

### 7.5 Step 4：trigger（MQ 拓扑 + Consumers）

#### 7.5.1 MQ 拓扑：`InteractionCommentMqConfig`

文件：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/config/InteractionCommentMqConfig.java`

照抄（和 `FeedFanoutConfig` 同风格）：
```java
package cn.nexus.trigger.mq.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 评论相关 MQ 拓扑：创建/计数变更/@通知。
 *
 * @author codex
 * @since 2026-01-14
 */
@Configuration
public class InteractionCommentMqConfig {

    public static final String EXCHANGE = "social.interaction";

    public static final String RK_COMMENT_CREATED = "comment.created";
    public static final String Q_COMMENT_CREATED = "interaction.comment.created.queue";

    public static final String RK_REPLY_COUNT_CHANGED = "comment.reply_count.changed";
    public static final String Q_REPLY_COUNT_CHANGED = "interaction.comment.reply_count.changed.queue";

    public static final String RK_COMMENT_LIKE_CHANGED = "comment.like.changed";
    public static final String Q_COMMENT_LIKE_CHANGED = "interaction.comment.like.changed.queue";

    public static final String RK_MENTIONED = "comment.mentioned";
    public static final String Q_MENTIONED = "interaction.comment.mentioned.queue";

    @Bean
    public DirectExchange interactionExchange() {
        return new DirectExchange(EXCHANGE, true, false);
    }

    @Bean
    public Queue commentCreatedQueue() {
        return new Queue(Q_COMMENT_CREATED, true);
    }

    @Bean
    public Queue replyCountChangedQueue() {
        return new Queue(Q_REPLY_COUNT_CHANGED, true);
    }

    @Bean
    public Queue commentLikeChangedQueue() {
        return new Queue(Q_COMMENT_LIKE_CHANGED, true);
    }

    @Bean
    public Queue mentionedQueue() {
        return new Queue(Q_MENTIONED, true);
    }

    @Bean
    public Binding commentCreatedBinding(Queue commentCreatedQueue, DirectExchange interactionExchange) {
        return BindingBuilder.bind(commentCreatedQueue).to(interactionExchange).with(RK_COMMENT_CREATED);
    }

    @Bean
    public Binding replyCountChangedBinding(Queue replyCountChangedQueue, DirectExchange interactionExchange) {
        return BindingBuilder.bind(replyCountChangedQueue).to(interactionExchange).with(RK_REPLY_COUNT_CHANGED);
    }

    @Bean
    public Binding commentLikeChangedBinding(Queue commentLikeChangedQueue, DirectExchange interactionExchange) {
        return BindingBuilder.bind(commentLikeChangedQueue).to(interactionExchange).with(RK_COMMENT_LIKE_CHANGED);
    }

    @Bean
    public Binding mentionedBinding(Queue mentionedQueue, DirectExchange interactionExchange) {
        return BindingBuilder.bind(mentionedQueue).to(interactionExchange).with(RK_MENTIONED);
    }
}
```

#### 7.5.2 Consumers（把业务逻辑放 domain，不要在 trigger 写一坨）

最小可用做法：consumer 直接注入 domain 仓储接口（Spring 会注入 infrastructure 实现），逻辑保持 10~30 行。

- `CommentCreatedConsumer`：只处理“一级评论入热榜”
- `RootReplyCountChangedConsumer`：更新 DB `reply_count` 并更新 Redis score
- `CommentLikeChangedConsumer`：更新 DB `like_count` 并更新 Redis score（可选）
- `MentionedUsersConsumer`：目前只记录日志（可选后续接入通知）

热榜 score 计算函数（固定）：
```
score = likeCount * 10 + replyCount * 20
```

可照抄实现（Consumers 模板，直接放到 trigger 层）：

#### 7.5.2.1 `CommentCreatedConsumer.java`

文件：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/CommentCreatedConsumer.java`
```java
package cn.nexus.trigger.mq.consumer;

import cn.nexus.domain.social.adapter.repository.ICommentHotRankRepository;
import cn.nexus.trigger.mq.config.InteractionCommentMqConfig;
import cn.nexus.types.event.interaction.CommentCreatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 评论创建事件消费者：一级评论入热榜。
 *
 * @author codex
 * @since 2026-01-14
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CommentCreatedConsumer {

    private final ICommentHotRankRepository hotRankRepository;

    @RabbitListener(queues = InteractionCommentMqConfig.Q_COMMENT_CREATED)
    public void onMessage(CommentCreatedEvent event) {
        if (event == null || event.getPostId() == null || event.getCommentId() == null) {
            return;
        }
        // 热榜只排一级评论
        if (event.getRootId() != null) {
            return;
        }
        hotRankRepository.upsert(event.getPostId(), event.getCommentId(), 0D);
    }
}
```

#### 7.5.2.2 `RootReplyCountChangedConsumer.java`

文件：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/RootReplyCountChangedConsumer.java`
```java
package cn.nexus.trigger.mq.consumer;

import cn.nexus.domain.social.adapter.repository.ICommentHotRankRepository;
import cn.nexus.domain.social.adapter.repository.ICommentRepository;
import cn.nexus.domain.social.model.valobj.CommentBriefVO;
import cn.nexus.trigger.mq.config.InteractionCommentMqConfig;
import cn.nexus.types.event.interaction.RootReplyCountChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 回复数变更事件消费者：更新一级评论 reply_count + 刷新热榜分数。
 *
 * @author codex
 * @since 2026-01-14
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RootReplyCountChangedConsumer {

    private final ICommentRepository commentRepository;
    private final ICommentHotRankRepository hotRankRepository;

    @RabbitListener(queues = InteractionCommentMqConfig.Q_REPLY_COUNT_CHANGED)
    public void onMessage(RootReplyCountChangedEvent event) {
        if (event == null || event.getRootCommentId() == null || event.getPostId() == null) {
            return;
        }
        commentRepository.addReplyCount(event.getRootCommentId(), event.getDelta());

        CommentBriefVO root = commentRepository.getBrief(event.getRootCommentId());
        if (root == null) {
            return;
        }
        if (root.getStatus() == null || root.getStatus() != 1) {
            hotRankRepository.remove(event.getPostId(), event.getRootCommentId());
            return;
        }
        double score = safe(root.getLikeCount()) * 10D + safe(root.getReplyCount()) * 20D;
        hotRankRepository.upsert(event.getPostId(), event.getRootCommentId(), score);
    }

    private long safe(Long v) {
        return v == null ? 0L : v;
    }
}
```

#### 7.5.2.3 `CommentLikeChangedConsumer.java`（可选）

文件：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/CommentLikeChangedConsumer.java`
```java
package cn.nexus.trigger.mq.consumer;

import cn.nexus.domain.social.adapter.repository.ICommentHotRankRepository;
import cn.nexus.domain.social.adapter.repository.ICommentRepository;
import cn.nexus.domain.social.model.valobj.CommentBriefVO;
import cn.nexus.trigger.mq.config.InteractionCommentMqConfig;
import cn.nexus.types.event.interaction.CommentLikeChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 点赞数变更事件消费者：更新一级评论 like_count + 刷新热榜分数。
 *
 * @author codex
 * @since 2026-01-14
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CommentLikeChangedConsumer {

    private final ICommentRepository commentRepository;
    private final ICommentHotRankRepository hotRankRepository;

    @RabbitListener(queues = InteractionCommentMqConfig.Q_COMMENT_LIKE_CHANGED)
    public void onMessage(CommentLikeChangedEvent event) {
        if (event == null || event.getRootCommentId() == null || event.getPostId() == null) {
            return;
        }
        commentRepository.addLikeCount(event.getRootCommentId(), event.getDelta());

        CommentBriefVO root = commentRepository.getBrief(event.getRootCommentId());
        if (root == null) {
            return;
        }
        if (root.getStatus() == null || root.getStatus() != 1) {
            hotRankRepository.remove(event.getPostId(), event.getRootCommentId());
            return;
        }
        double score = safe(root.getLikeCount()) * 10D + safe(root.getReplyCount()) * 20D;
        hotRankRepository.upsert(event.getPostId(), event.getRootCommentId(), score);
    }

    private long safe(Long v) {
        return v == null ? 0L : v;
    }
}
```

#### 7.5.2.4 `MentionedUsersConsumer.java`（可选）

文件：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/MentionedUsersConsumer.java`
```java
package cn.nexus.trigger.mq.consumer;

import cn.nexus.trigger.mq.config.InteractionCommentMqConfig;
import cn.nexus.types.event.interaction.MentionedUsersEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * @ 提及事件消费者：占位实现（只记录日志）。
 *
 * @author codex
 * @since 2026-01-14
 */
@Slf4j
@Component
public class MentionedUsersConsumer {

    @RabbitListener(queues = InteractionCommentMqConfig.Q_MENTIONED)
    public void onMessage(MentionedUsersEvent event) {
        if (event == null) {
            return;
        }
        log.info("mentioned users event, commentId={}, postId={}, rootId={}, mentionedUserIds={}",
                event.getCommentId(), event.getPostId(), event.getRootId(), event.getMentionedUserIds());
    }
}
```

### 7.6 Step 5：trigger（HTTP：从 UserContext 注入 userId）

#### 7.6.1 新增 `UserContext`（如果你还没做）

完整做法见：`.codex/user-context-injection.md`。
文件建议：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/support/UserContext.java`

#### 7.6.2 修改 `InteractionController.comment`

文件：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/InteractionController.java`

改动要点：
- 读取 `userId = UserContext.requireUserId()`
- 调用 domain：`interactionService.comment(userId, postId, parentId, content, mentions)`
- 缺 header：返回 `ResponseCode.ILLEGAL_PARAMETER`（别继续往下跑）

可照抄实现片段（只示意 comment 接口；其它接口同理）：

```java
@PostMapping("/interact/comment")
@Override
public Response<CommentResponseDTO> comment(@RequestBody CommentRequestDTO requestDTO) {
    Long userId;
    try {
        userId = UserContext.requireUserId();
    } catch (Exception e) {
        return Response.<CommentResponseDTO>builder()
                .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                .info(ResponseCode.ILLEGAL_PARAMETER.getInfo())
                .build();
    }

    CommentResultVO vo = interactionService.comment(
            userId,
            requestDTO.getPostId(),
            requestDTO.getParentId(),
            requestDTO.getContent(),
            requestDTO.getMentions());
    return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(),
            CommentResponseDTO.builder().commentId(vo.getCommentId()).createTime(vo.getCreateTime()).build());
}
```
### 7.7 Step 6：domain（实现 InteractionService.comment）

文件：
- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/IInteractionService.java`
- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/InteractionService.java`

改动要点：
- `comment(...)` 签名增加 `userId`
- `InteractionService` 注入：`ISocialIdPort` + `ICommentRepository` + `ICommentEventPort`
- rootId 计算严格按 2.1：
  - 一级：`rootId=null, parentId=null`
  - 回复：读 parent brief，校验 `postId` 一致且 `status=1`，然后 `rootId = parent.rootId==null ? parent.commentId : parent.rootId`
- insert 成功后发布事件：
  - `CommentCreatedEvent`（所有评论都发）
  - `RootReplyCountChangedEvent(delta=+1)`（仅回复才发）
  - `MentionedUsersEvent`（`mentions` 不为空才发；优先用 DTO 的 `mentions`，别解析 content）

可照抄实现片段（核心逻辑，别写成巨石函数）：

```java
@Override
public CommentResultVO comment(Long userId, Long postId, Long parentId, String content, List<Long> mentions) {
    Long nowMs = socialIdPort.now();
    Long commentId = socialIdPort.nextId();

    Long rootId = null;
    Long parentIdToSave = null;
    Long replyToId = null;

    if (parentId != null) {
        CommentBriefVO parent = commentRepository.getBrief(parentId);
        if (parent == null || parent.getPostId() == null || !parent.getPostId().equals(postId)) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), ResponseCode.ILLEGAL_PARAMETER.getInfo());
        }
        if (parent.getStatus() == null || parent.getStatus() != 1) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), ResponseCode.ILLEGAL_PARAMETER.getInfo());
        }
        rootId = parent.getRootId() == null ? parent.getCommentId() : parent.getRootId();
        parentIdToSave = parent.getCommentId();
        replyToId = parent.getCommentId();
    }

    commentRepository.insert(commentId, postId, userId, rootId, parentIdToSave, replyToId, content, nowMs);

    CommentCreatedEvent created = new CommentCreatedEvent();
    created.setCommentId(commentId);
    created.setPostId(postId);
    created.setRootId(rootId);
    created.setUserId(userId);
    created.setCreateTimeMs(nowMs);
    commentEventPort.publish(created);

    if (rootId != null) {
        RootReplyCountChangedEvent changed = new RootReplyCountChangedEvent();
        changed.setRootCommentId(rootId);
        changed.setPostId(postId);
        changed.setDelta(+1L);
        changed.setTsMs(nowMs);
        commentEventPort.publish(changed);
    }

    if (mentions != null && !mentions.isEmpty()) {
        MentionedUsersEvent evt = new MentionedUsersEvent();
        evt.setCommentId(commentId);
        evt.setPostId(postId);
        evt.setRootId(rootId);
        evt.setMentionedUserIds(mentions);
        evt.setTsMs(nowMs);
        commentEventPort.publish(evt);
    }

    return CommentResultVO.builder().commentId(commentId).createTime(nowMs).build();
}
```
### 7.8 必验收（不跑这些，你实现等于没实现）

1) 发一级评论：`root_id IS NULL`，热榜 `comment:hot:{postId}` 里出现该 `commentId`
2) 发回复：`root_id = 一级评论comment_id`，且一级评论 `reply_count +1`
3) 删除回复（软删）：状态从 1->2 时才触发 `delta=-1`，一级评论 `reply_count -1`
4) 不允许回复已删评论：parent.status=2 时创建回复必须失败
5) 热榜只排一级评论：回复不会出现在 `comment:hot:{postId}`
> 重要提醒：SQL 里判断 NULL 必须用 `IS NULL`，不要写 `= NULL`（那是永远 false 的）。
