# 评论领域实现文档（基于代码，可直接按路径看实现）

适用人群：新入职后端同学（第一次接触评论/互动/通知这套代码）  
目标：你看完后，能从 **HTTP 接口 → 领域服务 → MySQL/Redis/MQ** 把每条业务链路串起来，并且知道“为什么这么设计”。  

> 这份文档只讲“代码里已经实现的东西”。每个结论都给出对应的代码位置（文件路径 + 关键方法/类名）。  

---

## 0. 你先记住这 5 条“硬规则”（否则你会写错）

1) **两级盖楼**：一级评论 `root_id = NULL`；楼内回复 `root_id = 所属一级 comment_id`。  
   - 真值表：`project/nexus/docs/social_schema.sql`（`interaction_comment`）  
   - 写入计算：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/InteractionService.java` 的 `comment()`

2) **热榜只排一级评论**（楼内回复不进热榜）。  
   - 消费者：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/CommentCreatedConsumer.java` 的 `onMessage()`

3) **置顶一帖只能 1 条，且置顶不参与分页**（接口返回 `pinned` 单独字段）。  
   - 置顶表：`project/nexus/docs/social_schema.sql`（`interaction_comment_pin`）  
   - 分页排除 pinned：`project/nexus/nexus-infrastructure/src/main/resources/mapper/social/CommentMapper.xml` 的 `pageRootIds`  
   - 读接口返回结构：`project/nexus/nexus-api/src/main/java/cn/nexus/api/social/interaction/dto/CommentListResponseDTO.java`

4) **点赞只允许一级评论**（楼内回复禁止点赞）。  
   - 入口校验：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/InteractionService.java` 的 `parseTarget()`

5) **删除是软删（幂等）**：读接口只返回 `status=1`；删除后不做“占位”。  
   - 软删 SQL：`project/nexus/nexus-infrastructure/src/main/resources/mapper/social/CommentMapper.xml` 的 `softDelete/softDeleteByRootId`  
   - 读侧过滤：同文件的 `pageRootIds/pageReplyIds` 都带 `status = 1`  
   - 删除入口：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/InteractionService.java` 的 `deleteComment()`

---

## 1. 代码地图：你该从哪几个文件开始读

### 1.1 HTTP 入口（Controller）

- 发表评论/点赞/置顶：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/InteractionController.java`  
  - `comment()` → `IInteractionService.comment()`  
  - `react()` / `reactionState()` → `IInteractionService.react()` / `reactionState()`  
  - `pinComment()` → `IInteractionService.pinComment()`
- 评论读/删：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/CommentController.java`  
  - `list()` / `replyList()` / `hot()` → `ICommentQueryService`  
  - `delete()` → `IInteractionService.deleteComment()`

### 1.2 领域服务（业务主流程）

- 评论写侧（创建/置顶/删除 + 评论点赞的回写事件）：  
  `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/InteractionService.java`
- 评论读侧（列表/楼内回复/热榜，补全昵称头像）：  
  `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/CommentQueryService.java`
- 热榜重建（Redis 丢 key 时恢复）：  
  `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/CommentHotRankRebuildService.java`  
  + 启动器：`project/nexus/nexus-app/src/main/java/cn/nexus/config/CommentHotRankRebuildRunner.java`

### 1.3 存储与中间件（MySQL / Redis / MQ）

- MySQL 评论仓储：  
  - 接口：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/adapter/repository/ICommentRepository.java`  
  - 实现：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/CommentRepository.java`  
  - SQL：`project/nexus/nexus-infrastructure/src/main/resources/mapper/social/CommentMapper.xml`
- 置顶仓储：  
  - 接口：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/adapter/repository/ICommentPinRepository.java`  
  - 实现：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/CommentPinRepository.java`  
  - SQL：`project/nexus/nexus-infrastructure/src/main/resources/mapper/social/CommentPinMapper.xml`
- 热榜仓储（Redis ZSET）：  
  - 接口：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/adapter/repository/ICommentHotRankRepository.java`  
  - 实现：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/CommentHotRankRepository.java`
- 评论 MQ 拓扑：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/config/InteractionCommentMqConfig.java`
- 评论计数/热榜消费者：  
  - `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/CommentCreatedConsumer.java`  
  - `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/RootReplyCountChangedConsumer.java`  
  - `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/CommentLikeChangedConsumer.java`
- MQ 幂等收件箱（去重）：  
  - 端口：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/adapter/port/IInteractionCommentInboxPort.java`  
  - 实现：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/InteractionCommentInboxPort.java`  
  - SQL：`project/nexus/nexus-infrastructure/src/main/resources/mapper/social/InteractionCommentInboxMapper.xml`  
  - 表：`project/nexus/docs/social_schema.sql`（`interaction_comment_inbox`）

---

## 2. 数据结构：为什么“root_id 写对”就不需要递归

### 2.1 MySQL：interaction_comment（真值）

这张表承载“评论内容 + 楼内关系 + 可派生的计数”。表结构以 `root_id` 为核心：

- 一级评论：`root_id = NULL`
- 楼内回复：`root_id = 一级 comment_id`

对应代码/SQL：

- 表定义：`project/nexus/docs/social_schema.sql`（`interaction_comment`）  
- 插入字段：`project/nexus/nexus-infrastructure/src/main/resources/mapper/social/CommentMapper.xml` 的 `insert`  
- 写入逻辑：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/InteractionService.java` 的 `comment()`

> 关键好处：读侧要“拿到一个楼的所有回复”，只要用 `WHERE root_id = ?` 就行，不用做递归查询。  
> 这就是 `CommentMapper.xml` 里 `pageReplyIds` 的查询方式。

### 2.2 MySQL：interaction_comment_pin（一帖一条置顶）

- 主键是 `post_id`，天然保证“一帖最多一条置顶”。  
  - DDL：`project/nexus/docs/social_schema.sql`（`interaction_comment_pin`）  
  - Upsert：`project/nexus/nexus-infrastructure/src/main/resources/mapper/social/CommentPinMapper.xml` 的 `insertOrUpdate`

### 2.3 Redis：comment:hot:{postId}（热榜派生缓存）

- Key：`comment:hot:{postId}`  
- 类型：ZSET  
- member：一级评论 `commentId`  
- score：热度分（当前实现：`like_count*10 + reply_count*20`）

对应代码：

- 接口约定：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/adapter/repository/ICommentHotRankRepository.java`  
- Redis 实现：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/CommentHotRankRepository.java`
- 分数计算：  
  - `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/RootReplyCountChangedConsumer.java` 的 `refreshHotRankBestEffort()`  
  - `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/CommentLikeChangedConsumer.java` 的 `refreshHotRankBestEffort()`  
  - 重建：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/CommentHotRankRebuildService.java` 的 `rebuildForPost()`

### 2.4 MQ 幂等：interaction_comment_inbox（防重复加计数）

RabbitMQ 是“至少一次投递”（你要假设消息可能重复）。所以消费者侧必须去重，否则 `like_count/reply_count` 会被重复累加。

对应代码：

- Inbox 端口：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/adapter/port/IInteractionCommentInboxPort.java`  
- Consumer 使用：  
  - `RootReplyCountChangedConsumer.onMessage()`：`inboxPort.save(event.getEventId(), ...)`  
  - `CommentLikeChangedConsumer.onMessage()`：同样的去重逻辑  
- 落库 SQL：`project/nexus/nexus-infrastructure/src/main/resources/mapper/social/InteractionCommentInboxMapper.xml`（`INSERT IGNORE`）

---

## 3. 写侧业务：从一个 HTTP 请求到底层发生了什么

下面按“你会真正调用的接口”来讲。

### 3.1 发表评论 / 回复（POST /api/v1/interact/comment）

入口与 DTO：

- HTTP：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/InteractionController.java` 的 `comment()`  
- DTO：`project/nexus/nexus-api/src/main/java/cn/nexus/api/social/interaction/dto/CommentRequestDTO.java`
  - `@JsonIgnoreProperties(ignoreUnknown = true)`：为了兼容旧客户端可能还会传 `mentions` 字段（后端忽略）。

核心业务在 `InteractionService.comment()`：

- 代码：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/InteractionService.java` 的 `comment()`

它做的事（按顺序）：

1) 生成 `commentId` 和 `nowMs`  
   - `ISocialIdPort.nextId()/now()`：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/adapter/port/ISocialIdPort.java`
2) 计算 `rootId / parentIdToSave / replyToId`  
   - `parentId == null`：一级评论（`rootId=null`）  
   - `parentId != null`：先查父评论 `getBrief()`，再计算  
     - `rootId = (parent.rootId == null) ? parent.commentId : parent.rootId`  
3) 写 MySQL：`commentRepository.insert(...)`  
   - 仓储接口：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/adapter/repository/ICommentRepository.java`  
   - MyBatis：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/CommentRepository.java`
4) 发事件（都用 try/catch 包起来，失败也不阻塞主链路）  
   - 评论创建：`CommentCreatedEvent` → `ICommentEventPort.publish()`  
     - port：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/adapter/port/ICommentEventPort.java`  
     - MQ 实现：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/CommentEventPort.java`
   - 通知：`InteractionNotifyEvent`（`COMMENT_CREATED` + `COMMENT_MENTIONED`）  
     - 组装：`InteractionService.publishNotifyCommentCreated()/publishNotifyCommentMentioned()`  
     - port：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/adapter/port/IInteractionNotifyEventPort.java`
   - 如果是回复：发 `RootReplyCountChangedEvent(delta=+1)`  
     - `InteractionService.publishReplyCountChanged()`

关键代码摘录（来自 `InteractionService.comment()`，看这段就能知道 rootId 怎么算、事件怎么发）：

```java
Long rootId = null;
Long parentIdToSave = null;
Long replyToId = null;

if (parentId != null) {
    CommentBriefVO parent = commentRepository.getBrief(parentId);
    if (parent == null
            || parent.getPostId() == null
            || !postId.equals(parent.getPostId())
            || parent.getStatus() == null
            || parent.getStatus() != 1) {
        throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), ResponseCode.ILLEGAL_PARAMETER.getInfo());
    }
    rootId = parent.getRootId() == null ? parent.getCommentId() : parent.getRootId();
    parentIdToSave = parent.getCommentId();
    replyToId = parent.getCommentId();
}

commentRepository.insert(commentId, postId, userId, rootId, parentIdToSave, replyToId, content, nowMs);

publishCreated(commentId, postId, rootId, userId, nowMs);
publishNotifyCommentCreated(commentId, postId, rootId, parentIdToSave, userId, nowMs);
publishNotifyCommentMentioned(commentId, postId, rootId, parentIdToSave, userId, nowMs, content);
if (rootId != null) {
    publishReplyCountChanged(rootId, postId, +1L, nowMs);
}
```

对应“回复数 + 热榜分数”真正落库的地方在 MQ 消费者（异步最终一致）：

- `RootReplyCountChangedConsumer.onMessage()`：  
  `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/RootReplyCountChangedConsumer.java`

### 3.2 评论点赞（POST /api/v1/interact/reaction）

评论点赞不是直接改 `interaction_comment.like_count`，而是分两段：

1) 在线状态（我是否点过赞 + 近实时计数）由“点赞子域”处理（Redis + 延迟落库）。  
2) 评论表里的 `like_count` 由“评论子域的 MQ 派生链路”异步回写（最终一致），并驱动热榜更新。

入口：

- HTTP：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/InteractionController.java` 的 `react()`  
- 领域服务：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/InteractionService.java` 的 `react()`

关键约束：**楼内回复不允许点赞**。校验点在 `parseTarget()`：

- `InteractionService.parseTarget()` 会查 `commentRepository.getBrief(targetId)`，并要求：
  - `status == 1`
  - `rootId == null`（否则抛错“楼内回复不允许点赞”）

代码：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/InteractionService.java` 的 `parseTarget()`

关键代码摘录（来自 `InteractionService.parseTarget()`）：

```java
// 业务约束：楼内回复不允许被点赞（只允许点赞一级评论）
if (targetTypeEnum == ReactionTargetTypeEnumVO.COMMENT) {
    CommentBriefVO c = commentRepository.getBrief(targetId);
    if (c == null || c.getStatus() == null || c.getStatus() != 1) {
        throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "评论不存在或已删除");
    }
    if (c.getRootId() != null) {
        throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "楼内回复不允许点赞");
    }
}
```

当点赞目标是 COMMENT 时，`InteractionService.react()` 还会做一件事：

- 从 `ReactionResultVO.delta` 推出 `+1/-1`  
- 发 `CommentLikeChangedEvent(rootCommentId=commentId, delta=...)`  
- 交给 MQ 消费者去更新 `interaction_comment.like_count` + 刷新热榜分数

消费者：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/CommentLikeChangedConsumer.java`

> 补充：点赞链路本身也会发通知事件 LIKE_ADDED（评论/帖子都走同一套通知旁路）。  
> 代码在点赞子域：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/ReactionLikeService.java` 的 `publishNotifyLikeAdded()`

### 3.3 置顶评论（POST /api/v1/interact/comment/pin）

入口：

- HTTP：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/InteractionController.java` 的 `pinComment()`
- 领域服务：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/InteractionService.java` 的 `pinComment()`

业务规则（代码里写死的）：

1) 只有 **帖子作者** 能置顶  
   - `contentRepository.findPost(postId)` → 比较 `post.userId == userId`
2) 只能置顶 **一级评论**，且必须是正常状态  
   - `c.getRootId() == null && c.getStatus() == 1`

置顶落库：

- `commentPinRepository.pin(postId, commentId, nowMs)`  
  - 实现：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/CommentPinRepository.java`（`insertOrUpdate` upsert）

### 3.4 删除评论（DELETE /api/v1/comment/{commentId}）

入口：

- HTTP：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/CommentController.java` 的 `delete()`  
- 领域服务：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/InteractionService.java` 的 `deleteComment()`

权限规则（代码实现）：

- 帖子作者：可删本帖任意评论  
- 评论作者：只能删自己的评论  

代码：`InteractionService.hasDeletePermission()`（同文件）

删除分两种情况（用 `rootId` 一眼区分）：

1) 删除回复（`rootId != null`）  
   - 先 `commentRepository.softDelete(commentId)`（只有 status=1→2 成功才算“本次真的删了”）  
   - 如果本次真的删了：发 `RootReplyCountChangedEvent(delta=-1)` 去异步扣减一级评论 `reply_count`  
2) 删除一级评论（`rootId == null`）  
   - 软删自己：`softDelete(commentId)`  
   - **同步级联**软删楼内所有回复：`softDeleteByRootId(commentId)`  
   - 清理热榜：`commentHotRankRepository.remove(postId, commentId)`（best effort，失败只 warn）  
   - 如果当前是置顶：`commentPinRepository.clearIfPinned(postId, commentId)`

关键代码摘录（来自 `InteractionService.deleteComment()`）：

```java
// 回复：只有 status=1->2 成功才允许扣 reply_count
if (c.getRootId() != null) {
    boolean deleted = commentRepository.softDelete(commentId, nowMs);
    if (deleted) {
        publishReplyCountChanged(c.getRootId(), c.getPostId(), -1L, nowMs);
    }
    return ok(commentId, "DELETED", "已删除");
}

// 一级评论：同步级联删楼内回复 + 清理热榜/置顶
commentRepository.softDelete(commentId, nowMs);
commentRepository.softDeleteByRootId(commentId, nowMs);

try {
    commentHotRankRepository.remove(c.getPostId(), commentId);
} catch (Exception e) {
    log.warn("comment hot rank remove failed, postId={}, commentId={}", c.getPostId(), commentId, e);
}

commentPinRepository.clearIfPinned(c.getPostId(), commentId);
return ok(commentId, "DELETED", "已删除");
```

软删 SQL 在 `CommentMapper.xml`：

- `project/nexus/nexus-infrastructure/src/main/resources/mapper/social/CommentMapper.xml` 的 `softDelete/softDeleteByRootId`

---

## 4. 读侧业务：列表/楼内/热榜是怎么拼出来的

读侧的核心目标就 2 个：

1) **不递归**（root_id 设计已经帮你做掉了）  
2) **不 N+1**（用户昵称/头像必须批量补全）

### 4.1 一级评论列表（GET /api/v1/comment/list）

入口：

- `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/CommentController.java` 的 `list()`
- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/CommentQueryService.java` 的 `listRootComments()`

流程（按代码顺序）：

1) 查 pinnedId：`commentPinRepository.getPinnedCommentId(postId)`  
2) 加载 pinned（如果 pinned 失效会自动清理）  
   - `CommentQueryService.loadPinned()`：校验 `postId`、`rootId==null`、`status==1`，否则 `commentPinRepository.clear(postId)`

关键代码摘录（来自 `CommentQueryService.loadPinned()`，你会看到“失效就清理”的自动修复逻辑）：

```java
private RootCommentViewVO loadPinned(Long postId, Long pinnedId, int preload) {
    if (postId == null || pinnedId == null) {
        return null;
    }
    List<CommentViewVO> list = loadComments(List.of(pinnedId));
    if (list.isEmpty()) {
        commentPinRepository.clear(postId);
        return null;
    }
    CommentViewVO root = list.get(0);
    if (root == null
            || root.getPostId() == null
            || !postId.equals(root.getPostId())
            || root.getRootId() != null
            || root.getStatus() == null
            || root.getStatus() != 1) {
        commentPinRepository.clear(postId);
        return null;
    }

    List<CommentViewVO> preview = loadRepliesPreview(root.getCommentId(), preload);
    return RootCommentViewVO.builder().root(root).repliesPreview(preview).build();
}
```
3) 分页查 rootIds（排除 pinned）  
   - `ICommentRepository.pageRootCommentIds()`  
   - SQL：`CommentMapper.xml` 的 `pageRootIds`（`ORDER BY create_time DESC, comment_id DESC`）
4) 批量回表加载 root 评论详情（并保持顺序）  
   - `CommentQueryService.loadComments()`：先 `listByIds(ids)`，再用 map 重排为入参顺序

关键代码摘录（来自 `CommentQueryService.loadComments()`，它解释了“为什么先查 ID 再批量回表”还能保持顺序）：

```java
private List<CommentViewVO> loadComments(List<Long> ids) {
    if (ids == null || ids.isEmpty()) {
        return List.of();
    }
    List<CommentViewVO> list = commentRepository.listByIds(ids);
    if (list == null || list.isEmpty()) {
        return List.of();
    }
    Map<Long, CommentViewVO> map = new HashMap<>(list.size() * 2);
    for (CommentViewVO vo : list) {
        if (vo == null || vo.getCommentId() == null) {
            continue;
        }
        map.put(vo.getCommentId(), vo);
    }
    List<CommentViewVO> ordered = new ArrayList<>(ids.size());
    for (Long id : ids) {
        CommentViewVO vo = map.get(id);
        if (vo != null) {
            ordered.add(vo);
        }
    }
    return ordered;
}
```
5) 每条 root 预加载前 N 条回复（楼内预览）  
   - `loadRepliesPreview()` → `pageReplyCommentIds(rootId, cursor=null, limit=preload)`
6) 批量补全作者昵称/头像  
   - `enrichUserProfile()` → `userBaseRepository.listByUserIds(...)`  
   - 接口约束：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/adapter/repository/IUserBaseRepository.java`
7) 生成 nextCursor（只基于 items，不把 pinned 算进分页）  
   - `nextCursor = "{lastCreateTime}:{lastCommentId}"`

### 4.2 楼内回复列表（GET /api/v1/comment/reply/list）

入口：

- `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/CommentController.java` 的 `replyList()`
- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/CommentQueryService.java` 的 `listReplies()`

分页规则（SQL 是真相）：

- SQL：`project/nexus/nexus-infrastructure/src/main/resources/mapper/social/CommentMapper.xml` 的 `pageReplyIds`
  - `ORDER BY create_time ASC, comment_id ASC`（楼内回复按时间正序）
  - cursor 条件是 “大于上一个”：
    - `create_time > cursorTime OR (create_time = cursorTime AND comment_id > cursorId)`

关键 SQL 摘录（分页规则以 SQL 为准，别靠脑补）：

```xml
<select id="pageRootIds" resultType="java.lang.Long">
    SELECT comment_id
    FROM interaction_comment
    WHERE post_id = #{postId}
      AND root_id IS NULL
      AND status = 1
      <if test="pinnedId != null">
        AND comment_id &lt;&gt; #{pinnedId}
      </if>
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
```

同样会批量补全用户信息（`enrichUserProfile(items)`）。

### 4.3 热榜（GET /api/v1/comment/hot）

入口：

- `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/CommentController.java` 的 `hot()`
- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/CommentQueryService.java` 的 `hotComments()`

流程：

1) 同样先加载 pinned（并排除 pinned 出现在热榜 items）  
2) 从 Redis 取 topIds：`commentHotRankRepository.topIds(postId, limit+1)`  
3) 过滤 pinnedId，截断到 limit  
4) 批量回表 + 预加载回复预览 + 批量补全用户信息

注意一个“现实世界的坑”（代码已经考虑）：热榜可能出现脏数据（比如 commentId 不存在/已删）。  
处理方式是“跳过而不是整页失败”：

- `CommentHotRankRepository.topIds()`：解析 Long 失败就 continue  
- `CommentQueryService.loadRootsWithPreview()`：`status!=1` 或 `rootId!=null` 就跳过

对应代码见：

- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/CommentHotRankRepository.java` 的 `topIds()`  
- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/CommentQueryService.java` 的 `loadRootsWithPreview()`

---

## 5. 异步链路：计数、热榜、通知为什么要“拆出去”

一句话：**写评论的主链路只做“落库 + 发事件”，别让它被 Redis/MQ/计数拖死**。

### 5.1 评论相关 MQ 拓扑（exchange / routing key / queue）

配置在：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/config/InteractionCommentMqConfig.java`

- exchange：`social.interaction`
- rk + queue：
  - `comment.created` → `interaction.comment.created.queue`
  - `comment.reply_count.changed` → `interaction.comment.reply_count.changed.queue`
  - `comment.like.changed` → `interaction.comment.like.changed.queue`

生产者在 `CommentEventPort`：

- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/CommentEventPort.java`

### 5.2 为什么消费者里要“afterCommit 再刷热榜”

`RootReplyCountChangedConsumer` 和 `CommentLikeChangedConsumer` 都做了同一件事：

- 先更新 MySQL 的 `reply_count/like_count`
- 再在 **事务提交后** 更新 Redis 热榜分数

原因很简单：如果 DB 事务没提交就先改热榜，你会得到“Redis 里显示加了，但 DB 实际没加”的脏状态。

对应代码：

- `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/RootReplyCountChangedConsumer.java` 的 `registerHotRankAfterCommit()`  
- `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/CommentLikeChangedConsumer.java` 的 `registerHotRankAfterCommit()`

关键代码摘录（来自 `RootReplyCountChangedConsumer`；`CommentLikeChangedConsumer` 结构几乎一样）：

```java
if (!inboxPort.save(event.getEventId(), EVENT_TYPE, null)) {
    return;
}
commentRepository.addReplyCount(event.getRootCommentId(), event.getDelta());

CommentBriefVO root = commentRepository.getBrief(event.getRootCommentId());
if (root == null) {
    return;
}
registerHotRankAfterCommit(event.getPostId(), event.getRootCommentId(), root);

private void registerHotRankAfterCommit(Long postId, Long rootCommentId, CommentBriefVO root) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
        refreshHotRankBestEffort(postId, rootCommentId, root);
        return;
    }
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
            refreshHotRankBestEffort(postId, rootCommentId, root);
        }
    });
}

private void refreshHotRankBestEffort(Long postId, Long rootCommentId, CommentBriefVO root) {
    if (root.getStatus() == null || root.getStatus() != 1) {
        hotRankRepository.remove(postId, rootCommentId);
        return;
    }
    double score = safe(root.getLikeCount()) * 10D + safe(root.getReplyCount()) * 20D;
    hotRankRepository.upsert(postId, rootCommentId, score);
}
```

### 5.3 通知旁路（评论创建、@提及、点赞）

评论写侧会发两类通知事件：

- `COMMENT_CREATED`：在 `InteractionService.publishNotifyCommentCreated()`  
- `COMMENT_MENTIONED`：在 `InteractionService.publishNotifyCommentMentioned()`（会从 content 解析 `@username`）

代码：

- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/InteractionService.java`

点赞链路会发 `LIKE_ADDED`：

- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/ReactionLikeService.java` 的 `publishNotifyLikeAdded()`

通知事件统一由消费者落库聚合：

- 消费者：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/InteractionNotifyConsumer.java`
  - 幂等去重：`IInteractionNotifyInboxPort.save()`（另一个 inbox 表）  
  - 回表解析“目标归属”（POST/COMMENT）后做 UPSERT 聚合

关键代码摘录（来自 `InteractionService.publishNotifyCommentMentioned()`；重点看 eventId 的拼法）：

```java
String targetType = rootId == null ? "POST" : "COMMENT";
Long targetId = rootId == null ? postId : parentId;
if (targetId == null) {
    return;
}

for (Long toUserId : mentionedUserIds) {
    InteractionNotifyEvent event = new InteractionNotifyEvent();
    event.setEventType(EventType.COMMENT_MENTIONED);
    event.setEventId(commentId + ":" + toUserId);
    event.setFromUserId(fromUserId);
    event.setToUserId(toUserId);
    event.setTargetType(targetType);
    event.setTargetId(targetId);
    event.setPostId(postId);
    event.setRootCommentId(rootId);
    event.setCommentId(commentId);
    event.setTsMs(nowMs);
    interactionNotifyEventPort.publish(event);
}
```

---

## 6. API 速查：你不用翻 Controller 也能知道怎么调用

### 6.1 发表评论

- Path：`POST /api/v1/interact/comment`  
- Request：`CommentRequestDTO`（`postId`, `parentId`, `content`）  
  - DTO：`project/nexus/nexus-api/src/main/java/cn/nexus/api/social/interaction/dto/CommentRequestDTO.java`
- Response：`CommentResponseDTO`（`commentId`, `createTime`）  
  - DTO：`project/nexus/nexus-api/src/main/java/cn/nexus/api/social/interaction/dto/CommentResponseDTO.java`

示例（只看形状）：

```json
{
  "postId": 1001,
  "parentId": null,
  "content": "第一！@alice"
}
```

### 6.2 一级评论列表（含置顶 + 回复预览）

- Path：`GET /api/v1/comment/list`  
- Request：`CommentListRequestDTO`（`postId`, `cursor`, `limit`, `preloadReplyLimit`）  
  - DTO：`project/nexus/nexus-api/src/main/java/cn/nexus/api/social/interaction/dto/CommentListRequestDTO.java`
- Response：`CommentListResponseDTO`（`pinned`, `items`, `nextCursor`）  
  - DTO：`project/nexus/nexus-api/src/main/java/cn/nexus/api/social/interaction/dto/CommentListResponseDTO.java`

### 6.3 楼内回复列表

- Path：`GET /api/v1/comment/reply/list`  
- Request：`CommentReplyListRequestDTO`（`rootId`, `cursor`, `limit`）  
  - DTO：`project/nexus/nexus-api/src/main/java/cn/nexus/api/social/interaction/dto/CommentReplyListRequestDTO.java`

### 6.4 热榜

- Path：`GET /api/v1/comment/hot`  
- Request：`CommentHotRequestDTO`（`postId`, `limit`, `preloadReplyLimit`）  
  - DTO：`project/nexus/nexus-api/src/main/java/cn/nexus/api/social/interaction/dto/CommentHotRequestDTO.java`

### 6.5 删除评论

- Path：`DELETE /api/v1/comment/{commentId}`  
- Response：`OperationResultDTO`  
  - DTO：`project/nexus/nexus-api/src/main/java/cn/nexus/api/social/common/OperationResultDTO.java`

### 6.6 点赞评论（只允许一级评论）

- Path：`POST /api/v1/interact/reaction`  
- Request：`ReactionRequestDTO`（其中 `targetType=COMMENT`）  
- 关键限制：楼内回复会被 `parseTarget()` 拦掉  

对应入口：

- `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/InteractionController.java` 的 `react()`  
- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/InteractionService.java` 的 `parseTarget()`

---

## 7. 快速自检清单（你改代码前先对一遍）

1) 你有没有破坏 “root_id 语义”？  
   - 看 `InteractionService.comment()` 和 `interaction_comment` 表定义
2) 置顶是不是还保证“一帖一条，且不进分页”？  
   - 看 `CommentMapper.xml.pageRootIds` 是否排除了 pinnedId  
3) 楼内回复有没有被错误地允许点赞？  
   - 看 `InteractionService.parseTarget()` 对 `rootId` 的判断
4) 计数链路是否还能抵抗 MQ 重复投递？  
   - 看 `interaction_comment_inbox` + `inboxPort.save(...)`
5) 读接口是否仍然批量补全用户信息（没有 N+1）？  
   - 看 `CommentQueryService.enrichUserProfile(...)` 是否还在批量查 `userBaseRepository.listByUserIds(...)`

---

## 8. 附录：最常用的“看哪里就能定位问题”

- “评论发出去了，但热榜/计数没变”：  
  - 先看 MQ 是否消费：`CommentCreatedConsumer` / `RootReplyCountChangedConsumer` / `CommentLikeChangedConsumer`  
  - 再看 inbox 是否挡住了重复：`interaction_comment_inbox`（`event_id` 是否已存在）  
  - 再看 Redis key：`comment:hot:{postId}`
- “置顶不见了”：  
  - `CommentQueryService.loadPinned()` 会在 pinned 失效时自动 `clear(postId)`（例如评论已删/不是一级/不属于该帖）
- “列表里昵称头像是空的”：  
  - `CommentViewVO` 的 `nickname/avatarUrl` 是读侧补全，不在评论表里  
  - 看 `IUserBaseRepository` 的实现 `UserBaseRepository`
