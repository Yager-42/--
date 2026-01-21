# 通知业务实现说明书（可照抄实施）

日期：2026-01-21  
执行者：Codex（Linus-mode）  
输入依据：  
- 评论：`.codex/comment-floor-system-implementation.md`（两级盖楼约束）  
- 点赞：`.codex/interaction-like-pipeline-implementation.md` + 现有实现 `ReactionLikeService/ReactionCachePort`（delta/幂等语义）  
- 接口：`社交接口.md`（4. 互动与通知服务）+ 现有代码 `InteractionController`（`GET /api/v1/notification/list`）  

目标：设计一套**可落地**的“站内通知”实现方案：当 **post/评论** 被点赞或被评论时，作者能看到“哪个 post/评论发生了什么”和“新增了多少赞/评论”。

---

## 0. 一句话目标（给 12 岁也能懂）

别人给你的帖子/评论点了赞，或者回复了你，你打开“通知”就能看到：**是谁动了什么**、**动了多少次**、**要点哪里去看**。

---

## 0.1 本文拍板清单（实现不得分叉）

1) **只做站内通知列表**：先不做 Push/短信/在线状态路由（那是另一个系统）。  
2) **只统计“新增”**：  
   - 点赞：只在“真的新增了一个赞”时计数（`delta=+1`）。  
   - 评论：每条新评论/回复都算一次（`+1`）。  
3) **聚合写入（核心）**：同一用户对同一个目标产生的同类事件，在通知里合并成一条：`unread_count += delta`。  
4) **消除重复通知（评论规则）**：  
   - `parentId == null`（直接评论 post）→ 通知 post 作者。  
   - `parentId != null`（回复某条评论）→ **只**通知被回复的那条评论作者（不再同时通知 post 作者）。  
5) **兼容现有 API**：`/notification/list` 仍返回 `title/content/createTime`，只允许“加字段”，禁止删字段/改语义。  
6) **必须支持 `@提及`**：评论里提及某用户时，对该用户产生站内通知（见 5.2/6.1/11）。  
7) **上线必做幂等**：RabbitMQ 至少一次投递；通知消费者必须对 `eventId` 去重，否则 `unread_count` 会被重复 +1（见 6.4）。  
8) **`@提及` 不双通知（拍板）**：如果被 @ 的用户本来就会收到该评论触发的 `POST_COMMENTED/COMMENT_REPLIED`（主收件人），则不再额外发送 `COMMENT_MENTIONED`（见 7.1）。  
9) **只返回未读（拍板）**：`/notification/list` **只返回** `unread_count > 0` 的通知行。  
10) **createTime 口径（拍板）**：`NotificationDTO.createTime` 返回 `update_time`（最后一次发生时间，毫秒）。  
11) **@username 解析口径（拍板）**：`@提及` 由**后端**从评论文本解析，语法固定为 `@username`，不允许 `@{userId}`（见 6.1 / 6.2 / 7.1）。  

---

## 1. 需求理解确认（实现不得偏离）

基于现有信息，我理解你的需求是：

- 你已经有“点赞链路”的落地方案与实现（Redis 幂等写 + MQ 延迟落库）。  
- 你也有“两级评论盖楼”的落地方案（root_id 语义、楼内回复不允许点赞等）。  
- 现在要补齐“通知”这块：当用户的 **post/评论** 被点赞或被评论时，作者能在通知列表里看到：  
  - 哪个 post / 哪条评论被互动了  
  - 新增了多少赞（以及可选：新增了多少评论/回复）  

已确认：回复评论只通知被回复的那条评论作者（不再同时通知 post 作者）。  

---

## 2. Linus 五层分析（只说关键点）

### 2.1 数据结构分析（核心）

通知系统真正的核心数据只有两类：

1) **事件**：点赞新增 / 评论创建（我们不记录取消赞）。  
2) **收件箱聚合结果**：对“同类事件 + 同一目标”累加出来的 `unread_count`。

一句话：**通知不是聊天记录，不需要保存每个赞的明细；它只需要“聚合后的提醒”。**

### 2.2 特殊情况识别（把 if/else 砍掉）

最容易写烂的两个“特殊情况”：

- 点赞重复请求/重试导致重复通知。  
- 评论回复场景同时通知 post 作者 + 评论作者，导致用户收到两条一样的“你被回复了”。

解决：

- 点赞只看 `delta=+1`（由 Redis 数据结构天然提供幂等）。  
- 回复只通知“被回复的那条评论作者”，直接评论才通知 post 作者（规则一刀切，不写补丁）。  

### 2.3 复杂度审查（别发明新概念）

本质一句话：

- **把互动事件变成“收件箱的一行聚合记录”。**

不要加：

- 复杂的 push 路由  
- 复杂的去重策略（幂等已由点赞链路解决）  
- 复杂的权限/风控/安全（本文明确不做）  

### 2.4 破坏性分析（Never break userspace）

不能破坏的用户可见行为：

- 现有 `GET /api/v1/notification/list` 仍然可用。  
- 返回里 `title/content/createTime` 仍然存在且语义合理。  

我们只做“加字段”，前端不升级也不会崩。  

### 2.5 实用性验证（是否真问题）

点赞和评论是最高频互动。没有通知，产品就是“哑的”。  

- ✅ 值得做：而且要先把“聚合写 + 兼容读”做对，否则你会把 DB 写爆、用户被通知刷屏。  

---

## 3. 结论：✅ 值得做

原因（不超过 3 条）：

1) 这是用户感知最强的闭环（互动→反馈）。  
2) 通过“聚合 UPSERT”可以把高频写压成可控的 DB 写入量。  
3) 现有代码里通知是占位实现，补齐空间非常清晰。  

---

## 4. 现状对齐（先看懂现在是什么）

### 4.1 已存在的 API（但没实现）

- `GET /api/v1/notification/list`  
  - Controller：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/InteractionController.java`  
  - Service 占位：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/InteractionService.java#notifications`  
  - DTO 现状：`NotificationDTO` 只有 `title/content/createTime`  

### 4.2 已存在的“点赞真实语义”（我们必须复用）

点赞链路已经给了我们一个“黄金信号”：

- `ReactionCachePort.applyAtomic(...)` 返回 `delta`：`+1/-1/0`  
- 只有 `delta=+1` 才代表“真的新增点赞”  

这意味着：**通知不需要自己做幂等，直接吃 delta 就够了。**

### 4.3 评论系统约束（通知也必须遵守）

来自两级盖楼方案（简化后只保留和通知有关的点）：

- 回复（楼内）仍然是 comment，只是 `root_id` 指向一级评论。  
- 点赞范围：只允许给一级评论点赞；楼内回复禁止点赞。  

---

## 5. 核心数据结构（MySQL 真值：聚合收件箱）

> 目标：让通知读接口只做一次“按用户分页”，不需要复杂 join，不需要递归。

### 5.1 表：`interaction_notification`（聚合收件箱）

最小字段（建议保留）：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `notification_id` | BIGINT PK | 通知行 ID（Snowflake，由 `socialIdPort.nextId()` 生成，用于分页游标） |
| `to_user_id` | BIGINT NOT NULL | 收件人 |
| `biz_type` | VARCHAR(32) NOT NULL | 业务类型（见 5.2） |
| `target_type` | VARCHAR(16) NOT NULL | 目标类型：POST/COMMENT |
| `target_id` | BIGINT NOT NULL | 目标 ID（被点赞/被回复的 postId/commentId） |
| `post_id` | BIGINT NULL | 关联 postId（用于跳转；COMMENT 场景强烈建议填） |
| `root_comment_id` | BIGINT NULL | COMMENT 场景可选：用于定位楼层（两级盖楼） |
| `last_actor_user_id` | BIGINT NULL | 最近一次触发者（可用于展示“某某点赞了你”） |
| `last_comment_id` | BIGINT NULL | 最近一次产生的 commentId（用于点击进入定位） |
| `unread_count` | BIGINT NOT NULL | 未读新增次数（赞/评论的增量） |
| `create_time` | DATETIME NOT NULL | 创建时间 |
| `update_time` | DATETIME NOT NULL | 最后一次发生时间（用于列表排序/游标；对应 DTO 的 createTime） |

核心约束（用唯一键保证，不要在代码里写补丁逻辑）：

- 同一条“聚合通知”的唯一键：`(to_user_id, biz_type, target_type, target_id)`  
- 作用：实现 `INSERT ... ON DUPLICATE KEY UPDATE unread_count = unread_count + 1`  

注意（别写错，不然上线必出鬼）：`update_time` 表示“最后一次发生时间”，不是“最后一次修改时间”。  \n因此 DDL **禁止** `ON UPDATE CURRENT_TIMESTAMP`，避免 `markRead/markReadAll` 把已读顶到最前面，也避免 `createTime` 口径被“读操作”污染。

可复制 DDL（最小可用）：

```sql
CREATE TABLE `interaction_notification` (
  `notification_id` BIGINT NOT NULL,
  `to_user_id` BIGINT NOT NULL,
  `biz_type` VARCHAR(32) NOT NULL,
  `target_type` VARCHAR(16) NOT NULL,
  `target_id` BIGINT NOT NULL,
  `post_id` BIGINT NULL,
  `root_comment_id` BIGINT NULL,
  `last_actor_user_id` BIGINT NULL,
  `last_comment_id` BIGINT NULL,
  `unread_count` BIGINT NOT NULL DEFAULT 0,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`notification_id`),
  UNIQUE KEY `uk_user_biz_target` (`to_user_id`, `biz_type`, `target_type`, `target_id`),
  KEY `idx_user_time_id` (`to_user_id`, `update_time`, `notification_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='站内通知收件箱（聚合）';
```

### 5.2 biz_type 枚举（拍板）

只做 5 种（够用且不复杂）：

- `POST_LIKED`：你的 post 新增赞  
- `COMMENT_LIKED`：你的一级评论新增赞  
- `POST_COMMENTED`：你的 post 新增评论（仅 parentId=null）  
- `COMMENT_REPLIED`：你的评论新增回复（仅 parentId!=null，通知被回复的评论作者）  
- `COMMENT_MENTIONED`：有人在评论里提及你（见 6.1 的 COMMENT_MENTIONED 事件映射）  

---

## 6. 事件契约（谁负责发？谁负责收？）

> 目标：把“通知”变成旁路消费者，不污染点赞/评论主链路的数据结构。

### 6.1 统一事件：`InteractionNotifyEvent`（推荐）

用一个事件类型把 LIKE/COMMENT 两种来源统一掉，减少消费者的特殊情况：

字段（最小可用）：

- `eventType`：`LIKE_ADDED` / `COMMENT_CREATED` / `COMMENT_MENTIONED`
- `eventId`：事件唯一 ID（上线必做幂等；见 6.4）
- `fromUserId`：触发者
- `toUserId`：可选；仅 `COMMENT_MENTIONED` 必填（表示“被提及的人”）
- `targetType`：`POST` / `COMMENT`（表示“被互动的目标”）
- `targetId`：目标 ID（postId 或 commentId）
- `postId`：可选（COMMENT 强烈建议填；LIKE/POST 等于 targetId）
- `rootCommentId`：可选（两级盖楼定位用）
- `commentId`：可选（COMMENT_CREATED 时为新评论 ID）
- `tsMs`：事件时间戳
- `requestId`：可选（点赞链路已有）

生产时的字段映射（拍板）：

1) 点赞新增（LIKE_ADDED）：
   - `eventId=requestId`（点赞链路没有 requestId 也会生成；所以这里必定有）
   - POST：`targetType=POST, targetId=postId, postId=postId`
   - COMMENT：`targetType=COMMENT, targetId=commentId`（postId/rootCommentId 可空，由消费者回表补齐）

2) 评论创建（COMMENT_CREATED）：
   - `eventId=commentId`（用评论主键做幂等键即可）
   - 直接评论 post（parentId=null）：`targetType=POST, targetId=postId, postId=postId, commentId=新commentId`
   - 回复评论（parentId!=null）：`targetType=COMMENT, targetId=parentId, postId=postId, rootCommentId=rootId, commentId=新commentId`

3) 评论提及（COMMENT_MENTIONED）（上线必须）：
   - 触发：创建评论时，从评论文本按 `@username` 解析出 `mentionedUserIds`（userId 列表）
     - 前置（必须拍死）：`username` 在系统里必须全局唯一；否则 `@username` 没法确定“到底 @ 了谁”
     - 落地（必须做）：`user_base` 增加唯一键 `UNIQUE KEY uk_username(username)`，用数据约束保证“不重复”，别在代码里写补丁
     - 解析规则（后端实现，别让前端传 userId 列表来凑合）：
       - 从 `content` 中扫描所有 `@`，取其后连续的用户名片段（最长 64 个字符）
       - 用户名在遇到“空白/换行/Tab/再次出现 @”时结束；结尾如果带 `, . ; : ! ? ) ] } ，。！？）】》` 这类标点，先裁掉再查
       - 去重：同一条评论里重复 @ 同一 username，只算一次（忽略大小写与否由 username 的数据库比较规则决定）
       - 批量回表：`SELECT user_id, username FROM user_base WHERE username IN (...)` 得到 `mentionedUserIds`
       - 查不到的 username 直接忽略（不要因为 @ 不存在就让评论创建失败）
   - 过滤（拍板，消除重复通知）：
     - 去重：同一条评论里重复 @ 同一用户，只算一次
     - 排除自提及：`mentionedUserId == fromUserId` 直接丢弃
     - 主收件人去重：不在评论服务回表做过滤；统一由通知消费者按 7.1 规则丢弃（避免双通知）
   - 每个收件人发一条事件（不要塞数组，让消费者保持简单）：
     - `eventId = commentId + ":" + toUserId`
     - `toUserId = mentionedUserId`
     - 目标口径保持与 COMMENT_CREATED 一致（通知侧可复用同一套“Target -> Owner”解析主收件人，并在消费端顺便过滤掉“主收件人不再额外提及通知”）：
       - 直接评论 post（parentId=null）：`targetType=POST, targetId=postId, postId=postId`
       - 回复评论（parentId!=null）：`targetType=COMMENT, targetId=parentId, postId=postId, rootCommentId=rootId`
     - `commentId=新commentId`

### 6.2 生产者位置（按现有工程分层）

- 点赞：在 `ReactionLikeService.applyReaction(...)` 内，当且仅当 `delta == +1` 时发布 `LIKE_ADDED` 事件。  
- 评论：在“评论创建落库成功”后发布 `COMMENT_CREATED` 事件（评论服务目前是占位，实现时照抄 `.codex/comment-floor-system-implementation.md` 的事件点）。  
- `@提及`：同样在“评论创建落库成功”后发布 `COMMENT_MENTIONED`（按 6.1 的过滤规则，按收件人逐条发布）。  

### 6.3 消费者职责（单一入口）

消费者只做四件事（别加戏）：

0) 先做幂等去重：`eventId` 已处理过则直接丢弃（见 6.4）  
1) 解析事件 → 算出 `toUserId`（收件人；优先用 event.toUserId，否则按 7.1 回表解析）  
2) 过滤自互动：`toUserId == fromUserId` 直接丢弃  
3) 依据规则做一条 UPSERT：`unread_count += 1`，并更新 `last_*` 字段  

---

## 6.4 事件幂等（上线必做）：Notify Inbox（复用 Relation Inbox 思路）

RabbitMQ 的投递语义是“至少一次”。不做幂等，你的 `unread_count` 一定会在重试时被重复 +1。

最小做法：给通知消费者加一个 MySQL 收件箱表，用 `event_id` 做唯一键，插入成功才继续处理。

DDL（最小可用）：

```sql
CREATE TABLE `interaction_notify_inbox` (
  `event_id` VARCHAR(128) NOT NULL,
  `event_type` VARCHAR(32) NOT NULL,
  `payload` TEXT NULL,
  `status` VARCHAR(16) NOT NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`event_id`),
  KEY `idx_status_time` (`status`, `update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通知事件收件箱（幂等去重）';
```

消费者伪代码（照抄就能做）：

```pseudocode
handleNotify(event):
  if event == null or event.eventId is blank: return

  // 1) 幂等：只处理第一次出现的 eventId
  inserted = inbox.insertIgnore(eventId=event.eventId, eventType=event.eventType, payload=json(event), status='NEW')
  if !inserted: return

  try:
    // 2) 正常处理（7.x）
    processBusiness(event)
    inbox.updateStatus(event.eventId, 'DONE')
  catch e:
    inbox.updateStatus(event.eventId, 'FAIL')
    throw e  // 让 MQ 走死信/报警，别悄悄吞
```

说明：

- 这套模式在仓库里已经有同款实现（`relation_event_inbox`）；通知照抄即可。
- `eventId` 生成规则见 6.1（LIKE 用 requestId；COMMENT 用 commentId；MENTION 用 commentId:toUserId）。

---

## 7. 通知写入链路（核心：聚合 UPSERT）

### 7.1 收件人解析（Target -> Owner）

需要一个“目标归属解析器”，但它不需要聪明：

- POST：用内容仓储 `IContentRepository.findPost(postId)` 拿作者 `userId`。  
- COMMENT：用评论仓储 `ICommentRepository.getBrief(commentId)` 拿评论作者 `userId`，并可回填 `postId/rootId`。  

> 注意：评论仓储如果尚未实现，先按文档落地它；通知链路依赖它是合理的（数据归属必须来自真值表）。

提及去重规则（拍板，消除双通知）：

- 当 `eventType == COMMENT_MENTIONED` 时：解析出 `targetOwnerUserId`（按上面的 Target->Owner）。
- 若 `toUserId == targetOwnerUserId`：直接丢弃该提及事件（主收件人已经会收到 `POST_COMMENTED/COMMENT_REPLIED`，不要再多打一条提及通知）。

### 7.2 biz_type 推导（消除特殊情况）

pseudocode：

```
deriveBizType(event):
  if event.eventType == LIKE_ADDED and event.targetType == POST: return POST_LIKED
  if event.eventType == LIKE_ADDED and event.targetType == COMMENT: return COMMENT_LIKED
  if event.eventType == COMMENT_CREATED and event.targetType == POST: return POST_COMMENTED
  if event.eventType == COMMENT_CREATED and event.targetType == COMMENT: return COMMENT_REPLIED
  if event.eventType == COMMENT_MENTIONED: return COMMENT_MENTIONED
  return null
```

### 7.3 关键写入：UPSERT（一次 SQL 完成累加）

pseudocode（仓储层语义）：

```
upsertIncrement(toUserId, bizType, targetType, targetId, postId, rootCommentId, lastActorUserId, lastCommentId, delta=1):
  INSERT INTO interaction_notification(...)
  ON DUPLICATE KEY UPDATE
    unread_count = unread_count + delta,
    post_id = COALESCE(VALUES(post_id), post_id),
    root_comment_id = COALESCE(VALUES(root_comment_id), root_comment_id),
    last_actor_user_id = VALUES(last_actor_user_id),
    last_comment_id = COALESCE(VALUES(last_comment_id), last_comment_id),
    update_time = NOW()
```

这个设计的好处：

- 不需要“先查再改”避免并发问题。  
- DB 写入量按“聚合维度”收敛，而不是按事件明细爆炸。  

---

## 8. 通知读链路（/notification/list）

### 8.1 游标协议（复用现有范式）

参考内容 profile 的游标：`{createTimeMs}:{postId}`。  
通知用：`{updateTimeMs}:{notificationId}`，查询只取 `unread_count > 0`，排序：`update_time DESC, notification_id DESC`。  

### 8.2 List API 输出（在不破坏现有字段前提下扩展）

现有 `NotificationDTO` 字段必须保留：

- `title`
- `content`
- `createTime`

其中 `createTime` 固定取 `update_time`（最后一次发生时间，毫秒），不要取 `create_time`。

建议新增字段（都可选，不破坏兼容）：

- `notificationId`：用于 mark read 与稳定分页
- `bizType`：见 5.2
- `targetType/targetId`：跳转目标
- `postId/rootCommentId/lastCommentId`：用于精确跳转定位
- `unreadCount`：就是 `unread_count`

示例（只展示新增字段的意图）：

```json
{
  "notifications": [
    {
      "title": "帖子获赞",
      "content": "你的帖子新增 12 个赞",
      "createTime": 1700000000000,
      "notificationId": 10001,
      "bizType": "POST_LIKED",
      "targetType": "POST",
      "targetId": 90001,
      "postId": 90001,
      "unreadCount": 12
    }
  ],
  "nextCursor": "1700000000000:10001"
}
```

### 8.3 title/content 生成（不要在 DB 存字符串）

在 domain 里根据 `bizType + unreadCount` 生成即可，避免以后改文案要跑数据迁移。

pseudocode：

```
renderTitle(bizType):
  POST_LIKED -> "帖子获赞"
  COMMENT_LIKED -> "评论获赞"
  POST_COMMENTED -> "帖子被评论"
  COMMENT_REPLIED -> "评论被回复"
  COMMENT_MENTIONED -> "提及你"

renderContent(bizType, unreadCount):
  POST_LIKED -> "你的帖子新增 {n} 个赞"
  COMMENT_LIKED -> "你的评论新增 {n} 个赞"
  POST_COMMENTED -> "你的帖子新增 {n} 条评论"
  COMMENT_REPLIED -> "你的评论新增 {n} 条回复"
  COMMENT_MENTIONED -> "有人在评论里提及你 {n} 次"
```

---

## 9. 已读能力（否则“新增多少”永远清不掉）

> 这不是锦上添花，是必要闭环。

建议新增两个最小接口（不影响现有接口）：

- `POST /api/v1/notification/read`：标记单条已读（把 `unread_count` 置 0；已读后该行不会再出现在 `/notification/list`）  
- `POST /api/v1/notification/read/all`：全部标记已读（对 `to_user_id` 批量置 0）  

接口契约（最小可用，别再发明新 DTO）：

- 返回统一复用 `OperationResultDTO`（已有通用结构）。
- `/notification/read` 请求体：`{ "notificationId": 123 }`。
- `/notification/read/all`：空请求体即可。
- 幂等语义：重复调用永远返回 success=true（该用户没有对应通知也算成功，别给前端造麻烦）。

实现策略（简单粗暴）：

- 已读就是 `unread_count=0`，不引入额外“已读表”。  
- 标记已读**不得**修改 `update_time`（它是“最后一次发生时间”，不是“最后一次修改时间”）。  

---

## 10. 逐步实现清单（按模块拆，照抄就能做）

### Step 1：补齐数据库表

- 在 `project/nexus/docs/social_schema.sql` 追加：
  - `interaction_notification` DDL（见 5.1）
  - `interaction_notify_inbox` DDL（见 6.4）

### Step 2：nexus-types（事件定义）

- 新增 `InteractionNotifyEvent` 与 `EventType` 枚举（放在 `cn.nexus.types.event.interaction` 或类似目录）。  

### Step 3：domain（读接口与业务拼装）

- `InteractionService.notifications(...)` 改为真实实现：调用通知仓储分页读取，生成 `NotificationVO`（含 bizType/ids/unreadCount）。  
- 保持 DTO 兼容：Controller 仍返回 `title/content/createTime`，并把新增字段透传。  

### Step 4：infrastructure（MyBatis DAO/Mapper + 仓储实现）

- 新增 PO/DAO/Mapper.xml：`interaction_notification` 的 `pageByUser`、`upsertIncrement`、`markRead`、`markReadAll`。  

### Step 5：trigger（MQ consumer + 生产者挂载点）

- 增加一个 Rabbit consumer：消费 `InteractionNotifyEvent`，解析收件人并做 UPSERT。  
- 点赞生产：`ReactionLikeService.applyReaction` 在 `delta==+1` 时发 `LIKE_ADDED` 事件。  
- 评论生产：评论创建落库成功后发 `COMMENT_CREATED` 事件。  

---

## 11. 最小自测（本地 AI 可执行，无需 CI）

1) 给某个 post 连续点两次赞（ADD）：  
   - 期望：通知 `POST_LIKED` 的 `unread_count` 只 +1（第二次 delta=0 不发通知）。  
2) 对该 post 再取消赞（REMOVE）：  
   - 期望：不产生通知（只统计新增）。  
3) 别人评论你的 post（parentId=null）：  
   - 期望：你收到 `POST_COMMENTED`，`unread_count +1`。  
4) 别人回复你的评论（parentId!=null）：  
   - 期望：你收到 `COMMENT_REPLIED`，不再额外收到 `POST_COMMENTED`（消除重复）。  
5) 调用 `/notification/list`：  
   - 期望：返回按 `update_time` 倒序；`nextCursor` 形如 `{updateTimeMs}:{notificationId}`。  
6) 调用 `/notification/read`：  
   - 期望：该条 `unread_count` 变 0；再次 list 文案显示“新增 0”或直接前端隐藏红点。  
7) 发表评论并 `@` 另一个用户：  
   - 期望：被 @ 的用户收到 `COMMENT_MENTIONED`；且如果该用户本来就会收到 `POST_COMMENTED/COMMENT_REPLIED`，则不再额外产生一条提及通知（避免双通知）。  
