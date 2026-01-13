# B站“评论盖楼”系统：两级扁平化 + Redis 热榜 + MQ 异步（实现级方案）

执行者：Codex（Linus mode）  
日期：2026-01-13  
来源：微信文章《B站二面：设计一个“评论盖楼”系统，我只答了“递归”，面试官笑了：你连索引都不会建？》  
链接：https://mp.weixin.qq.com/s/Xe9MeJ6yQSmUoFUrGn4_qw  
作者：Fox爱分享

> 目标：把原文的思路落成“能交给另一个 Codex agent 直接实现”的文档。  
> 风格：先定数据结构，再定读写链路；尽量消灭特殊情况；接受最终一致性（评论不是银行）。

---

## 0. 我理解你的需求是（请确认）

你希望基于这篇文章，整理出一套“B站/抖音式两层评论（盖楼）系统”的实现方案：  
要能支撑高并发读写；支持按时间/按热度排序；处理中间评论被删、回复不丢；点赞/回复/@通知等都不能阻塞主链路；最后输出到一个新 Markdown 文档，供另一个 agent 借鉴实现。

请确认我的理解是否准确？

---

## 1. 一句话版本（给 12 岁也能懂）

别用“树形递归”查评论。把所有回复都“摊平”，每条回复都指向同一个一级评论（root_id）。这样查一楼的所有回复只要一条 SQL；热度排序交给 Redis 排行榜；点赞、计数、@通知都走 MQ 异步。

---

## 2. 王者方案：反范式 + 两级扁平化（B站/抖音模式）

**关键思想**：业务是“两层结构”，不是“无限层级论坛”。UI 上所有回复都展示在一级评论下面，回复谁只是展示信息，不影响核心查询。  
**做法**：引入冗余字段 `root_id`，所有回复的 `root_id` 都指向同一个一级评论。  
**收益**：查询一个楼层的所有回复是 O(1) 的索引扫描，不需要递归。

#### 2.1 你要保证的“数据不变量”（实现时别写歪）

1) **root_id 的语义永远不变**  
- `root_id = 0`：一级评论（对 post 的直接评论）  
- `root_id > 0`：回复（无论回复谁，都属于某个一级评论的楼层）

2) **回复链条不参与核心查询**  
- `parent_id` / `reply_to_id` 只为“展示/定位上下文”，即使断了也不影响“查整楼”

3) **所有楼内回复都能被一条索引查询拿全**  
- `WHERE root_id = :rootId` 必须始终成立（否则你又回到递归地狱）

#### 2.2 root_id 计算规则（写入时一次算对，读侧永远省事）

写入时只做一次判断：

- 新建一级评论：`root_id = 0`，`parent_id = 0`（或 NULL，但要统一）
- 新建回复（parent_id > 0）：先查父评论 `parent`（只需要 `comment_id/root_id/post_id/status`）
  - 若 `parent.root_id == 0`：`root_id = parent.comment_id`（父亲就是一级评论）
  - 否则：`root_id = parent.root_id`（父亲也是回复，继承它的 root_id）
  - `reply_to_id = parent.comment_id`（用于前端显示“回复@谁”）
  - `parent_id = parent.comment_id`（可选：用于审核/定位上下文）

必须校验 2 个事实（不然数据会脏到无法修）：

- `parent.post_id == post_id`（禁止跨帖子串楼）
- `parent.status != 删除`（产品决定：要么禁止回复已删评论，要么允许但前端显示“已删除”占位；但两种都要记录清楚）

---

## 3. 核心数据结构（落库真值）

> 这里是实现的“锚”。其它所有组件（Redis/MQ）都是围绕它加速与削峰。

### 3.1 Comment 表（最小字段集）

表名建议：`comment`

| 字段 | 类型 | 含义 | 备注 |
| --- | --- | --- | --- |
| `comment_id` | BIGINT PK | 评论 ID | 雪花/发号器 |
| `post_id` | BIGINT | 内容/视频/帖子 ID | 你系统里的目标 |
| `user_id` | BIGINT | 评论者 | 必须有 |
| `root_id` | BIGINT | 一级评论 ID | 一级评论：0；回复：一级评论的 comment_id |
| `parent_id` | BIGINT | 直接父评论 ID | 可选：用于审核/风控/定位上下文，不参与“查整楼” |
| `reply_to_id` | BIGINT | 回复对象 ID | 仅用于前端显示“回复@谁” |
| `content` | TEXT/LONGTEXT | 评论内容 | 建议支持结构化 @ 片段 |
| `status` | TINYINT | 状态 | 1=正常；2=删除/屏蔽（软删） |
| `like_count` | BIGINT | 点赞数 | 可最终一致 |
| `reply_count` | BIGINT | 回复数 | 对一级评论有意义 |
| `create_time` | DATETIME | 创建时间 | |
| `update_time` | DATETIME | 更新时间 | |

### 3.2 两级扁平化规则（必须严格遵守）

- 一级评论：`root_id = 0`（或 NULL，但要统一）
- 二级及更深回复：`root_id = 一级评论.comment_id`
- 回复二级评论（回复的回复）：  
  - `root_id` 仍然是一级评论 ID（不变）  
  - `reply_to_id` 指向被回复的那条评论（仅展示）  
  - `parent_id` 可指向被回复的那条评论（可选）

### 3.2.1 写入链路（最小可用版本，建议按这个拆函数）

> 目标：发评论接口只做“写 comment + 发事件”，别在主线程里做计数/热榜/@通知。

pseudocode（领域服务）：

```
createComment(postId, userId, parentId, content):
  now = clock.now()
  commentId = idPort.nextId()

  if parentId is null or parentId == 0:
    rootId = 0
    replyToId = 0
  else:
    parent = commentRepo.getBrief(parentId) // {commentId, postId, rootId, status}
    assert parent.postId == postId
    assert parent.status == NORMAL (or allowDeletedByPolicy)
    rootId = (parent.rootId == 0) ? parent.commentId : parent.rootId
    replyToId = parent.commentId

  commentRepo.insert(commentId, postId, userId, rootId, parentId, replyToId, content, now)

  mq.publish(CommentCreatedEvent(commentId, postId, rootId, userId, now))
  mq.publish(MentionedUsersEvent(commentId, postId, rootId, mentionedUserIds, now)) // 如果有@

  return commentId
```

> “好品味”点：核心逻辑只有一层 if/else；root_id 一次算对，后面所有查询都简单。

### 3.3 关键查询（必须能走索引）

**查整楼回复（核心能力）**：

```sql
SELECT * FROM comment
WHERE root_id = :rootId AND status = 1
ORDER BY create_time ASC, comment_id ASC
LIMIT :limit OFFSET :offset;
```

> 这就是“扁平化”的意义：永远不需要递归。

**查帖子下一级评论**（按时间）：

```sql
SELECT * FROM comment
WHERE post_id = :postId AND root_id = 0 AND status = 1
ORDER BY create_time DESC, comment_id DESC
LIMIT :limit OFFSET :offset;
```

### 3.4 必要索引（别装，索引就是命）

至少需要：

- `idx_post_root_time`：`(post_id, root_id, create_time, comment_id)`
  - 覆盖“查一级评论”和“查整楼回复”
- 如果你常按 root_id 查回复，也可加：
  - `idx_root_time`：`(root_id, create_time, comment_id)`

> 注意：不要指望数据库实时用 “ORDER BY (like_count+reply_count)” 扛热点排序，这是计算密集型，迟早炸。

---

## 4. 读链路（按时间 / 按热度）

### 4.1 一级评论列表（按时间）

建议用 **游标分页** 代替 OFFSET（深翻页会越来越慢）。游标可以沿用你仓库里 profile 的风格：`{createTimeMs}:{commentId}`。

SQL（create_time DESC, comment_id DESC）：

```sql
SELECT * FROM comment
WHERE post_id = :postId AND root_id = 0 AND status = 1
  AND (create_time < :cursorTime OR (create_time = :cursorTime AND comment_id < :cursorId))
ORDER BY create_time DESC, comment_id DESC
LIMIT :limit;
```

### 4.2 楼内回复列表（按时间）

楼内回复通常按时间正序展示，同样建议游标分页（create_time ASC, comment_id ASC）：

```sql
SELECT * FROM comment
WHERE root_id = :rootId AND status = 1
  AND (create_time > :cursorTime OR (create_time = :cursorTime AND comment_id > :cursorId))
ORDER BY create_time ASC, comment_id ASC
LIMIT :limit;
```

### 4.3 楼内“预加载策略”（产品体验，不是学术）

典型 UI：一级评论列表每条只预加载 **前 N 条回复**（例如 3 条），其余点“展开更多”再拉。

实现方式（简单但有效）：
- 先分页拉一级评论（limit=20）
- 对每个 rootCommentId：再查 `root_id = rootCommentId` 的前 3 条回复

> 注意：这会变成 1 + N 次查询。优化空间有，但别一上来就过度设计（先跑起来）。

---

## 5. 热度排序（Redis ZSet 排行榜）

### 5.1 热榜的责任边界

数据库存“数据真值”；Redis 只存“排序索引”。  
Redis 挂了：只是排序体验降级，不影响数据不丢。

### 5.2 ZSet Key 设计（按你产品的排序范围选一种）

原文给了示例：`comment:hot:{video_id}:{root_id}`。你实现时可以按实际 UI 选择：

**A. 一级评论热榜（最常见）**

- Key：`comment:hot:{postId}`（ZSET）
- member：`comment_id`（一级评论）

**B. 楼内回复热榜（如果你要对二级回复也按热度）**

- Key：`comment:hot:{postId}:{rootId}`（ZSET）
- member：`comment_id`（该楼内回复）

### 5.3 Score 公式（先用土办法跑起来）

原文建议：

- `score = like_count * 10 + reply_count * 20 + 时间衰减因子`

衰减因子你可以先用最简单的：

- `decay = - ageHours`（越老分越低）

---

### 5.4 查询流程（典型“先拿ID再回表”）

1) 读 Redis：`ZREVRANGE key 0 9` 拿到 TopN comment_id  
2) 回表查 MySQL：`WHERE comment_id IN (...)` 拿内容  
3) 按 Redis 返回的 ID 顺序重排（避免 IN 查询顺序漂移）

> 这个模式你仓库里已经在 Feed timeline 回表用过（同样问题同样解法）。

---

## 6. 写链路（点赞/回复/计数更新：全部异步化）

### 6.1 原文的核心立场：评论不是银行，允许短暂不一致

用户点个赞，前端直接 +1，体验先赢。  
后台慢慢把计数落库、更新 Redis 排序分数。  
Redis 比 MySQL 快 1 秒，没人会死。

### 6.2 点赞更新建议链路

最小可行的链路：

1) 用户点赞（API 收到请求）  
2) 写一条消息到 MQ（削峰）  
3) 消费者：更新 MySQL `like_count`（或独立点赞表）  
4) 同时：更新 Redis ZSet 的 score（只更新索引，不存正文）

> 备注：你如果已经有“点赞计数方案”（Redis 秒回 + 延迟落库），也可以把评论点赞复用那套。关键是：不要在主线程里同步写数据库。

### 6.3 回复数更新建议链路

当新增一条回复时：

- 插入 comment 行（root_id 已经确定）
- 通过 MQ 异步把“一级评论 reply_count +1”
- 消费者更新 MySQL `reply_count`
- 同时更新 Redis ZSet score（因为 reply_count 参与热度）

---

## 7. 中间评论被删怎么办？（树断了怎么办？）

扁平化结构下，“树断了”不是问题：

- 查询整楼靠的是 `root_id`，不是 parent 链
- 删除某一条中间评论：只要软删该行（status=删除），楼内其它回复仍能正常查出
- 前端展示：被删评论显示“该评论已删除”，但下面回复继续显示（产品决定）

---

## 8. @用户 怎么设计？（结构化存储 + 异步通知）

### 8.1 为什么不能只存纯文本

如果只存“@Fox 你好”，以后 Fox 改名，你历史评论会显示旧名字。  
所以要么存 JSON，要么存带结构的标记。

### 8.2 推荐存储格式（原文示例）

例如把 content 存成带结构片段的文本：

- `"@{user_id:888, name:Fox} 你好"`

前端渲染：识别这种格式，渲染成超链接。

### 8.3 通知必须异步（千万别卡主链路）

保存评论时：

1) 后端解析出被 @ 的 `user_id` 列表  
2) 丢 MQ：`topic: at_notification`（名字只是示意）  
3) 通知服务消费：给这些用户发站内信/Push

> 关键原则：不要在“保存评论”的主线程里发通知，会把接口延迟打爆。

---

## 9. 在本仓库如何落地（给另一个 Codex agent 的对接清单）

> 你现在的仓库是 DDD 分层：trigger/api/domain/infrastructure/types。

### 9.1 已有接口（代码事实）

- 评论接口：`POST /api/v1/interact/comment`
- DTO：`CommentRequestDTO` 包含 `postId/parentId/content/mentions`
- Domain：`InteractionService.comment` 目前是占位（只返回 mock 的 commentId）

### 9.2 实现落地点（按层分配）

**API/trigger（入口层）**

- 补齐 userId 来源：没有 userId 就无法落库谁评论的  
  - 最小可行：DTO 增加 `userId`  
  - 或者：从网关/鉴权上下文注入（如果你有的话）

**domain（业务编排）**

- 新增 `CommentService` 或扩展 `InteractionService.comment`  
- 负责：
  - 计算 root_id（决定“扁平化”是否成立）
  - 组织 MQ 事件（reply_count、@通知、热榜更新）

**infrastructure（落地细节）**

- MyBatis：comment 表的 insert/query/index 使用  
- Redis：ZSet 热榜读写（按 Key 规范）
- MQ：定义消息体（如 CommentCreatedEvent / CommentLikedEvent / MentionedUsersEvent）并消费更新计数+热榜

### 9.3 最小验收（写代码之前就能对齐）

1) 回复的回复也能用一条 SQL 查出来（`WHERE root_id = ?`）  
2) 中间评论删除不影响楼内其它回复可见  
3) 热度排序不走 MySQL 计算，走 Redis ZSet + 回表  
4) 点赞/回复/@通知都不会阻塞“发评论”接口（MQ 异步）  

---

## 10. 开放问题（不回答会卡实现）

1) 你产品是否严格“两级展示”？（如果 UI 真要无限层级，这套要改）  
2) 热榜范围：只排一级评论，还是楼内回复也要热榜？（决定 ZSet key）  
3) `root_id=0` 还是 NULL？（数据库与代码要统一，否则索引/查询会乱）  
