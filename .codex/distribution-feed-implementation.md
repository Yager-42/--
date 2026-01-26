# 分发与 Feed 服务实现方案（实现级文档，可直接照着写代码，执行者：Codex / 日期：2026-01-12）

> 目标：在 **不改变现有接口契约**（`/api/v1/feed/*` 与现有 DTO 字段）的前提下，把“分发 + Feed 时间线”从占位实现落地为可运行的真实链路。  
> 本文档的详细程度目标：**任何一个新来的 Codex agent，不了解项目也能按本文落地 Phase 1（MVP）**。

---

## 0. 你需要先知道的三件事（不懂也没关系，照做就行）

### 0.1 项目结构（Maven 多模块 + DDD 分层）

项目根目录：`project/nexus`

- `project/nexus/nexus-trigger`：HTTP/MQ 入口（Controller、@RabbitListener）
- `project/nexus/nexus-api`：接口与 DTO（契约层）
- `project/nexus/nexus-domain`：领域服务（业务编排），只依赖 `nexus-types`
- `project/nexus/nexus-infrastructure`：MyBatis/Redis/RabbitMQ 等技术实现（domain 的接口实现）
- `project/nexus/nexus-types`：全局枚举/事件/异常

分层规范参考：`.codex/DDD-ARCHITECTURE-SPECIFICATION.md`

### 0.2 现有 Feed 接口已存在，但实现是“占位”

- HTTP：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/FeedController.java`
- Domain：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/FeedService.java`（当前返回伪造数据）
- Domain 接口：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/IFeedService.java`

你的工作是：**不改 Controller/DTO，不改 API 契约，把 FeedService 换成真实实现**。

### 0.3 内容发布成功后会触发分发端口（这是接入点）

`ContentService.publish` 在成功分支会触发：
- `IContentDispatchPort.onPublished(postId, userId)`

上线约束（必须遵守）：
- 必须在事务提交后（after-commit）再触发 `onPublished`，否则 MQ 可能读到未提交数据，导致 feed 读侧误判并清理索引（“刚发布就消失”）。
- 当前代码已按 after-commit 落地（参考 `ContentService.publish` 内的 `dispatchAfterCommit(...)`），不要改回“事务内直接发事件”。

相关文件：
- 调用点：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/ContentService.java`
- 端口定义：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/adapter/port/IContentDispatchPort.java`
- 现有实现（仅日志，占位）：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/ContentDispatchPort.java`

我们的“Post_Published → MQ → fanout”链路应该从这里开始做，不要在 domain 里直接发 MQ。

### 0.4 编码规范（必须遵守，不然实现者会写出垃圾）

来自 `.codex/DDD-ARCHITECTURE-SPECIFICATION.md` 的硬要求（写代码前先记住）：
- 每个新建的 Java 类文件需要类注释，包含 `@author` 与 `@since yyyy-MM-dd`
- 所有 `public/protected` 的类/方法/字段都要写中文 JavaDoc，并补齐 `@param` / `@return`
- 领域层（domain）不允许直接依赖 DAO/Redis/RabbitMQ 客户端，只能依赖端口/仓储接口

### 0.5 实现进度 Checklist（合并版，自动更新）

> 更新时间：2026-01-26  
> 说明：
> - `[x]` 代表“代码已实现并已合入仓库”
> - `[ ]` 代表“未实现 / 仅方案（文档里写了，但代码还没落地）”
> - 本次执行本地编译（`mvn -DskipTests package`）；不跑单测；验证记录在 `.codex/testing.md` 与 `verification.md`

#### Phase 1（MVP：写扩散 + timeline + profile + 负反馈）

- [x] 新增 `PostPublishedEvent`（`nexus-types`）
- [x] 新增 `FeedFanoutConfig` + `FeedFanoutDispatcherConsumer` + `FeedFanoutTaskConsumer`（`nexus-trigger`）
- [x] 改造 `ContentDispatchPort`：发布 MQ 消息（`nexus-infrastructure`）
- [x] 新增 `IFeedDistributionService` + `FeedDistributionService.fanout`（`nexus-domain`）
- [x] 粉丝分页查询：`IFollowerDao` + `IRelationRepository.listFollowerIds`（MyBatis）
- [x] Redis InboxTimeline：`IFeedTimelineRepository` + `FeedTimelineRepository`（ZSET）
- [x] Redis 负反馈：`IFeedNegativeFeedbackRepository` + `FeedNegativeFeedbackRepository`（SET：postId + postType（业务类目，非 `content_post.media_type`））
- [x] 内容批量/分页查询：`IContentPostDao` + `IContentRepository` 扩展（`selectByIds`/`selectByUserPage`）
- [x] 改造 `FeedService`：timeline/profile/负反馈走真实链路（保持 Controller/DTO 不变）

#### Phase 2（在线推 / 离线拉：方案 A）

- [x] 在线判定：`IFeedTimelineRepository.inboxExists`（以 inbox key 是否存在定义在线）
- [x] 原子重建：`IFeedTimelineRepository.replaceInbox`（lock + tmpKey + RENAME 覆盖 + NoMore + TTL）
- [x] NoMore 哨兵：member=`__NOMORE__`；`pageInbox` 过滤 NoMore 且读侧刷新 TTL（EXPIRE）
- [x] 离线拉：`FeedInboxRebuildService` + `FeedService.timeline` 首页 inbox miss 触发重建并返回近况
- [x] 关注回源兜底：`RelationAdjacencyCachePort.listFollowing` 在缓存 key miss 或 relation 表缺失时，从 `user_follower` 回源关注列表，避免重建空 inbox
- [x] 在线推：`FeedDistributionService.fanoutSlice`（fanout 内部复用同一逻辑）作者无条件写入 inbox；粉丝仅对 `inboxExists(userId)=true` 的用户写入 inbox
- [x] 配置落地：`feed.rebuild.*` / `feed.inbox.ttlDays` / `feed.fanout.batchSize`（`application-dev.yml` 给出示例）

#### 已落地（补齐项）

- [x] Phase 3：推荐与排序（按第 11 章与 11.12 M0~M9 已落地）
- [x] 10.5.1 fanout 大任务切片（规模化）
- [x] 10.5.2 follow/unfollow 最小补偿（体验补偿）
- [x] 10.5.3 Outbox + 大 V 隔离（推拉结合）
- [x] 10.5.4 粉丝分层（铁粉推 / 路人拉；铁粉集合生成已实现）
- [x] 10.5.5 大 V 聚合池（关注大 V 过多的拉取兜底）
- [x] 10.5.6 Max_ID（瀑布流）分页
- [x] 10.5.7 读时修复后的索引清理（懒清理/异步清理）

#### 可改进点（不影响 Phase 2 正确性）

- [x] MQ 消息序列化统一为 JSON
- [x] timeline 读侧批量 postId 负反馈过滤（减少 `SISMEMBER` 次数）
- [x] 热点探测 + L1 本地缓存：JD HotKey + Caffeine（短 TTL，只缓存 hot key；落地：`ContentRepository.findPost/listPostsByIds`）
- [x] fanout 补齐 DLQ/重试与监控指标（DLQ + 最小指标日志）
- [x] fanout 的 `inboxExists` 使用 Redis pipeline（减少 1:1 round-trip）
- [x] 负反馈维度扩展：使用 postTypes（业务类目/主题），来源 `content_post_type`；发布接口支持用户提交 postTypes（字段上限 5，但本次上线口径只允许 1 个一级类，见 11.2.1）并落库

---

## 1. 需求确认（保持接口不变）

### 1.1 对齐《社交接口.md》的 Feed 契约（不允许改字段）

接口文档在 `社交接口.md` 的 “分发与 Feed 服务” 章节。

对应接口（已实现路由）：
- `GET /api/v1/feed/timeline` → `IFeedService.timeline(userId, cursor, limit, feedType)`
- `GET /api/v1/feed/profile/{targetId}` → `IFeedService.profile(targetId, visitorId, cursor, limit)`
- `POST /api/v1/feed/feedback/negative` → `IFeedService.negativeFeedback(userId, targetId, type, reasonCode, extraTags)`
- `DELETE /api/v1/feed/feedback/negative/{targetId}` → `IFeedService.cancelNegativeFeedback(userId, targetId)`

用户可见行为（Never break userspace）：
- 路由不变
- DTO 字段不变
- Response 包装不变

### 1.2 userId 来源（已拍板：从登录态/网关上下文注入）

> 这不是“安全设计”，只是**参数从哪里来**的硬约束：Feed/负反馈这类“和用户身份绑定”的接口，永远不要信客户端自己报的 userId。

- 网关约定：每个请求携带 Header `X-User-Id: <Long>`（把它当真值，不做任何安全校验/签名校验）。
- trigger 层提供 `UserContext`：从请求上下文取 `X-User-Id`，Controller 调用 `UserContext.requireUserId()`。
- 建议位置：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/support/UserContext.java`（给 Feed/Interaction/Risk 等所有“需要当前用户”的接口复用）
- 契约不变的做法：DTO 仍保留 `userId/visitorId` 字段（不删、不改），但 **Controller 必须忽略** `requestDTO.getUserId()/getVisitorId()`，统一用 `UserContext.requireUserId()` 覆盖后再调用 domain。

---

## 2. Phase 1（MVP）交付范围：只做 FOLLOW 时间线 + 最小负反馈 + 写扩散

### 2.1 目标用户体验（验收标准，必须满足）

1. A 关注 B，B 发布内容后：A 刷新关注页能看到 B 的新内容（允许秒级延迟）
2. A 关注页连续下拉翻页：不重复、不漏页（不能用 offset）
3. 个人页能按时间倒序拉到历史内容，并能稳定翻页
4. A 对某条内容提交负反馈后：A 的关注页下一次拉取不再返回该条；若用户在该帖的 postTypes 里点选了一个类型，则该类型会进入“类型级过滤”，后续同类型内容也会被隐藏；撤销后可再次出现（只要数据还在）

### 2.2 Phase 1 落地顺序（强制按这个顺序做，避免越写越乱）

1. 新增 `PostPublishedEvent`（types）
2. 增加 Feed MQ 拓扑（trigger：`FeedFanoutConfig`）
3. 改造 `ContentDispatchPort` 发布事件（infrastructure）
4. 增加 fanout consumer（trigger：`FeedFanoutDispatcherConsumer` + `FeedFanoutTaskConsumer`）
5. 增加粉丝分页查询（`IFollowerDao` + `RelationRepository` 扩展）
6. 增加 Redis InboxTimeline 仓储（`IFeedTimelineRepository` + infrastructure 实现）
7. 增加 Redis 负反馈仓储（`IFeedNegativeFeedbackRepository` + infrastructure 实现）
8. 增加 Content 批量/分页查询（`IContentPostDao` + `ContentRepository` 扩展）
9. 改造 `FeedService`：timeline/profile/负反馈走真实链路

做到第 9 步时，Phase 1 的 4 条验收才可能通过。

---

## 3. 核心数据结构（先把数据结构定死，后面实现就不乱）

### 3.1 真值源（事实数据）

- 内容真值：MySQL `content_post`（已存在）
- Timeline 不存 MySQL（这是硬约束）

依据：
- 《社交领域数据库.md》明确：不要用 MySQL 存储 Feed 流数据，Timeline 应使用 Redis/List/ZSet 或 NoSQL。

来源标注：
- 《社交领域数据库.md》

### 3.2 InboxTimeline（关注页索引，Redis）

Redis Key 规范（必须统一）：
- `feed:inbox:{userId}`：用户关注页 InboxTimeline（ZSET）
- `feed:neg:{userId}`：用户负反馈集合（SET，postId 维度）
- `feed:neg:postType:{userId}`：用户负反馈帖子类型集合（SET，业务类目/主题维度）
- `feed:neg:postTypeByPost:{userId}`：用户对某条 post 点选的类型（HASH，field=postId，value=postType，用于撤销反查）

InboxTimeline 结构（ZSET）：
- member：`postId`（字符串，唯一）
- score：`publishTimeMs`（毫秒时间戳，long → double 安全）

保留策略（可配置）：
- `feed.inbox.maxSize`：默认 1000
- `feed.inbox.ttlDays`：默认 30

为什么是 ZSET：
- 天然时间排序
- `ZADD` 幂等（重复写不会产生重复条目）
- 可裁剪只保留最近 N 条

来源标注：
- 《从小白到架构师(4): Feed 流系统实战》（Redis ZSET 做 Timeline）

---

## 4. Cursor 分页协议（这是“体验是否稳定”的关键）

### 4.1 timeline（关注页）cursor 定义

- 请求：`cursor`（String）= 上一页最后一条的 `postId`
- 返回：`nextCursor`（String）= 本页最后一条的 `postId`

为什么不用 offset：
- Feed 是动态列表，offset 会重复/漏页

来源标注：
- 《从小白到架构师(4): Feed 流系统实战》（不要使用 limit+offset 分页）

### 4.2 timeline 分页算法（必须按 member rank 翻页）

读取第 1 页：
- `ZREVRANGE feed:inbox:{userId} 0 limit-1`

读取下一页（cursor 已给）：
- `rank = ZREVRANK feed:inbox:{userId} cursorPostId`
- `ZREVRANGE feed:inbox:{userId} (rank+1) (rank+limit)`

退化策略（必须明确）：
- 若 `rank == null`（cursor 对应 postId 已被裁剪/过期/不存在）：
  - Phase 1：直接返回空列表 + `nextCursor=null`
  - Phase 2：同 Phase 1：返回空列表 + `nextCursor=null`（离线重建只在“首页 + inbox key miss”触发，见 10.3）

### 4.3 profile（个人页）cursor 定义（建议）

个人页来自 MySQL，排序建议固定为：
- `ORDER BY create_time DESC, post_id DESC`

cursor 编码建议：
- `"{lastCreateTimeMs}:{lastPostId}"`
- 示例：`1700000000123:1234567890123456789`

分页条件（SQL 语义）：
- 下一页：`create_time < cursorTime OR (create_time = cursorTime AND post_id < cursorPostId)`

---

## 5. 事件链路：Post_Published → MQ → fanout → 写入粉丝 inbox

### 5.1 这套思路来自哪里（按你要求标注出处）

- “发布成功先返回，扩散异步做”：来源标注《feed服务项目设计思考》
- “推模型用 MQ worker 异步推送 + 大任务切片”：来源标注《从小白到架构师(4): Feed 流系统实战》

### 5.2 事件模型（建议放在 types，避免 trigger 依赖 infrastructure）

新增事件类：
- `project/nexus/nexus-types/src/main/java/cn/nexus/types/event/PostPublishedEvent.java`（已存在）

字段最小集合：
- `postId`（Long）
- `authorId`（Long）
- `publishTimeMs`（Long）

建议继承已有基类：
- `project/nexus/nexus-types/src/main/java/cn/nexus/types/event/BaseEvent.java`

现有实现（以代码为准，不要重复创建类）：
```java
package cn.nexus.types.event;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 内容发布事件：用于触发 Feed 写扩散（fanout）。
 *
 * @author codex
 * @since 2026-01-12
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class PostPublishedEvent extends BaseEvent {
    private Long postId;
    private Long authorId;
    private Long publishTimeMs;
}
```

`publishTimeMs` 的取值规则（Phase 1 固定这样做）：
- 直接用 `System.currentTimeMillis()`（不做额外查库）

### 5.3 事件发送端（infrastructure：实现已有端口）

修改文件（现有占位实现）：
- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/ContentDispatchPort.java`

目标行为：
- 在 `onPublished(postId, userId)` 内构造 `PostPublishedEvent` 并发布到 RabbitMQ。

建议 MQ 拓扑（让本地可跑、可复现）：
- Exchange：`social.feed`（DirectExchange）
- Queue：`feed.post.published.queue`
- RoutingKey：`post.published`

`ContentDispatchPort.onPublished` 的代码骨架（示例，按需调整 import/注解）：
```java
@Slf4j
@Component
@RequiredArgsConstructor
public class ContentDispatchPort implements IContentDispatchPort {
    private final RabbitTemplate rabbitTemplate;

    @Override
    public void onPublished(Long postId, Long userId) {
        PostPublishedEvent event = new PostPublishedEvent();
        event.setPostId(postId);
        event.setAuthorId(userId);
        event.setPublishTimeMs(System.currentTimeMillis());
        rabbitTemplate.convertAndSend("social.feed", "post.published", event);
        log.info("Post_Published dispatched. postId={}, userId={}", postId, userId);
    }
}
```

事务边界（上线必做）：

- `ContentDispatchPort` 只负责“发 MQ”，不负责事务边界。
- 调用方（`ContentService.publish`）必须 after-commit 才能触发 `onPublished`（已在代码落地），不要在事务内直接发事件。

注意：`authorId` 就是发布者 `userId`（不要再造一个“作者服务”去查）。

参考现有 MQ 配置写法：
- `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/config/ContentScheduleDelayConfig.java`

### 5.4 MQ 配置（trigger：声明 exchange/queue/binding）

新增配置类：
- `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/config/FeedFanoutConfig.java`

内容要求：
- 常量：`EXCHANGE`、`QUEUE`、`ROUTING_KEY`
- Bean：`DirectExchange`、`Queue`、`Binding`

参考骨架（示例，按需调整 import）：
```java
@Configuration
public class FeedFanoutConfig {
    public static final String EXCHANGE = "social.feed";
    public static final String QUEUE = "feed.post.published.queue";
    public static final String ROUTING_KEY = "post.published";

    @Bean
    public DirectExchange feedExchange() {
        return new DirectExchange(EXCHANGE, true, false);
    }

    @Bean
    public Queue feedPostPublishedQueue() {
        return new Queue(QUEUE, true);
    }

    @Bean
    public Binding feedPostPublishedBinding(Queue feedPostPublishedQueue, DirectExchange feedExchange) {
        return BindingBuilder.bind(feedPostPublishedQueue).to(feedExchange).with(ROUTING_KEY);
    }
}
```

### 5.5 fanout 消费者（trigger：@RabbitListener）

落地消费者类（已实现，按 10.5.1 采用“拆片 + 执行片”）：
- dispatcher（消费 `PostPublishedEvent`）：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FeedFanoutDispatcherConsumer.java`
- worker（消费 `FeedFanoutTask`）：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FeedFanoutTaskConsumer.java`

职责（保持 trigger 薄，业务循环收敛到 domain）：
- dispatcher：
  - 作者自己无条件写入 inbox（体验保底）
  - 基于 followerCount 与 batchSize 计算切片数量，投递多个 `FeedFanoutTask(offset,limit)` 到 task queue
- worker：
  - 调用 `IFeedDistributionService.fanoutSlice(...)` 执行单片 fanout（按 offset/limit 只处理这一段粉丝）

失败处理建议：
- 参考 `RelationEventListener`：捕获异常并抛 `AmqpRejectAndDontRequeueException`
- 参考文件：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/listener/social/RelationEventListener.java`

---

## 6. fanout 分发逻辑（domain：真正的业务循环放这里）

### 6.1 新增领域服务（推荐）

新增接口：
- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/IFeedDistributionService.java`

新增实现：
- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/FeedDistributionService.java`

接口方法（Phase 1 足够）：
- `void fanout(PostPublishedEvent event)`

依赖（通过 domain 接口注入）：
- `IRelationRepository`：拉粉丝分页
- `IFeedTimelineRepository`：写 inbox

### 6.2 粉丝列表读取（必须做分页）

现状：
（Phase 1 已实现，无需再改）：
- `IFollowerDao.selectFollowerIds(userId, offset, limit)` 已存在（MyBatis：`FollowerMapper.xml`）
- `IRelationRepository.listFollowerIds(userId, offset, limit)` 已存在（domain 可直接用）

若你在某个分支/版本里没看到这些方法，再按下面的签名补齐：
1. `IFollowerDao.selectFollowerIds(userId, offset, limit) -> List<Long>`
2. `FollowerMapper.xml` 增加对应 SQL
3. `IRelationRepository` 增加 `listFollowerIds(userId, offset, limit)`，由 `RelationRepository` 调用 followerDao 实现
   - `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/adapter/repository/IRelationRepository.java`
   - `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/RelationRepository.java`

需要新增/修改的方法签名（精确到文件，避免实现者“猜接口”）：

1) `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/dao/social/IFollowerDao.java`
```java
@Mapper
public interface IFollowerDao {
    int insert(FollowerPO po);

    int delete(@Param("userId") Long userId, @Param("followerId") Long followerId);

    java.util.List<Long> selectFollowerIds(@Param("userId") Long userId,
                                           @Param("offset") Integer offset,
                                           @Param("limit") Integer limit);
}
```

2) `project/nexus/nexus-infrastructure/src/main/resources/mapper/social/FollowerMapper.xml`
```xml
<select id="selectFollowerIds" resultType="java.lang.Long">
    SELECT follower_id
    FROM user_follower
    WHERE user_id = #{userId}
    ORDER BY id ASC
    LIMIT #{limit} OFFSET #{offset}
</select>
```

3) `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/adapter/repository/IRelationRepository.java`
```java
java.util.List<Long> listFollowerIds(Long userId, Integer offset, Integer limit);
```

4) `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/RelationRepository.java`
```java
@Override
public java.util.List<Long> listFollowerIds(Long userId, Integer offset, Integer limit) {
    return followerDao.selectFollowerIds(userId, offset, limit);
}
```

SQL 语义：见上方 `FollowerMapper.xml` 的 `selectFollowerIds`。

### 6.3 fanout 写入规则（Redis）

对每个 followerId：
- `ZADD feed:inbox:{followerId} publishTimeMs postId`
- `EXPIRE feed:inbox:{followerId} ttlSeconds`
- 裁剪到 `maxSize`

幂等性说明：
- MQ 至少一次投递，重复消费会发生
- `ZADD` 写同一个 member 不会产生重复条目 → 天然幂等

来源标注：
- 《从小白到架构师(4): Feed 流系统实战》（幂等性与 Timeline 推送）

`IFeedDistributionService.fanout` 的伪代码（Phase 1 固定这样实现）：
```
postId = event.postId
authorId = event.authorId
ts = event.publishTimeMs

// 1) 先写入作者自己的 inbox，保证“自己也能在关注页看到自己发的内容”
//    来源标注：《feed服务项目设计思考》（优先保证发布者体验）
timelineRepo.addToInbox(authorId, postId, ts)

// 2) 再写入粉丝 inbox（分页拉粉丝，避免一次性全量）
offset = 0
batchSize = config.feed.fanout.batchSize (default 200/500)
while true:
  followerIds = relationRepo.listFollowerIds(authorId, offset, batchSize)
  if followerIds is empty:
    break
  for followerId in followerIds:
    if followerId == authorId:
      continue
    timelineRepo.addToInbox(followerId, postId, ts)
  offset = offset + followerIds.size
  if followerIds.size < batchSize:
    break
```

---

## 7. Feed 读取链路（domain：把占位改成真实读取）

### 7.1 新增两个 domain repository 接口

新增 `IFeedTimelineRepository`（domain 接口）：
- 位置：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/adapter/repository/IFeedTimelineRepository.java`
- 作用：封装 Redis InboxTimeline 的读写

建议方法（Phase 1 足够）：
- `void addToInbox(Long userId, Long postId, Long publishTimeMs)`
- `FeedIdPageVO pageInbox(Long userId, String cursor, int limit)`

新增 `IFeedNegativeFeedbackRepository`（domain 接口）：
- 位置：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/adapter/repository/IFeedNegativeFeedbackRepository.java`

建议方法：
- `void add(Long userId, Long targetId, String type, String reasonCode)`
- `void remove(Long userId, Long targetId)`
- `boolean contains(Long userId, Long targetId)`

新增分页 VO（domain）：
- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/model/valobj/FeedIdPageVO.java`
  - `List<Long> postIds`
  - `String nextCursor`
- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/model/valobj/ContentPostPageVO.java`
  - `List<ContentPostEntity> posts`
  - `String nextCursor`

建议接口/VO 骨架（示例，按项目既有 Lombok 风格写）：

1) `IFeedTimelineRepository`
```java
public interface IFeedTimelineRepository {
    void addToInbox(Long userId, Long postId, Long publishTimeMs);

    FeedIdPageVO pageInbox(Long userId, String cursor, int limit);
}
```

2) `IFeedNegativeFeedbackRepository`
```java
public interface IFeedNegativeFeedbackRepository {
    void add(Long userId, Long targetId, String type, String reasonCode);

    void remove(Long userId, Long targetId);

    boolean contains(Long userId, Long targetId);
}
```

3) `FeedIdPageVO`（cursor 协议：nextCursor=lastPostId）
```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeedIdPageVO {
    private java.util.List<Long> postIds;
    private String nextCursor;
}
```

4) `ContentPostPageVO`（cursor 协议：nextCursor="{lastCreateTimeMs}:{lastPostId}"）
```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContentPostPageVO {
    private java.util.List<ContentPostEntity> posts;
    private String nextCursor;
}
```

### 7.2 你必须扩展 ContentRepository（批量查帖子 + 个人页分页查帖子）

现状（不足以支撑 feed）：
- `IContentRepository` 没有批量查
  - `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/adapter/repository/IContentRepository.java`
- `IContentPostDao` 也没有 `selectByIds` / `selectByUserPage`
  - `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/dao/social/IContentPostDao.java`
  - `project/nexus/nexus-infrastructure/src/main/resources/mapper/social/ContentPostMapper.xml`

Phase 1 需要新增（建议）：
- `IContentRepository.listPostsByIds(List<Long> postIds) -> List<ContentPostEntity>`
- `IContentRepository.listUserPosts(Long userId, String cursor, int limit) -> ContentPostPageVO`

底层 DAO（MyBatis）需要新增：
- `IContentPostDao.selectByIds(postIds)`
- `IContentPostDao.selectByUserPage(userId, cursorTime, cursorPostId, limit)`

需要新增/修改的方法签名与 Mapper（精确到文件，避免实现者“猜 SQL”）：

1) `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/dao/social/IContentPostDao.java`
```java
@Mapper
public interface IContentPostDao {
    int insert(ContentPostPO po);

    ContentPostPO selectById(@Param("postId") Long postId);

    ContentPostPO selectByIdForUpdate(@Param("postId") Long postId);

    int updateStatus(@Param("postId") Long postId, @Param("status") Integer status);

    int updateStatusWithUser(@Param("postId") Long postId, @Param("userId") Long userId, @Param("status") Integer status);

    int updateContentAndVersion(@Param("postId") Long postId,
                                @Param("contentText") String contentText,
                                @Param("mediaInfo") String mediaInfo,
                                @Param("locationInfo") String locationInfo,
                                @Param("versionNum") Integer versionNum,
                                @Param("isEdited") Integer isEdited,
                                @Param("status") Integer status,
                                @Param("visibility") Integer visibility,
                                @Param("expectedVersion") Integer expectedVersion);

    java.util.List<ContentPostPO> selectByIds(@Param("postIds") java.util.List<Long> postIds);

    java.util.List<ContentPostPO> selectByUserPage(@Param("userId") Long userId,
                                                   @Param("cursorTime") java.util.Date cursorTime,
                                                   @Param("cursorPostId") Long cursorPostId,
                                                   @Param("limit") Integer limit);
}
```

2) `project/nexus/nexus-infrastructure/src/main/resources/mapper/social/ContentPostMapper.xml`

新增 `selectByIds`（用于 timeline 批量回表）：
```xml
<select id="selectByIds" resultMap="ContentPostMap">
    SELECT post_id, user_id, content_text, media_type, media_info, location_info, status, visibility, version_num, is_edited, create_time
    FROM content_post
    WHERE status = 2
      AND post_id IN
      <foreach collection="postIds" item="id" open="(" separator="," close=")">
          #{id}
      </foreach>
</select>
```

新增 `selectByUserPage`（用于个人页 profile）：
```xml
<select id="selectByUserPage" resultMap="ContentPostMap">
    SELECT post_id, user_id, content_text, media_type, media_info, location_info, status, visibility, version_num, is_edited, create_time
    FROM content_post
    WHERE user_id = #{userId}
      AND status = 2
      <if test="cursorTime != null and cursorPostId != null">
          AND (create_time &lt; #{cursorTime}
               OR (create_time = #{cursorTime} AND post_id &lt; #{cursorPostId}))
      </if>
    ORDER BY create_time DESC, post_id DESC
    LIMIT #{limit}
</select>
```

3) `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/ContentRepository.java`

实现侧注意点（必须写在实现里，而不是脑补）：
- `IN (...)` 查询不保证返回顺序，必须按入参 `postIds` 的顺序重排（用 `Map<postId, entity>` 再按 `postIds` 迭代组装）
- 可能存在 postId 查不到（被删除/未发布/数据异常），直接跳过，不要抛异常

注意：
- SQL 的排序字段必须与 cursor 协议一致，否则必然产生重复/漏页。

### 7.3 修改 FeedService（现有类）实现真实逻辑

修改文件：
- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/FeedService.java`

方法签名保持不变（对外契约已存在）：
- `timeline(userId, cursor, limit, feedType)`
- `profile(targetId, visitorId, cursor, limit)`
- `negativeFeedback(...)`
- `cancelNegativeFeedback(...)`

#### timeline（Phase 1 仅实现 FOLLOW）

输入规则（必须固定，别临场发挥）：
- `limit` 默认 20，最大 100
- `feedType` 为空时视为 `FOLLOW`

pseudocode（与当前代码一致）：
```
page = feedTimelineRepo.pageInbox(userId, cursor, limit)
ids = page.postIds

// 1) postId 维度过滤：精确隐藏某条内容
candidateIds = []
for id in ids:
  if id is null:
    continue
  if !negRepo.contains(userId, id):
    candidateIds.add(id)

posts = contentRepo.listPostsByIds(candidateIds)

// 2) 帖子类型维度过滤：隐藏“这类内容”（postTypes，业务类目/主题）
negativePostTypes = negRepo.listPostTypes(userId)
items = []
for post in posts:
  if intersects(post.postTypes, negativePostTypes):
    continue
  items.add(FeedItemVO(postId, authorId=post.userId, text=post.contentText, publishTime=post.createTime, source="FOLLOW"))
return FeedTimelineVO(items, page.nextCursor)
```

注意点：
- `nextCursor` 使用原始 page 的 lastPostId，不要因为过滤而乱改（否则分页语义不可解释）
- `listPostsByIds` 的返回顺序不可信，FeedService 组装 `items` 时必须按 `filtered` 的顺序输出（否则用户会看到“顺序乱跳”）
- 某些 postId 可能查不到（被删/未发布/数据异常），直接跳过即可

#### profile（个人页）

pseudocode：
```
page = contentRepo.listUserPosts(targetId, cursor, limit)
items = map(page.posts) -> FeedItemVO(source="PROFILE")
return FeedTimelineVO(items, page.nextCursor)
```

#### 负反馈

Redis（两层过滤 + 一份反查映射，撤销必须用）：

- postId 维度（精确隐藏某条内容）：
  - key：`feed:neg:{userId}`
  - `submitNegativeFeedback`：`SADD feed:neg:{userId} targetId`
  - `cancelNegativeFeedback`：`SREM feed:neg:{userId} targetId`

- 帖子类型维度（隐藏这类内容；业务类目/主题，不是媒体形态）：
  - key：`feed:neg:postType:{userId}`（SET）
  - 类型值来源：`content_post_type`（一帖多类型，用户发布时提交；`ContentPostEntity.postTypes` 由仓储回填）
  - submit：仅当 `request.type` 属于该帖的 `postTypes` 时才写入 `SADD feed:neg:postType:{userId} type`
  - cancel：撤销时需要先反查当时点选的 `type`（见下一条），并在“没有其它 post 仍点选该 type”时才 `SREM feed:neg:postType:{userId} type`

- 点选类型反查（撤销必须用；因为 cancel 接口没有 type 参数）：
  - key：`feed:neg:postTypeByPost:{userId}`（HASH）
  - field：`postId`
  - value：`type`
  - submit：`HSET feed:neg:postTypeByPost:{userId} postId type`
  - cancel：`type = HGET ... postId` → `HDEL ... postId`

说明：
- `content_post.media_type` 只描述“媒体形态”（纯文/图文/视频），不能用来代表“业务类目/主题”。把它当“内容类型”会导致用户点一次负反馈就把所有视频/图文都屏蔽掉，这是错误的用户体验。
- postTypes 是“业务类目/主题”，由用户发布时提交（最多 5 个），通过 `content_post_type` 表落库。

---

## 8. infrastructure 实现清单（照着建文件就能写）

### 8.1 Redis InboxTimeline 仓储实现

新增类（实现 `IFeedTimelineRepository`）：
- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/FeedTimelineRepository.java`（已存在）

实现要点：
- 使用 `StringRedisTemplate`
- member：`postId.toString()`
- score：`publishTimeMs`（double）
- 分页：`ZREVRANK` + `ZREVRANGE`
- 写入时顺带 `EXPIRE` + 裁剪到 maxSize

核心实现伪代码（Redis 命令级语义，按这个实现就不会跑偏）：

`addToInbox(userId, postId, publishTimeMs)`：
```
key = "feed:inbox:" + userId
ZADD key publishTimeMs postId
EXPIRE key ttlSeconds
if ZCARD(key) > maxSize:
  removeCount = ZCARD(key) - maxSize
  ZREMRANGEBYRANK key 0 (removeCount - 1)   // 删除最旧的 removeCount 条
```

`pageInbox(userId, cursor, limit)`：
```
key = "feed:inbox:" + userId
if cursor is blank:
  ids = ZREVRANGE key 0 (limit-1)
else:
  rank = ZREVRANK key cursor
  if rank is null:
    return {postIds: [], nextCursor: null}
  ids = ZREVRANGE key (rank+1) (rank+limit)
nextCursor = ids.isEmpty ? null : ids.last
return {postIds: ids(as Long), nextCursor}
```

### 8.2 Redis 负反馈仓储实现

新增类：
- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/FeedNegativeFeedbackRepository.java`

实现要点：
- `StringRedisTemplate.opsForSet()` + `opsForHash()`
- key：
  - `feed:neg:{userId}`（SET，postId 维度）
  - `feed:neg:postType:{userId}`（SET，postType 维度：业务类目/主题）
  - `feed:neg:postTypeByPost:{userId}`（HASH，postId->type，用于撤销反查）

存储策略（固定，且与当前代码一致）：
- postId 维度：把 `targetId(postId)` 存入 `feed:neg:{userId}`（用于精确过滤这条内容）
- postType 维度：仅当 `request.type` 属于该帖的 `postTypes` 时才写入（并记录 postId->type 映射，便于撤销）
- `type/reasonCode/extraTags` 暂不持久化（可以继续用 `message=reasonCode` 回传给前端，保持现有行为）

核心实现伪代码：
```
postKey = "feed:neg:" + userId
typeKey = "feed:neg:postType:" + userId
typeByPostKey = "feed:neg:postTypeByPost:" + userId

addPost: SADD postKey postId
removePost: SREM postKey postId
containsPost: SISMEMBER postKey postId

addType: SADD typeKey postType
removeType: SREM typeKey postType
listTypes: SMEMBERS typeKey -> Set<String>

saveSelectedType: HSET typeByPostKey postId postType; SADD typeKey postType
getSelectedType: HGET typeByPostKey postId
removeSelectedType: type=HGET; HDEL; 若其它 post 仍点选该 type 则不 SREM，否则 SREM typeKey type
```

### 8.3 fanout 事件发布（改造现有 ContentDispatchPort）

修改类：
- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/ContentDispatchPort.java`

实现要点：
- 注入 `RabbitTemplate`
- `convertAndSend(exchange, routingKey, PostPublishedEvent)`

---

## 9. 配置清单（写清楚，不要让实现者猜）

### 9.1 已有 Redis/RabbitMQ 基础配置

开发环境占位配置文件：
- `project/nexus/nexus-app/src/main/resources/application-dev.yml`

包含：
- `spring.data.redis.*`
- `spring.rabbitmq.*`
- `mybatis.mapper-locations: classpath*:mapper/social/*.xml`

### 9.2 建议新增的 feed 配置项

建议配置键：
- `feed.inbox.maxSize`（默认 1000）
- `feed.inbox.ttlDays`（默认 30）
- `feed.fanout.batchSize`（默认 200 或 500）
- `feed.rebuild.perFollowingLimit`（默认 20）
- `feed.rebuild.inboxSize`（默认 200）
- `feed.rebuild.maxFollowings`（默认 2000）
- `feed.rebuild.lockSeconds`（默认 30，用于离线重建互斥锁）

---

## 10. Phase 2（最终方案，选 A）：在线推 / 离线拉（以 inbox 缓存是否存在定义在线）

核心思想（只保留一条判断线，别搞两套 TTL）：
- 在线推：只推送给 `feed:inbox:{userId}` key 仍存在的粉丝（写 inbox）
- 离线拉：`feed:inbox:{userId}` key 不存在的用户回归时再拉取重建（一次性回填最近一段时间）

来源标注：
- 《从小白到架构师(4): Feed 流系统实战》（在线推、离线拉；NoMore；原子重建；大任务切片）

### 10.0 Phase 2 落地顺序（强制按这个顺序做）

1. 固化在线/离线定义（见 10.1）：在线= inbox key 存在；离线=inbox key 不存在（TTL 控制在线窗口）
2. 引入 NoMore 哨兵 member（见 10.1）：保证“空 Timeline”也能拥有 inbox key（否则 A 方案不成立）
3. 扩展 `IFeedTimelineRepository`（见 10.0.1）：`inboxExists` + `replaceInbox`（原子化重建）+ `pageInbox` 过滤哨兵/读侧续期
4. 新增离线重建服务（`IFeedInboxRebuildService` / `FeedInboxRebuildService`）
5. 修改 `FeedService.timeline`：首页 + inbox miss → rebuild；然后再按 Phase 1 读链路返回
6. 修改 `FeedDistributionService.fanout`：只对 `inboxExists(followerId)` 的粉丝写 inbox（作者自己无条件写）
7. （可选，规模化）当在线粉丝规模很大：引入 fanout 切片任务（见 10.5.1）

### 10.0.1 Phase 2 改动清单（精确到文件，新 Agent 直接照着改）

新增（domain）：
- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/IFeedInboxRebuildService.java`
  - `void rebuildIfNeeded(Long userId)`（只负责“重建”，不负责组装返回）
- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/FeedInboxRebuildService.java`
  - 依赖：`IRelationAdjacencyCachePort` + `IContentRepository` + `IFeedTimelineRepository` + `IFeedNegativeFeedbackRepository`
- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/model/valobj/FeedInboxEntryVO.java`
  - 字段：`postId`（Long）、`publishTimeMs`（Long）

配置（本项目当前实现）：
- `feed.rebuild.*` 通过 `@Value` 读取（避免新增跨模块 `@ConfigurationProperties` 类导致 domain 反向依赖）
  - 读取位置：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/FeedInboxRebuildService.java:1`
  - 读取位置：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/FeedTimelineRepository.java:1`

修改（domain）：
- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/FeedService.java`
  - 首页（`cursor` 为空）且 `!timelineRepo.inboxExists(userId)` 时：调用 `feedInboxRebuildService.rebuildIfNeeded(userId)` 再读 inbox
- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/FeedDistributionService.java`
  - fanout 时：只对 `timelineRepo.inboxExists(followerId)` 的粉丝写 inbox（作者自己无条件写）

修改（domain/infrastructure）：
- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/adapter/repository/IFeedTimelineRepository.java`
  - 新增：`boolean inboxExists(Long userId)`（用于判定 inbox key miss）
  - 新增：`void replaceInbox(Long userId, java.util.List<FeedInboxEntryVO> entries)`（原子化重建，内部写 NoMore）
  - `pageInbox()`：必须过滤 NoMore，并且读侧刷新 TTL（EXPIRE）
- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/FeedTimelineRepository.java`
  - `inboxExists(userId)`：`EXISTS feed:inbox:{userId}`
  - `replaceInbox(...)`：lock + tmpKey + `RENAME` 原子覆盖 + `EXPIRE` + 裁剪（保留 NoMore）
  - `pageInbox(...)`：过滤 NoMore，避免 `nextCursor` 返回 NoMore

可选（trigger/types，规模化）：
- （可选）新增 `FeedFanoutTask` 消息模型与 consumer，用于大规模 fanout 切片（见 10.5.1）

### 10.1 在线/离线判定（最终规则：以 inbox key 是否存在为准）

在线/离线定义（这就是 A 方案的核心）：
- 在线用户：`EXISTS feed:inbox:{userId} == true`
- 离线用户：`EXISTS feed:inbox:{userId} == false`

TTL（这就是“在线窗口”）：
- inbox 的 TTL 使用 `feed.inbox.ttlDays`
- 建议值：7（太大就等于把“很久不打开 App 的人”也当在线推送，写放大又回来了）

空 Timeline 的关键约束（必须解决，否则 A 方案不成立）：
- Redis 不存在“空 ZSET”，所以需要一个哨兵 member 让 key 存在
- 约定 NoMore 哨兵：`member="__NOMORE__"`，`score=0`
- 规则：`replaceInbox(...)` 无论 entries 是否为空，都必须写入 NoMore，并设置 TTL
- 读侧：`pageInbox` 必须过滤掉 `__NOMORE__`，且 `nextCursor` 不能返回 `__NOMORE__`

### 10.2 fanout 的“在线推”（只推 inbox key 存在的粉丝）

目标：把“写扩散放大”限制在在线粉丝子集上（inbox key 仍存在的粉丝），离线粉丝不推。

在 `IFeedDistributionService.fanout` 内，Phase 2 固定流程：
1. 作者自己：无条件写入自己的 inbox（发布者刚发内容，天然在线）
2. 分页拉粉丝（沿用 Phase 1 的 followerIds 分页查询）
3. 对每个 followerId：
   - 若 `!timelineRepo.inboxExists(followerId)`：跳过（离线用户不推）
   - 否则：`timelineRepo.addToInbox(followerId, postId, publishTimeMs)`

重要约束：
- 对离线用户“跳过”时，不要创建 inbox key（否则你又把离线用户变在线缓存）
- 对在线用户写入时，`addToInbox` 内部会 `EXPIRE` inbox key，保持在线用户的 timeline 缓存常驻（直到不活跃）

性能建议（可选优化，不影响正确性）：
- `EXISTS`/`ZADD` 可以对一个 batch 做 pipeline，减少 Redis 往返

### 10.3 离线用户回归：离线拉重建 inbox（首页 + inbox miss）

触发条件（固定）：
- 用户请求 `timeline(FOLLOW)` 的首页（`cursor` 为空）
- 且 `!timelineRepo.inboxExists(userId)`（inbox key miss）

说明：
- 这是“离线拉”，发生频率低（用户回归），允许做多次 DB 查询
- 目标不是构建“无限历史”，只回填一小段“最近内容”即可

新增领域服务（让 `FeedService` 保持短小）：
- 接口：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/IFeedInboxRebuildService.java`
- 实现：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/FeedInboxRebuildService.java`

依赖（domain 注入接口，不直接用 DAO/Redis）：
- `IRelationAdjacencyCachePort`：拿关注列表（followings，内部可回源重建）
- `IContentRepository`：按作者拉最近 K 条 post
- `IFeedTimelineRepository`：原子写回 inbox（`replaceInbox`）
- `IFeedNegativeFeedbackRepository`：重建时过滤掉负反馈内容

#### 10.3.1 关注列表获取与回源兜底（避免重建空 inbox）

你说的“离线重建只建了个空 inbox”，本质通常不是重建逻辑，而是 **关注列表拿不到**。

本项目的做法：把“关注列表完整性”上移到 `IRelationAdjacencyCachePort.listFollowing(...)`，让 `FeedInboxRebuildService` 只关心“按关注列表回填最近内容”。

落点（代码已实现）：
- domain 端口：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/adapter/port/IRelationAdjacencyCachePort.java`
- infrastructure 实现：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/RelationAdjacencyCachePort.java`
- 回源 DAO：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/dao/social/IFollowerDao.java`
- 回源 SQL：`project/nexus/nexus-infrastructure/src/main/resources/mapper/social/FollowerMapper.xml`（`selectFollowingIds`）

关键逻辑（pseudocode，与代码一致）：
```text
listFollowing(sourceId):
  key = \"social:adj:following:\" + sourceId
  keyExists = redis.hasKey(key)
  members = redis.SMEMBERS(key)
  dbCount = relationRepo.countRelationsBySource(sourceId, RELATION_FOLLOW)
  if !keyExists OR members is null OR members.size < dbCount:
    rebuildFollowing(sourceId)
    members = redis.SMEMBERS(key)
  return members

rebuildFollowing(sourceId):
  redis.DEL(\"social:adj:following:\" + sourceId)
  relations = relationRepo.listRelationsBySource(sourceId, RELATION_FOLLOW)
  if relations not empty:
    for rel in relations:
      addFollow(rel.sourceId, rel.targetId)
    return

  // relation 表缺边时：回源 user_follower（我关注了谁）
  offset = 0
  limit = 1000
  while true:
    followings = followerDao.selectFollowingIds(sourceId, offset, limit)
    if followings empty: break
    for targetId in followings:
      addFollow(sourceId, targetId)
    offset += followings.size
    if followings.size < limit: break
```

结果：只要 `user_follower` 有数据，离线重建就不会因为“关注列表缺失”而变成空 inbox。

重建流程（pseudocode，固定这样实现）：
```
targets = [userId] + adjacencyCachePort.listFollowing(userId, feed.rebuild.maxFollowings)
negativeTypes = negRepo.listPostTypes(userId)

allPosts = []
for each targetId in targets:
  page = contentRepo.listUserPosts(targetId, cursor=null, limit=feed.rebuild.perFollowingLimit)
  allPosts.addAll(page.posts)

sorted = sort(allPosts, by createTime desc then postId desc)
top = sorted.takeFirst(feed.rebuild.inboxSize)

entries = []
	for post in top:
	  if negRepo.contains(userId, post.postId):
	    continue
	  if intersects(post.postTypes, negativeTypes):
	    continue
	  entries.add({postId: post.postId, publishTimeMs: post.createTime})

timelineRepo.replaceInbox(userId, entries)   // 内部：lock + tmpKey + RENAME，且写入 NoMore + TTL
```

并发与原子性（必须做，不然你会读到“重建一半”的数据）：
- 重建互斥锁：`SET feed:inbox:rebuild:lock:{userId} 1 NX EX feed.rebuild.lockSeconds`
- 写入临时 key：`feed:inbox:tmp:{userId}:{epochMs}`
- 完成后 `RENAME` 覆盖正式 `feed:inbox:{userId}`（原子切换）

边界情况（必须写进实现里）：
- followings 为空且本人也没内容：`replaceInbox(userId, [])`（仍会写 NoMore，避免反复 miss）
- 关注的人都没发过内容（且本人也没内容）：同上（空 inbox 也要有 key，否则每次首页都会重建）

### 10.4 Phase 2 验收点（必须通过）

1. 在线推生效：同一作者发帖时，只写入 inbox key 存在的粉丝（写入数显著小于总粉丝数）
2. 离线拉生效：离线用户回归拉 timeline 首页，如果关注对象有已发布内容，应触发重建并返回近况
3. 资源回收：用户停止使用超过 `feed.inbox.ttlDays` 后，其 inbox key 自然过期 → 不再接收 pushes → 内存释放；回归再重建

### 10.5 可选优化（规模化/体验，不影响 A 方案正确性）

#### 10.5.1 大规模推送任务切片（避免单条消息做完“全世界”）

来源标注：《从小白到架构师(4): Feed 流系统实战》

当单条 `PostPublishedEvent` 需要处理的粉丝很多时，不要让一个 consumer 在一次消费里做完整 fanout（太慢、失败重试成本也太高）。

这节给出“能直接落地”的实现方案：
- dispatcher：只负责把一次 fanout 拆成很多片（slice）
- worker：只负责执行某一片（offset+limit）的 fanout

✅ 已落地（代码落点对照本节说明）：

- types：`project/nexus/nexus-types/src/main/java/cn/nexus/types/event/FeedFanoutTask.java`
- trigger：
  - dispatcher：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FeedFanoutDispatcherConsumer.java`（消费 `PostPublishedEvent` → 投递 `FeedFanoutTask`）
  - worker：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FeedFanoutTaskConsumer.java`（消费 `FeedFanoutTask`）
- trigger config：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/config/FeedFanoutConfig.java`（新增 `TASK_QUEUE/TASK_ROUTING_KEY` 与绑定）
- domain：
  - `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/IFeedDistributionService.java`（新增 `fanoutSlice`）
  - `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/FeedDistributionService.java`（实现 `fanoutSlice`）
- follower count：
  - `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/adapter/repository/IRelationRepository.java`（新增 `countFollowerIds`）
  - `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/dao/social/IFollowerDao.java`（新增 `countFollowers`）
  - `project/nexus/nexus-infrastructure/src/main/resources/mapper/social/FollowerMapper.xml`（新增 `countFollowers` SQL）
  - `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/RelationRepository.java`（实现 `countFollowerIds`）

##### 10.5.1.1 消息模型（types）

新增消息体（建议放 `nexus-types`，因为 trigger/infrastructure 都要用）：
- `project/nexus/nexus-types/src/main/java/cn/nexus/types/event/FeedFanoutTask.java`

字段建议（最小够用）：
```java
public record FeedFanoutTask(Long postId,
                             Long authorId,
                             Long publishTimeMs,
                             Integer offset,
                             Integer limit) {
}
```

##### 10.5.1.2 MQ 拓扑（trigger）

复用现有 `social.feed` Exchange（别再造一堆 exchange）：
- routingKey：`feed.fanout.task`
- queue：`feed.fanout.task.queue`

落点：
- 修改：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/config/FeedFanoutConfig.java`
  - 增加 `feed.fanout.task.queue` 的声明与绑定

##### 10.5.1.3 Dispatcher：PostPublishedEvent → 多个 FeedFanoutTask（trigger）

新增 dispatcher consumer 只做两件事：
1) 作者自己写入 inbox（体验保底）
2) 拆分任务并投递到 task queue

建议新增：
- `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FeedFanoutDispatcherConsumer.java`

dispatcher 伪代码（推荐做法：基于 followerCount 计算 slice 数量）：
```text
consume(PostPublishedEvent e):
  // 1) 作者自己写入（保底体验）
  timelineRepo.addToInbox(e.authorId, e.postId, e.publishTimeMs)

  // 2) 计算 slice 数量并投递任务
  total = relationRepo.countFollowerIds(e.authorId)  // 真值以 user_follower 反向表为准
  pageSize = feed.fanout.batchSize
  tasks = ceil(total / pageSize)
  for i in 0..tasks-1:
    rabbitTemplate.convertAndSend(\"social.feed\", \"feed.fanout.task\",
      FeedFanoutTask(e.postId, e.authorId, e.publishTimeMs, i*pageSize, pageSize))
```

本次落地直接采用 `user_follower` 反向表计数：`IFollowerDao.countFollowers` → `IRelationRepository.countFollowerIds`，dispatcher 用它计算切片数量（避免因 relation 表缺边导致切片漏发）。

##### 10.5.1.4 Worker：消费 FeedFanoutTask 执行某一片 fanout（trigger + domain）

新增 worker consumer：
- `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FeedFanoutTaskConsumer.java`

它只做“执行一片”：
```text
consume(FeedFanoutTask t):
  followers = relationRepo.listFollowerIds(t.authorId, t.offset, t.limit)
  for followerId in followers:
    if followerId == authorId: continue
    if !timelineRepo.inboxExists(followerId): continue
    timelineRepo.addToInbox(followerId, t.postId, t.publishTimeMs)
```

为了避免 trigger 里散落业务逻辑，推荐给 domain 增加一个“按 offset/limit 执行 fanout”方法：
- `IFeedDistributionService.fanoutSlice(postId, authorId, publishTimeMs, offset, limit)`
  - `FeedDistributionService` 内部复用现有逻辑即可

##### 10.5.1.5 失败重试与幂等点（必须讲清楚）

- 幂等性：Redis ZSET `ZADD` 对同一 member（postId）天然幂等 → slice 重试不会产生重复条目。
- 重试粒度：失败只重试这一片 `FeedFanoutTask(offset,limit)`，不会“从 0 再来”。
- DLQ/重试配置见 10.6.4（不要在这里再写一套）。

#### 10.5.2 follow/unfollow 的最小补偿（让体验更像正常产品）

来源标注：《从小白到架构师(4): Feed 流系统实战》

> ✅ 已落地（2026-01-22）：follow/unfollow 的在线补偿已实现（unfollow 事件触发在线用户强制重建 inbox）。  
> 落点文件：  
> - feed：`IFeedFollowCompensationService` / `FeedFollowCompensationService`（`onFollow/onUnfollow`）  
> - trigger：`RelationEventListener`（status=ACTIVE/UNFOLLOW 时触发补偿）  
> - MQ 拓扑：`RelationMqConfig`（补齐 social.relation 的队列绑定）  
> 配置：`feed.follow.compensate.recentPosts`（默认 20）

##### 10.5.2.1 follow：在线用户立刻回填“新关注的人”的最近 K 条

触发点（已存在，当前是占位调用）：
- MQ：`relation.follow.queue`
- listener：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/listener/social/RelationEventListener.java` 的 `handleFollow(RelationFollowEvent event)`

把占位代码：
- `feedService.timeline(event.targetId(), null, 1, \"FOLLOW_EVENT\")`
替换为真正补偿。

新增 domain 服务（建议独立出来，别把 FeedService 写肥）：
- 接口：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/IFeedFollowCompensationService.java`
  - `void onFollow(Long followerId, Long followeeId);`
- 实现：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/FeedFollowCompensationService.java`

依赖（domain 只依赖接口）：
- `IFeedTimelineRepository`：判断在线 + 写 inbox
- `IContentRepository`：拉 followee 最近内容
- `IFeedNegativeFeedbackRepository`：过滤 postId + postType（业务类目/主题）

补偿逻辑（pseudocode）：
```text
onFollow(followerId, followeeId):
  if followerId == null or followeeId == null: return

  // 只补偿在线用户；离线用户下次首页会走 Phase2 rebuild
  if !timelineRepo.inboxExists(followerId):
    return

	  page = contentRepo.listUserPosts(followeeId, cursor=null, limit=feed.follow.compensate.recentPosts)
	  negativeTypes = negRepo.listPostTypes(followerId)
	  for post in page.posts:
	    if negRepo.contains(followerId, post.postId): continue
	    if intersects(post.postTypes, negativeTypes): continue
	    timelineRepo.addToInbox(followerId, post.postId, post.createTime)
```

listener 改造（trigger）：
```text
handleFollow(event):
  if event.status != \"ACTIVE\": return   // 只对真正生效的关注做补偿
  feedFollowCompensationService.onFollow(event.sourceId, event.targetId)
```

配置建议（示例）：
```yml
feed:
  follow:
    compensate:
      recentPosts: 20
```

##### 10.5.2.2 unfollow：已落地策略（在线用户立刻生效）

关键约束：
- inbox 索引只有 `postId/publishTimeMs`，没有 `authorId`，因此无法按作者精确删除“已取消关注者”的历史内容。

当前实现（最小可落地，且用户体验立刻生效）：
- 触发点：收到 `RelationFollowEvent.status=UNFOLLOW`
- 动作：如果 follower 在线（`inboxExists(followerId)=true`），直接 `forceRebuild(followerId)`，用“当前关注列表”重建 inbox
- 结果：unfollow 立刻生效；离线用户仍在下次首页走 Phase2 rebuild

#### 10.5.3 大 V 隔离：Outbox + 混合读取（推拉结合）

来源标注：《字节三面挂了！问 “抖音关注流怎么设计”，我答 “推模式”，面试官：顶流大V发一条视频，你打算写 1 亿次 Redis？》

> ✅ 已落地（2026-01-14）：Outbox + 大 V 判定 + 读侧合并读取已实现。  
> 写侧落点：`IFeedOutboxRepository` / `FeedOutboxRepository` / `FeedFanoutDispatcherConsumer` / `FeedDistributionService`  
> 读侧落点：`FeedService.timeline`（合并 Inbox + Outbox/Pool，并使用 Max_ID 内部游标）  
> 关键配置：`feed.outbox.*`、`feed.bigv.followerThreshold`、`feed.bigv.pull.*`、`feed.bigv.pool.*`

我们当前 Phase 2 的混合点是“用户是否在线（inbox key 是否存在）”，它解决的是资源回收与离线回归，但对“顶流大 V 写放大”没有根治：如果某作者有大量在线粉丝，依然可能写很多次 Redis。

本节的目标：把“顶流大 V 发一条内容要写 N 次 inbox”的最坏情况，降到 **O(1) 写**（只写作者 Outbox），把成本转移到读侧的“可控合并”。

##### 10.5.3.1 数据结构（Redis）

- Outbox：`feed:outbox:{authorId}`（ZSET）
  - member：`postId`（字符串）
  - score：`publishTimeMs`（毫秒时间戳）
  - 策略：裁剪只保留最近 `feed.outbox.maxSize` 条；TTL=`feed.outbox.ttlDays`

注意：Outbox 只存索引，不存正文；真值仍在 `content_post`。读侧回表与负反馈过滤逻辑保持不变（Never break userspace）。

##### 10.5.3.2 配置项（新增）

建议新增配置（示例，按需放入 `application-dev.yml` 等）：

```yml
feed:
  outbox:
    maxSize: 1000
    ttlDays: 30
  bigv:
    followerThreshold: 500000
    pull:
      maxBigvFollowings: 200
      perBigvLimit: 50
```

字段解释：
- `feed.bigv.followerThreshold`：粉丝数 ≥ 阈值 → 视为大 V（启用 Outbox 拉模式）
- `feed.bigv.pull.maxBigvFollowings`：读侧最多合并多少个大 V（兜底）
- `feed.bigv.pull.perBigvLimit`：每个大 V 的 Outbox 单次最多拉多少条索引（兜底）

##### 10.5.3.3 接口契约（domain，新增/修改点）

1) 新增 Outbox 仓储接口（domain）：

- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/adapter/repository/IFeedOutboxRepository.java`

接口签名（只定义契约，不在 domain 引 Redis/MyBatis）：

```java
public interface IFeedOutboxRepository {
    void addToOutbox(Long authorId, Long postId, Long publishTimeMs);
    void removeFromOutbox(Long authorId, Long postId);
    java.util.List<FeedInboxEntryVO> pageOutbox(Long authorId, Long cursorTimeMs, Long cursorPostId, int limit);
}
```

说明：
- 复用 `FeedInboxEntryVO(postId, publishTimeMs)` 作为“索引条目”，避免再造一个重复 VO。
- `pageOutbox` 的 cursor 语义与 10.5.6 的 Max_ID 分页一致（必须配合一起落地，否则混合读取很难做稳定分页）。

2) 修改分发服务（domain）：

- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/FeedDistributionService.java`

改动点：
- 发布事件到来时 **永远** 写 `outboxRepo.addToOutbox(authorId, postId, publishTimeMs)`（1 写）
- 再根据“大 V 判定”决定是否继续写粉丝 inbox（普通作者保留现有在线推；大 V 默认不推）

大 V 判定建议用 `user_follower` 反向表计数（更贴近真实关注关系），因此需要：
- 在 `IRelationRepository` 增加一个“粉丝数”查询方法（由 `IFollowerDao` 回源实现），详见 10.5.3.4

3) 修改读侧 timeline（domain）：

- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/FeedService.java`

改动点：
- timeline 读取不再只读 inbox：需要 **合并 Inbox + 大 V Outbox**，再回表组装内容
- 为保证翻页稳定性，建议直接升级 timeline cursor 为 Max_ID（10.5.6）

##### 10.5.3.4 逐文件落地清单（可直接照着改）

1) 新增 Outbox 配置类（infrastructure）：
- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/config/FeedOutboxProperties.java`
  - `@ConfigurationProperties(prefix = "feed.outbox")`
  - 字段：`maxSize`、`ttlDays`

2) 新增 Outbox Redis 仓储实现（infrastructure）：
- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/FeedOutboxRepository.java`
  - Key 前缀：`feed:outbox:`
  - `addToOutbox`：ZADD + expireIfNeeded + trimToMaxSize
  - `pageOutbox`：用 `ZREVRANGEBYSCORE ... WITHSCORES` 拉取 member+score 并转成 `FeedInboxEntryVO`

3) 让 domain 能判断“大 V”（domain + infrastructure）：

- 扩展 DAO（infrastructure）：
  - `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/dao/social/IFollowerDao.java`
    - 新增 `int countFollowers(@Param("userId") Long userId);`
  - `project/nexus/nexus-infrastructure/src/main/resources/mapper/social/FollowerMapper.xml`
    - 新增 SQL：`SELECT COUNT(1) FROM user_follower WHERE user_id = #{userId}`

- 扩展仓储接口（domain）：
  - `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/adapter/repository/IRelationRepository.java`
    - 新增 `int countFollowerIds(Long userId);`（粉丝数）

- 实现（infrastructure）：
  - `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/RelationRepository.java`
    - `return followerDao.countFollowers(userId);`

4) 修改 `FeedDistributionService.fanout`（domain）伪代码（只描述逻辑，不给完整代码）：

```text
fanout(event):
  authorId = event.authorId
  postId = event.postId
  ts = event.publishTimeMs

  outboxRepo.addToOutbox(authorId, postId, ts)
  timelineRepo.addToInbox(authorId, postId, ts)   // 作者自己仍写 inbox，保证自家 timeline 体验

  followerCount = relationRepo.countFollowerIds(authorId)
  if followerCount >= feed.bigv.followerThreshold:
    return   // 大V默认只写 Outbox

  for followerId in relationRepo.listFollowerIds(authorId, offset, limit):
    if timelineRepo.inboxExists(followerId):
      timelineRepo.addToInbox(followerId, postId, ts)
```

5) 修改 `FeedService.timeline`（domain）合并读取伪代码（需要配合 10.5.6 的 cursor）：

```text
timeline(userId, cursor, limit):
  (cursorTimeMs, cursorPostId) = parseMaxIdCursor(cursor)  // 见 10.5.6

  // inbox miss 仍走离线重建（Phase 2）
  if isHomePage(cursor) and !timelineRepo.inboxExists(userId):
    rebuildService.rebuildIfNeeded(userId)

  // 1) inbox 候选
  inboxEntries = timelineRepo.pageInboxEntries(userId, cursorTimeMs, cursorPostId, limit * 3)

  // 2) bigV outbox 候选（最多合并 maxBigvFollowings 个）
  followings = adjacencyCachePort.listFollowing(userId, feed.rebuild.maxFollowings)
  bigvAuthors = pickBigVAuthors(followings, threshold, maxBigvFollowings)
  outboxEntries = []
  for authorId in bigvAuthors:
    outboxEntries.addAll(outboxRepo.pageOutbox(authorId, cursorTimeMs, cursorPostId, perBigvLimit))

  // 3) 合并去重 + 排序截断
  merged = mergeAndDedup(inboxEntries + outboxEntries)   // 按 publishTimeMs desc, postId desc
  candidates = merged.takeFirst(limit * 3)

  // 4) 回表 + 负反馈过滤 + 组装返回
  posts = contentRepo.listPostsByIds(candidates.postIds)
  items = applyNegativeFeedbackAndBuildVO(posts)

  // 5) nextCursor 用 candidates 的最后一个（保证翻页推进，不被过滤卡住）
  nextCursor = toMaxIdCursor(candidates.last.publishTimeMs, candidates.last.postId)
  return {items: items.takeFirst(limit), nextCursor: nextCursor}
```

#### 10.5.4 粉丝分层：铁粉推，路人拉（大 V 的体验/成本平衡）

来源标注：同上

> ✅ 已落地（2026-01-22）：铁粉集合仓储 + 生成消费者 + 写侧“只推铁粉”已落地。  
> 落点文件：`IFeedCoreFansRepository` / `FeedCoreFansRepository` / `FeedCoreFansConsumer` / `FeedFanoutDispatcherConsumer` / `FeedDistributionService`  
> 关键配置：`feed.bigv.coreFanMaxPush`（默认 2000）、`feed.corefans.ttlDays`（默认 30）

在 10.5.3 的基础上进一步分层：
- 铁粉（高频互动/高活跃）：即使是大 V 也推送到 Inbox（体验优先）
- 普通粉/僵尸粉：拉模式（成本优先）

本节给出一个“能落地”的最小实现，不引入新中间件：

##### 10.5.4.1 数据结构（Redis）

- `feed:corefans:{authorId}`（SET）
  - member：`followerId`
  - TTL：建议 7~30 天（避免永远膨胀）

##### 10.5.4.2 接口契约（domain）

新增仓储接口：
- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/adapter/repository/IFeedCoreFansRepository.java`

```java
public interface IFeedCoreFansRepository {
    void addCoreFan(Long authorId, Long followerId);
    boolean isCoreFan(Long authorId, Long followerId);
    java.util.List<Long> listCoreFans(Long authorId, int limit);
}
```

对应 Redis 实现放在 infrastructure：`FeedCoreFansRepository`（SET：SADD/isMember/members + EXPIRE）。

##### 10.5.4.3 写侧策略（修改 fanout）

当作者是大 V 时，不再分页遍历所有粉丝，而是只遍历“铁粉集合”：

```text
if isBigV(authorId):
  coreFans = coreFansRepo.listCoreFans(authorId, feed.bigv.coreFanMaxPush)
  for followerId in coreFans:
    if timelineRepo.inboxExists(followerId):
      timelineRepo.addToInbox(followerId, postId, ts)
  return
```

##### 10.5.4.4 铁粉集合如何产生（已落地：复用 interaction.notify 旁路）

当前实现（足够用，且不引入新系统）：
- 复用通知旁路事件 `interaction.notify`：`LIKE_ADDED(仅 POST)` 与 `COMMENT_CREATED`。
- Consumer：`FeedCoreFansConsumer`。
- 规则：如果 `fromUserId` 当前仍关注该 post 的作者，则 `SADD feed:corefans:{authorId} followerId` 并刷新 TTL（避免集合永远膨胀）。

后续可演进（按需，不属于本阶段）：
- 复用点赞/评论的互动计数，按亲密度 TopN 重算写入 `feed:corefans:{authorId}`（避免简单规则误判）。

#### 10.5.5 大 V 聚合池（解决关注大 V 过多导致的拉取慢）

来源标注：同上（Q2）

> ✅ 已落地（2026-01-14）：聚合池仓储 + 写侧入池 + 读侧按需读池已实现（默认开关关闭）。  
> 落点文件：`IFeedBigVPoolRepository` / `FeedBigVPoolRepository` / `FeedBigVPoolProperties` / `FeedService.timeline`  
> 关键配置：`feed.bigv.pool.*`（`enabled/buckets/maxSizePerBucket/ttlDays/fetchFactor/triggerFollowings`）

当用户关注的大 V 很多时，即使有 Outbox，逐个拉 Outbox 也会慢（N 个 Outbox → N 次 Redis 读）。

本节提供一个“只用 Redis 就能落地”的兜底：把多次读变成一次读。

##### 10.5.5.1 数据结构（Redis）

推荐分桶（避免单 key 过热）：
- `feed:bigv:pool:{bucket}`（ZSET）
  - member：`postId`（字符串）
  - score：`publishTimeMs`（毫秒时间戳）
  - bucket 取值：`0..feed.bigv.pool.buckets-1`
  - 分桶策略：`bucket = authorId % buckets`

##### 10.5.5.2 配置项（新增）

```yml
feed:
  bigv:
    pool:
      enabled: false
      buckets: 4
      maxSizePerBucket: 500000
      ttlDays: 7
      fetchFactor: 30
      triggerFollowings: 200
```

字段解释：
- `enabled`：开关（默认关，等 10.5.3 落地后再按需开启）
- `fetchFactor`：为抵消“池里有很多你不关注的大 V”导致的无效命中，池拉取条数≈`limit * fetchFactor`

##### 10.5.5.3 接口契约（domain）

新增聚合池仓储接口（domain）：
- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/adapter/repository/IFeedBigVPoolRepository.java`

```java
public interface IFeedBigVPoolRepository {
    void addToPool(Long authorId, Long postId, Long publishTimeMs);
    void removeFromPool(Long authorId, Long postId);
    java.util.List<FeedInboxEntryVO> pagePool(int bucket, Long cursorTimeMs, Long cursorPostId, int limit);
}
```

对应 Redis 实现放在 infrastructure：`FeedBigVPoolRepository`（ZSET）。
- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/FeedBigVPoolRepository.java`

##### 10.5.5.4 写侧接入点（分发链路）

落点：`FeedDistributionService.fanout`（domain）

伪代码：

```text
if isBigV(authorId) and feed.bigv.pool.enabled:
  poolRepo.addToPool(authorId, postId, ts)   // bucket = authorId % buckets
```

##### 10.5.5.5 读侧接入点（timeline 合并读取）

当 `bigvAuthors.size()` 过大时（例如 > 200），不再逐个拉 Outbox，改为读聚合池：

```text
followings = adjacencyCachePort.listFollowing(userId, feed.rebuild.maxFollowings)
bigvAuthorSet = filterBigV(followings)   // 用粉丝数阈值判定，转 HashSet

need = limit * feed.bigv.pool.fetchFactor
poolEntries = []
for bucket in 0..buckets-1:
  poolEntries.addAll(poolRepo.pagePool(bucket, cursorTimeMs, cursorPostId, need / buckets))

poolEntries = sort(poolEntries, by publishTime desc then postId desc)
poolEntries = poolEntries.takeFirst(need)

posts = contentRepo.listPostsByIds(poolEntries.postIds)
onlyFollowedBigV = posts.filter(p -> bigvAuthorSet.contains(p.authorId))

mergeCandidates = inboxEntries + toEntries(onlyFollowedBigV)
...后续合并/过滤/nextCursor 同 10.5.3 ...
```

注意：
- 这是“兜底优化”，不是必须项。
  如果你的系统还没到“用户关注很多大 V”的规模，优先落地 10.5.3/10.5.6 即可。

#### 10.5.6 Max_ID（瀑布流）分页（替换/增强 ZREVRANK）

来源标注：同上（Q1）

> ✅ 已落地（2026-01-14）：对外 cursor 仍保持 `postId`（兼容），服务端内部升级为 Max_ID 游标并支持多源合并。  
> 关键落点：  
> - domain：`IFeedTimelineRepository.pageInboxEntries/removeFromInbox`、`FeedService.timeline`  
> - infrastructure：`FeedTimelineRepository.pageInboxEntries`（`ZREVRANGEBYSCORE ... WITHSCORES` + Max_ID 过滤 + NoMore 过滤）  
> - outbox/pool：`pageOutbox/pagePool` 统一使用同一套 Max_ID 语义

当前 timeline cursor=postId + `ZREVRANK`，当 cursor 被裁剪/过期会断流返回空页；并且在 10.5.3/10.5.5 “多源合并”场景下很难做稳定分页。

本节提供一个可落地的 Max_ID（瀑布流）分页方案。

##### 10.5.6.1 排序定义（必须先定死）

timeline 的全局排序键固定为：
1) `publishTimeMs` 倒序（大到小）
2) `postId` 倒序（大到小，作为同时间戳 tie-break）

##### 10.5.6.2 Cursor 对外形态（两种选择，推荐兼容版）

选择 A（最标准）：cursor 直接用 Max_ID
- `cursor = "{publishTimeMs}:{postId}"`

选择 B（更稳妥，推荐）：对外保持 cursor=postId（不改变用户可见行为），服务端内部把它转换为 Max_ID
- 入参 cursor 为纯数字：先 `contentRepo.findPost(postId)` 取 `createTime` 作为 `cursorTimeMs`
- 内部统一按 `(cursorTimeMs, cursorPostId)` 做 Redis 分页与多源合并

##### 10.5.6.3 接口契约（domain：仓储需要返回 score）

现状问题：`IFeedTimelineRepository.pageInbox` 只返回 postId，不带 publishTimeMs，无法在 domain 里做“多源合并排序”。
因此需要增加一个“带 score 的分页接口”。

1) 扩展 Inbox 仓储接口（domain）：
- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/adapter/repository/IFeedTimelineRepository.java`

新增方法（保留旧方法以避免一次性大改）：

```java
java.util.List<FeedInboxEntryVO> pageInboxEntries(Long userId, Long cursorTimeMs, Long cursorPostId, int limit);
void removeFromInbox(Long userId, Long postId);
```

2) Outbox/聚合池仓储也统一为同一套 cursor 语义：
- `IFeedOutboxRepository.pageOutbox(authorId, cursorTimeMs, cursorPostId, limit)`
- `IFeedBigVPoolRepository.pagePool(bucket, cursorTimeMs, cursorPostId, limit)`

对应实现落点（infrastructure，按你项目现有目录结构放置）：
- Inbox：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/FeedTimelineRepository.java`
- Outbox：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/FeedOutboxRepository.java`
- 聚合池：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/FeedBigVPoolRepository.java`

##### 10.5.6.4 Redis 分页实现要点（infrastructure）

以 Inbox 为例（Outbox/Pool 同理）：
- Redis 查询：`ZREVRANGEBYSCORE key maxScore minScore WITHSCORES LIMIT 0 count`
  - `maxScore = cursorTimeMs`（首页用 `Long.MAX_VALUE`）
  - `minScore = 0`
- 过滤条件：只保留满足
  - `publishTimeMs < cursorTimeMs`
  或
  - `publishTimeMs == cursorTimeMs && postId < cursorPostId`
- NoMore 哨兵必须过滤（`__NOMORE__` 永远不应作为 postId 下发）
- 为避免“等于 cursor 的大量同分数”导致过滤后不够一页，建议查询 `count = limit + 20` 再过滤截断

##### 10.5.6.5 FeedService.timeline 组装 nextCursor 规则（必须固定）

由于负反馈/删除会导致“过滤掉很多条”，nextCursor 不能取“最后返回的一条”，否则容易卡住。
推荐规则：
- `nextCursor` 取“本次扫描过的候选列表”的最后一条（见 10.5.3 的 `candidates.last`）
- 若采用选择 B（对外 cursor=postId），则 `nextCursor = last.postId.toString()`
- 若采用选择 A（对外 Max_ID），则 `nextCursor = last.publishTimeMs + ":" + last.postId`

#### 10.5.7 读时修复（删除/下架不做“回撤 fanout”）

来源标注：同上（Q3）

> ✅ 已落地（2026-01-14）：timeline 回表后发现“无效 postId”会进行懒清理（写时不回撤 fanout）。  
> 落点：`FeedService.timeline` 在回表后对缺失 postId 执行 `removeFromInbox/removeFromOutbox/removeFromPool`（找不到 authorId 时至少清 inbox）。

写扩散回撤太贵，所以删除/下架只改元数据（本项目体现为 `content_post.status != 2`）。
读侧回表时天然会过滤掉无效内容（`selectByIds`/`selectByUserPage` 都限定 `status=2`），这就是“读时修复”。

本节要补齐的是：既然读时会发现“无效 postId”，就顺手把 Redis 索引也慢慢清掉，避免每次都 miss。

##### 10.5.7.1 接口契约（domain）

需要提供“删除索引”的仓储能力：
- Inbox：`IFeedTimelineRepository.removeFromInbox(userId, postId)`（见 10.5.6）
- Outbox：`IFeedOutboxRepository.removeFromOutbox(authorId, postId)`
- 聚合池：`IFeedBigVPoolRepository.removeFromPool(authorId, postId)`（按 bucket 删）

##### 10.5.7.2 读侧懒清理（最小可用实现）

落点：`FeedService.timeline`（domain）

伪代码：

```text
posts = contentRepo.listPostsByIds(candidatePostIds)
foundIds = set(posts.postId)

for id in candidatePostIds:
  if id not in foundIds:
    timelineRepo.removeFromInbox(userId, id)          // 不确定来源就都试一次，幂等
    // 如果你能拿到 authorId（例如从 outbox/pool 的来源侧带出来），也可以顺手删 outbox/pool
```

如果你已经在 merge 候选时能携带 “source=INBOX/OUTBOX/POOL + authorId”，那就可以精确删除对应索引，减少多余写。

##### 10.5.7.3 后台异步清理（可选，更稳）

当你不想在读接口里做写操作，可改为写 MQ：
- Topic/Queue：`feed.index.cleanup`
- 消息体：`{source, userId, authorId, postId}`
- Consumer：批量 `ZREM` 清理

原则：只做清理，不做复杂重算；错过一次也没关系，读时还能再发现。

### 10.6 可改进点（0.5 Checklist 对应实现方案）

> 这些都不影响 Phase 2 正确性，但会显著影响可用性/性能/可运维性。
> 下面按 0.5 的“可改进点”逐条给出可直接实现的落地清单。

#### 10.6.1 MQ 消息序列化统一为 JSON

问题：当前依赖默认消息转换器，跨服务/跨语言不稳。

落地方案（Spring AMQP 标准做法）：
- 在 MQ 配置里统一声明 `Jackson2JsonMessageConverter`
- RabbitTemplate 和 ListenerContainer 使用同一个 converter

落点：
- `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/config/FeedFanoutConfig.java`

已落地（代码）：
- 仅声明 `MessageConverter` Bean（`Jackson2JsonMessageConverter`）即可；Spring Boot 会自动把它注入到 `RabbitTemplate` 与 `@RabbitListener` 容器工厂，做到“同一套 converter”。

pseudocode：
```text
@Bean
MessageConverter messageConverter() = new Jackson2JsonMessageConverter()
```

#### 10.6.2 timeline 读侧批量 postId 负反馈过滤（减少 N 次 SISMEMBER）

现状：`for id in ids: SISMEMBER` → N 次 Redis 往返。

可落地的两种方案（择一）：

方案 A（最简单）：一次性取出负反馈全集合
- `SMEMBERS feed:neg:{userId}` → `Set<Long>`
- 在内存里过滤 `ids`
- 适用：负反馈集合不大（通常成立）

方案 B（更稳）：Redis pipeline 批量 `SISMEMBER`
- 对本页 `ids` 做 `executePipelined`
- 适用：担心 `SMEMBERS` 太大

落点：
- infrastructure：`FeedNegativeFeedbackRepository` 增加 `Set<Long> listPostIds(Long userId)` 或 `Map<Long, Boolean> containsBatch(Long userId, List<Long> postIds)`
- domain：`FeedService.timeline` 改用批量结果过滤

已落地（代码）：
- 采用方案 A：新增 `IFeedNegativeFeedbackRepository.listPostIds`，timeline 读侧改为一次 `SMEMBERS feed:neg:{userId}` + 内存过滤。

#### 10.6.3 热点探测 + L1 本地缓存：JD HotKey + Caffeine

目标：热点 postId 的回表/回 Redis 成本打爆时，先顶住。

落地顺序（照做就行）：
1) 先接 JD HotKey（按 `.codex/interaction-like-pipeline-implementation.md` 的 2.2.6 清单部署/配置）
2) 只对被判定为 hot 的 postId 开 Caffeine 短 TTL（例如 1~5 秒）
3) 缓存位置放在 infrastructure 的 `ContentRepository`（回表入口），domain 不要感知缓存

#### 10.6.4 fanout 补齐 DLQ/重试与监控指标

DLQ（死信）最小可用：
- 给 `feed.post.published.queue` 以及（如果你实现 10.5.1）`feed.fanout.task.queue` 配置 DLX/DLQ
- consumer 失败时 `AmqpRejectAndDontRequeueException` 直接进 DLQ（现有 `RelationEventListener` 就是这个风格）

监控（先做最小的）：
- 记录 fanout 写入条数、跳过离线条数、处理耗时
- 记录 DLQ 堆积长度（RabbitMQ 自带指标/管理台即可）

已落地（代码）：
- DLX：`social.feed.dlx.exchange`
- DLQ：`feed.post.published.dlx.queue` / `feed.fanout.task.dlx.queue`
- 指标：在 `FeedDistributionService.fanoutSlice` 与 `FeedFanoutDispatcherConsumer.pushToCoreFans` 输出最小日志（wrote/skippedOffline/costMs）。

#### 10.6.5 fanout 的 inboxExists 使用 Redis pipeline（减少 1:1 round-trip）

现状：每个 followerId 一次 `EXISTS`。

落地方式：给仓储接口增加 batch 能力，domain 只拿结果：
- domain：`IFeedTimelineRepository` 新增 `java.util.Set<Long> filterOnlineUsers(java.util.List<Long> userIds)`
- infrastructure：用 `executePipelined` 批量 `EXISTS feed:inbox:{id}`，返回 online 集合

domain 伪代码：
```text
followers = relationRepo.listFollowerIds(authorId, offset, limit)
online = timelineRepo.filterOnlineUsers(followers)
for id in online:
  timelineRepo.addToInbox(id, postId, ts)
```

已落地（代码）：
- domain：`IFeedTimelineRepository.filterOnlineUsers(List<Long>)`
- infrastructure：`FeedTimelineRepository.filterOnlineUsers` 使用 Redis pipeline 批量 `EXISTS feed:inbox:{id}`
- fanout：`FeedDistributionService` 与 `FeedFanoutDispatcherConsumer` 已改为批量过滤在线用户再写入 inbox

#### 10.6.6 负反馈维度扩展：从 media_type 扩到业务类目/标签

什么时候需要：你希望用户点一次“不喜欢”，过滤的是“某类内容”（业务类目/标签），而不仅是 `media_type`。

最小可落地方案（MySQL + Redis，不引入新组件）：

1) 给 post 绑定业务类型（两种选一）：
- 选 A（最简单）：直接在 `content_post` 加字段 `biz_type`/`biz_tags`（JSON/逗号分隔都行，别过度设计）
- 选 B（更规范）：新表 `content_post_type(post_id, type_id)` / `content_post_tag(post_id, tag_id)`

2) 发布链路写入类型（真值在 MySQL）：
- `ContentService.publish` 成功分支写入类型信息（DAO/Repository）

3) 负反馈仓储扩展（Redis SET）：
- 新 key：`feed:neg:biztype:{userId}` / `feed:neg:tag:{userId}`
- `negativeFeedback` 时回表拿 post 的业务类型并写入集合
- `timeline/rebuild` 读侧按业务类型集合过滤

---

## 11. Phase 3（推荐与排序）：先留接口，不要现在就实现复杂系统

结论先说清楚：**别自研推荐系统**。用成熟的推荐引擎做“召回 + 基础排序”，Feed 服务只负责：
1) 把我们的业务数据/反馈写进去；2) 把返回的 postId 拉回来、过滤、组装成 DTO。

本 Phase 3 的“最小可交付”目标（不改变现有接口）：
1. `GET /api/v1/feed/timeline?feedType=RECOMMEND` 能稳定刷（支持翻页，不重复不漏页）
2. 推荐结果能被“负反馈（postId + postType）”过滤
3. 推荐引擎不可用时，有**明确兜底**（不 500、不空白）

### 11.0 本次上线口径（2026-01-23，定死不争论）

> 这节是“实现者必须遵守的选择题答案”。缺它就会出现：每个人实现一套自己的理解，最后线上效果不可控。

- 北极星指标：点赞率（Like Rate）。
- Labels 来源：业务类目/标签（`content_post_type` / `ContentPostEntity.postTypes`），不做用户自定义标签。
- Read（曝光）写入：选 1B（不使用 `write-back-type=read` 自动回写；只对最终下发 items 写 `read` feedback，见 11.5）。
- 排序：选 2A（本次上线不做本地重排；返回顺序=候选扫描顺序，见 11.9）。
- postTypes 字典：选 3有（固定一级类；每帖必须且只能选 1 个；字典见 11.2.1）。
- Feedback 入口：A + C 双通道
  - A（复用现有 MQ）：复用 `PostPublishedEvent` 与 `interaction.notify`（仅正向事件：`LIKE_ADDED` / `COMMENT_CREATED`）作为“立即可用”的入口。
  - C（新增专用事件）：取消类/未来扩展走“推荐专用 routing key + 独立队列”，避免污染通知语义（例如：取消点赞/取消收藏）。
- 撤销语义：用“反向 feedbackType”表达撤销（`unlike` / `unstar`）。
- 强规则：推荐页“每页作者去重”（同一页最多 1 条同作者；跨页不保证去重）。
- 部署口径：Gorse 单实例 + 持久化数据卷（volume）即可；先别上多副本。

### 11.1 参考实现：scooter-WSVA 是怎么接入推荐系统的（你要的“出处”在这里）

这不是理论，是现成跑过的接入方式。

推荐引擎：Gorse（容器化，一体化镜像）

- 部署：`.codex/_repos/scooter-WSVA/docker-compose.yml`（service `gorse`）
- 配置：`.codex/_repos/scooter-WSVA/scripts/gorse/config.toml`
- 写入 Item（视频=Item，标签=Labels）：`.codex/_repos/scooter-WSVA/backend/mq/internal/logic/consumer.go`
- 写入 Feedback（like/star/comment...）：`.codex/_repos/scooter-WSVA/backend/mq/format/httpreq.go`
- 拉取推荐列表：`.codex/_repos/scooter-WSVA/backend/feed/rpc/internal/logic/listvideosbyrecommendlogic.go`
- 拉取热门列表：`.codex/_repos/scooter-WSVA/backend/feed/rpc/internal/logic/listpopularvideoslogic.go`
- 拉取相似（相关推荐）：`.codex/_repos/scooter-WSVA/backend/feed/rpc/internal/logic/listneighborvideoslogic.go`

核心 REST API 形态（按 scooter-WSVA 的真实调用）：
- `POST /api/item`：写入 Item + Labels + Timestamp
- `POST /api/feedback`：写入反馈（FeedbackType, UserId, ItemId, Timestamp）
- `GET /api/recommend/{userId}?write-back-type=read&n=...`：取推荐（scooter-WSVA 用法；本项目本次上线选 1B，不用自动回写）
- `GET /api/popular?user-id=...&n=...&offset=...`：取热门
- `GET /api/item/{itemId}/neighbors?n=...`：取相似 Item

### 11.2 我们在 nexus 里要落的“数据结构”（别把特殊情况写进代码）

Gorse 的世界很简单，就四样东西：

1) User：`userId`（String）
2) Item：`postId`（String）
3) Label：内容标签（String 数组）
4) Feedback：`(userId, postId, feedbackType, ts)`

把我们的复杂业务“压扁”成这四样，你的代码会更短、更稳。

映射规则（nexus → Gorse）：
- `postId(Long)` → `ItemId(String)`
- `userId(Long)` → `UserId(String)`
- `postTypes(List<String>)` → `Labels([]string)`
  - `postTypes` 真值来源：`content_post_type`（写入点见 Phase 2/10.6.6；读侧由 `ContentPostEntity.postTypes` 回填）。
  - 归一化规则：`trim`；空串丢弃；去重；保持“字符串值本身”作为 label（不要额外拼前缀/JSON）。

#### 11.2.1 postTypes 一级类字典（固定，不重叠；本次只做这一维）

注意：`postTypes` 表示“帖子意图/形式”，不表示“具体游戏/球队/品牌/话题”。具体主题不要写进 type。

- 本次上线口径：每条 post **必须且只能选择 1 个** `postType`（后端字段仍是 `List<String>`，但本次只允许 `size==1`）。
- 允许值（内部值 → 显示名）：
  - `game_news` → 游戏资讯
  - `general_news` → 综合资讯（非游戏）
  - `guide` → 攻略教程
  - `review` → 评测导购
  - `deal_trade` → 优惠交易
  - `qa` → 问答求助
  - `lfg` → 组队招募
  - `showcase` → 作品展示
  - `discussion` → 讨论观点
  - `life` → 日常生活
  - `emotion` → 情感树洞
  - `meta` → 站务反馈
- 判定规则（用来“消除重叠”，实现者/产品都按这个判）：  
  - 价格/交易导向（史低/出物/求购/渠道）→ `deal_trade`
  - 提问为主 → `qa`；给方法为主 → `guide`
  - 展示为主 → `showcase`；对比/结论/推荐对象为主 → `review`
  - 纯事实资讯 → `game_news`/`general_news`；观点/争论/吐槽 → `discussion`

反馈类型（保持简单可读；不要发明复杂枚举）：
- `read`：浏览/曝光（上线口径：只对最终下发 items 写入；不使用 `write-back-type=read` 自动回写）
- `like`：点赞（delta==+1）
- `unlike`：取消点赞（delta==-1）
- `comment`：评论（新评论落库后写入）
- `share`：分享（如果将来有分享事件，再写入）
- `star`：收藏/喜欢/加星（如果你把“收藏”定义为 star，就统一用 star）
- `unstar`：取消收藏（如果将来支持取消收藏）

注意：**负反馈不塞进 Gorse**（先别折腾）。我们已有 Redis 负反馈集合，读侧过滤即可。

### 11.3 组件拆分（DDD：把“推荐系统”当外部依赖）

目标：domain 只做编排，不直接写 HTTP。

domain：新增一个端口 + 一个小服务即可（别做大）：
- 端口：`IRecommendationPort`
  - `List<Long> recommend(userId, n)`
  - `List<Long> popular(userId, n, offset)`
  - `List<Long> neighbors(postId, n)`
  - `void upsertItem(postId, labels, timestamp)`
  - `void insertFeedback(userId, postId, feedbackType, timestamp)`
- 服务：`FeedRecommendService`（或在 `FeedService.timeline` 内部抽出 `recommendTimeline(...)`）

infrastructure：实现 `IRecommendationPort`：
- `GorseRecommendationPort`（HTTP client：Spring `RestClient` / `WebClient` 二选一，别两套）
- 配置：`feed.recommend.baseUrl`（对应 scooter-WSVA 的 `RecommendUrl`）

trigger：不用新增 Controller；复用现有 `timeline(feedType)`。

### 11.4 写入链路：内容发布 → Item（关键：after-commit，别在事务里搞 IO）

目标：让“新内容”尽快进入推荐池（否则推荐全是旧的）。

推荐的最小链路（只复用现有组件，不引入新花样）：
1) `ContentService.publish` after-commit：发布 `PostPublishedEvent`（已存在）
2) 新增一个 consumer：`FeedRecommendItemUpsertConsumer`（独立队列，不与 fanout 共用）
   - 建议：新增队列 `feed.recommend.item.upsert.queue` 绑定到 `social.feed` + routingKey=`post.published`
3) consumer 收到 event 后：
   - 查 `contentRepository.findPost(postId)` 拿 `postTypes`（或 tags）
   - 调 `recommendationPort.upsertItem(postId, labels, publishTime)`

为什么 consumer 而不是直接调：
- publish 是写路径，必须短；推荐写入失败也不应影响发布成功。

补充：删帖/撤回/封禁
- 如果项目有“删帖事件”，同样走 MQ：写入端口做 `deleteItem` 或 `hideItem`（选一种，别两种都做）。

### 11.5 写入链路：用户行为 → Feedback（正向 + 撤销；不做复杂幂等）

参考 scooter-WSVA 的真实做法：调用 `POST /api/feedback`，请求体是数组：

```json
[
  {
    "FeedbackType": "like",
    "ItemId": "123",
    "Timestamp": "2026-01-23T10:00:00.000Z",
    "UserId": "456"
  }
]
```

在 nexus 里怎么接（建议优先级从高到低）：
1) Read（曝光，选 1B）：`timeline(feedType=RECOMMEND)` 返回 items 后，将本次**实际下发**的 postIds 以 feedbackType=`read` 写入推荐系统（best-effort，异步/允许丢；不要用 `write-back-type=read` 自动回写）
2) Like（正向，复用 A）：`interaction.notify` 的 `LIKE_ADDED(POST)` → feedbackType=`like`
3) Like（撤销，走 C）：新增“推荐专用事件”（或 routing key）→ feedbackType=`unlike`
4) Comment（正向，复用 A）：`interaction.notify` 的 `COMMENT_CREATED` → feedbackType=`comment`
5) Star/Collect（正向+撤销，走 C）：如果将来有收藏域事件 → `star` / `unstar`
6) Share（正向，走 C）：如果将来有分享事件 → `share`

关键约束（必须写死，避免实现者“偷懒共用队列”导致消息被抢）：
- 复用 `interaction.notify` 时，推荐侧必须用**独立队列**绑定 `RK_INTERACTION_NOTIFY`；绝不与通知消费者共用 `interaction.notify.queue`。

原则：
- **只在“成功落库”后写入**（别写“尝试”）。
- 写入失败允许丢（先别搞复杂重试），但要打日志/指标，后面再补偿。

### 11.6 读链路：RECOMMEND / POPULAR / NEIGHBORS（统一成“候选集 → 过滤 → 回表 → 组装”）

核心思路：不管来源是什么，最后都变成 `List<Long> candidatePostIds`。

1) RECOMMEND（挂到 `timeline(feedType=RECOMMEND)`）：
- 调用：`GET /api/recommend/{userId}?n=K`（本次上线不使用 `write-back-type=read`，read 由 11.5 的写入链路负责）
- 返回：postId 列表（按顺序）

2) POPULAR（如果将来要独立热门页）：
- 调用：`GET /api/popular?user-id={userId}&n=K&offset=O`
- 返回：`[{Id, Score}]`，我们只取 Id；Score 可以先忽略

3) NEIGHBORS（相关推荐）：
- 调用：`GET /api/item/{postId}/neighbors?n=K`

统一过滤（复用 Phase 2 已有仓储）：
- postId 级负反馈：`feedNegativeFeedbackRepository.contains(userId, postId)`
- postType 级负反馈：`feedNegativeFeedbackRepository.containsType(userId, postType)`
- 内容不存在/已删除：`contentRepository.findPost` 回表为空则剔除

统一回表：
- `contentRepository.listPostsByIds(candidatePostIds)` 批量查
- 保持顺序：按 candidatePostIds 的顺序组装 `FeedItemVO`

### 11.7 分页：别把 cursor 绑死成 postId（推荐流天然不是按 postId 排序）

FOLLOW 流 cursor=postId 没问题；RECOMMEND 流不行。

做法：把 cursor 当成“对客户端不透明的 token”。

推荐流 cursor 建议格式（只要是 String，都不破坏现有契约）：
- `REC:{sessionId}:{scanIndex}`（scanIndex 是“扫描指针”，不是“已返回条数”）

实现策略（简单、可控、可重试）：
1) 首页（cursor 为空 / 非法）：
   - 创建 sessionId，scanIndex 从 0 开始
   - 调 gorse 拉 `M = limit * prefetchFactor` 个候选 id（默认 prefetchFactor=5）
   - 写入 Redis LIST：`feed:rec:session:{userId}:{sessionId}`（TTL=10~30min，追加时要去重）
2) 翻页（cursor 有）：
   - 解析 sessionId/scanIndex
   - 从 LIST 以 scanIndex 继续“扫描候选 → 过滤 → 回表 → 组装”，直到凑够 limit 或达到 scanBudget
   - `nextCursor = REC:{sessionId}:{scanIndexAfterScan}`（必须推进，避免过滤后卡住）
3) 候选不足：
   - 再调 gorse 追加写入 LIST；若 gorse 不可用，按 11.8 用 `feed:global:latest` 补齐（详见 11.11.4）

这样做的好处：
- 重试同一个 cursor 会返回同一批数据（用户体验稳定）
- gorse 挂了也能靠 session cache 兜住一段时间

### 11.8 兜底策略（推荐系统挂了也不能让用户看到 500）

兜底只做一个，别搞一堆 if：

- 维护一个全站最新 N 条的 ZSET：`feed:global:latest`（score=publishTime）
- publish after-commit：`ZADD feed:global:latest {ts} {postId}` + `ZREMRANGEBYRANK` 保持 topN
- 推荐调用失败：直接从 `feed:global:latest` 按 cursor 翻页返回

这和 Phase 2 的架构完全兼容，而且极易验证。

### 11.9 排序：先“规则重排”，别一上来就做模型

本次上线口径：选 2A —— **不做本地重排**。

- 规则：Feed 侧不计算 score、不重排；返回顺序=候选扫描顺序（跳过被过滤/被去重的即可）。
- 后续如果要做规则重排：必须先把“质量信号来源 + 稳定性口径（同 cursor 重试是否一致）”写死，否则不要做。

### 11.10 本地验证清单（不写代码也要能自证方案可跑）

按 scooter-WSVA 的方式，最小验证只需要：
1) 起 gorse（docker-compose）并挂载 config.toml
2) 写入 item：`POST /api/item`（带 labels）
3) 写入 feedback：`POST /api/feedback`（like/comment/read）
4) 拉推荐：`GET /api/recommend/{user}`
5) 在 nexus 侧用 mock/最小实现把 `feedType=RECOMMEND` 打通（后续真正落代码时做）

来源标注：
- scooter-WSVA（见 11.1 的文件清单）
- 《feed服务项目设计思考》（全站 latest 兜底）
- Gorse（开源推荐引擎，复用生态而非自研）

### 11.11 上线必补：把“能跑”变成“可上线”（缺任何一条都只能算 demo）

> 说明（给 12 岁也能懂）：现在 11.1~11.10 讲的是“怎么接”，但线上真正会把你打死的是：  
> 1) 翻页会不会重复/漏页；2) 推荐挂了会不会白屏；3) 数据是不是一开始就有；4) 出问题你能不能马上定位。  
> 下面把这些“必须写死的契约”补齐，避免实现者靠猜写出一堆 if。

#### 11.11.1 RECOMMEND 的 cursor/token 契约（必须固定，不要让实现者脑补）

RECOMMEND 流 cursor 不再是 postId，而是“对客户端不透明的 token”：
- 格式：`REC:{sessionId}:{scanIndex}`
- 含义：
  - `sessionId`：一次推荐会话 ID（短字符串即可，不做签名、不做安全校验）
  - `scanIndex`：**扫描指针**（不是“已返回条数”），表示“下一次从候选序列的第几个开始继续扫描”

服务端必须固定行为（不允许自由发挥）：
1) cursor 为空 / 解析失败 / scanIndex 非法（非数字、<0）：当作首页，创建新 session
2) session 缓存不存在（过期/被清理）：当作首页，创建新 session（用户体验允许“过期后不保证重试稳定”）
3) 同一个 cursor 重试：在 session TTL 内必须返回同一批数据（稳定性优先）

#### 11.11.2 过滤与翻页推进：scanIndex 以“扫描过的候选”为准（避免卡住）

线上会大量过滤：负反馈、下架、回表 miss。你必须保证翻页能推进：
- 规则：`nextCursor` 的 `scanIndex` 取“本次扫描结束的位置”，而不是“本次返回的最后一条”
- 目标：哪怕本页只返回了 3 条，也必须能继续往后翻，不允许卡死在同一段候选上

推荐流每次请求的最小流程（pseudocode，仅定义语义）： 
```text
recommendTimeline(userId, cursor, limit):
  (sessionId, scanIndex) = parseOrCreateSession(cursor)

  results = []
  seenAuthors = set()   // 强规则：每页作者去重
  scanned = 0
  scanBudget = limit * feed.recommend.scanFactor   // 默认 10

  while results.size < limit and scanned < scanBudget:
    ensureSessionCandidatesEnough(sessionId, scanIndex + 1)  // 不够就拉 gorse 追加
    candidateId = sessionList.get(scanIndex)
    scanIndex++
    scanned++

    if negativeFeedback.contains(userId, candidateId): continue
    post = contentRepo.findPost(candidateId)  // 或批量回表（推荐）
    if post == null: continue
    if negativeFeedback.containsType(userId, post.postTypes): continue
    if seenAuthors.contains(post.authorId): continue
    seenAuthors.add(post.authorId)

    results.add(buildFeedItem(post))

  nextCursor = "REC:{sessionId}:{scanIndex}"
  return {items: results, nextCursor: nextCursor}
```

#### 11.11.3 session cache 的数据结构（Redis）：最小化但要可控

为了让“同 cursor 重试稳定”，推荐流必须把候选序列缓存起来（TTL 短即可）：
- 候选列表（顺序）：`feed:rec:session:{userId}:{sessionId}`（LIST，member=postId）
- 去重集合（避免追加时重复）：`feed:rec:seen:{userId}:{sessionId}`（SET，member=postId）
- TTL：10~30 分钟（默认 20 分钟）

追加候选的规则（必须定死，避免实现者写出 5 层 if）：
- 每次追加批量：`appendBatch = limit * feed.recommend.prefetchFactor`（默认 5）
- 追加最多尝试轮数：`feed.recommend.maxAppendRounds`（默认 3）
- 追加时要做 session 内去重：只把 `seen` 不存在的 id push 进 list

#### 11.11.4 推荐不可用时的降级契约（只做一个兜底，别搞花活）

降级目标：用户永远不看到 500、不看到空白页（允许内容“没那么准”，但必须可用）。

固定策略（推荐调用失败/超时）：
1) 如果 session 里还有候选：继续用 session 扫描（不需要再调 gorse）
2) 如果 session 里候选不足：用全站 latest 补齐候选（见 11.8）

全站 latest 的 cursor 语义必须与 Phase 2 的 Max_ID 一致（避免新发明）：
- internal cursor：`{publishTimeMs}:{postId}`（用于从 `feed:global:latest` 继续翻）
- 建议在 session 内额外存一个 latestCursor（STRING）用于补齐，避免 offset 分页造成性能波动

#### 11.11.5 冷启动/回灌：不做就别谈“上线效果”

最小可上线的冷启动要求（从“必须”开始做，别理想化）：
1) 必须：历史内容回灌 Item（否则推荐池里没东西）
   - 扫描 `content_post(status=2)`，按时间分页
   - 对每条内容调用 `upsertItem(postId, labels, publishTime)`
2) 可选但强烈建议：历史互动回灌 Feedback（否则推荐很久都像随机）
   - 从互动域真值（点赞/评论/收藏）回放“正反馈”
   - 只回灌“最终成功”的行为（与 11.5 一致）

回灌形式不要发明新组件：做一个一次性 Job（Java main / 定时任务都行）调用 `IRecommendationPort` 即可。

#### 11.11.6 超时、预算、失败处理（写死数字，别凭感觉）

推荐接口是外部依赖，必须给它预算：
- HTTP 超时：
  - connectTimeout：`feed.recommend.connectTimeoutMs`（默认 200ms）
  - readTimeout：`feed.recommend.readTimeoutMs`（默认 500ms）
- 单次请求上限：
  - scanBudget：`limit * feed.recommend.scanFactor`（默认 10）
  - maxAppendRounds：默认 3（避免无限拉取）
- 失败策略：不重试（或最多 1 次快速重试），直接走 11.11.4 降级；失败要打日志/指标

#### 11.11.7 可配置项清单（实现前先把配置名定死）

建议最小配置（Spring `@ConfigurationProperties(prefix="feed.recommend")`）：
- `baseUrl`：Gorse Base URL
- `connectTimeoutMs` / `readTimeoutMs`
- `sessionTtlMinutes`（默认 20）
- `prefetchFactor`（默认 5）
- `scanFactor`（默认 10）
- `maxAppendRounds`（默认 3）

全站 latest（Spring `@ConfigurationProperties(prefix="feed.global.latest")`）：
- `maxSize`（例如 20000）

#### 11.11.8 线上可观测性（最小闭环：不需要花哨，但必须有）

至少要能回答两个问题：1) 推荐是不是在降级；2) 为什么在降级。

最小指标（用日志/metrics 任一方式落地即可）：
- `feed_recommend_http_ok_total / feed_recommend_http_fail_total`
- `feed_recommend_fallback_total`（走 latest 的次数）
- `feed_recommend_scan_drop_total`（过滤掉的数量：负反馈/下架/miss）
- `feed_recommend_latency_ms`（p50/p95）

关键日志（出现就能定位问题）：
- sessionId、scanIndex、limit、scanned、returned、appendRounds、fallbackReason

#### 11.11.9 验收用例（必须可重复，不靠“感觉挺对”）

推荐流（feedType=RECOMMEND）最小验收（每条都要能用接口复现）：
1) 翻页稳定：连续翻 5 页，不重复、不漏页（在 session TTL 内重试同 cursor，返回一致）
2) 负反馈生效：对某 postId 做负反馈后，该 postId 以及其 postType（若选择了类型）不再出现
3) 下架不穿透：把某条内容 status 改为非 2，推荐流不再返回（即使 gorse 仍吐出该 id）
4) 推荐挂了不白屏：人为让 gorse 超时/不可达，仍返回 latest，且可翻页推进

### 11.12 最小逐步实现步骤（给 Codex agent 的落地顺序）

目标：把 11.1~11.11 的“方案”拆成可逐步合入的最小实现单元，避免实现者靠猜写出一堆 if。

硬约束（每一步都必须满足）：
1) 不破坏现有 FOLLOW 行为与 cursor 协议（FOLLOW 仍是 postId）。
2) RECOMMEND 严格遵守 token 契约：`REC:{sessionId}:{scanIndex}`（见 11.11.1/11.11.2）。
3) 推荐页必须支持：负反馈过滤（postId + postType）与“每页作者去重”（跨页不保证）。
4) 推荐外部依赖不可用时，必须降级：不 500、不空白（见 11.11.4）。

#### 11.12.1 M0：先把接口/配置/Key 名称定死（行为不变，可先合入）

交付物：
1) domain 端口与仓储接口（只定义，不落实现）：
   - `IRecommendationPort`（按 11.3 的 5 个方法）
   - `IFeedGlobalLatestRepository`（维护 `feed:global:latest`）
   - `IFeedRecommendSessionRepository`（维护 session LIST/SET/游标 STRING）
2) infrastructure 配置类（仿照 `FeedInboxProperties`）：
   - `feed.recommend.*`（见 11.11.7）
   - `feed.global.latest.maxSize`
3) RECOMMEND cursor 的 parse/format 工具（cursor 非法一律当首页，见 11.11.1）。

验证：
- 静态自检：检索确认没有第二套 token 格式；配置 key 与 11.11.7 完全一致。

#### 11.12.2 M1：落地全站 latest 兜底数据源（写链路）

交付物：
1) Redis ZSET：`feed:global:latest`（score=publishTimeMs，member=postId）。
2) 发布事件消费处写入 latest：消费 `post.published` 时执行：
   - `ZADD feed:global:latest {ts} {postId}`
   - `ZREMRANGEBYRANK` 裁剪到 `feed.global.latest.maxSize`

建议落点（现成入口，最少改动）：
- `FeedFanoutDispatcherConsumer` 已消费 `PostPublishedEvent`，可在 dispatch 中追加写 latest（不影响 fanout 拆片语义）。

验证：
- 发布一条内容后，Redis 中 `feed:global:latest` 能查到该 postId，且超量会被裁剪。

#### 11.12.3 M2：RECOMMEND 读链路最小可用（先不接 Gorse，只用 latest 生成候选）

交付物：
1) 修正分流：`timeline(userId, cursor, limit, feedType)` 必须对 `feedType=RECOMMEND` 走独立链路，
   不能复用 FOLLOW 的 inbox/outbox 读取（否则会把关注流伪装成推荐流）。
2) session cache（Redis）按 11.11.3 固化：
   - LIST：`feed:rec:session:{userId}:{sessionId}`
   - SET：`feed:rec:seen:{userId}:{sessionId}`
   - STRING：`feed:rec:latestCursor:{userId}:{sessionId}`（用于 latest 补齐的 internal cursor）
3) recommendTimeline 扫描逻辑完全按 11.11.2：
   - `scanIndex` 是扫描指针，不是“已返回条数”
   - `nextCursor` 必须推进到“扫描结束位置”（避免过滤后卡住）
   - 过滤：负反馈 postId + postType；回表 miss/下架剔除；每页作者去重
4) 候选补齐（无 gorse）：当 session 候选不足时，从 `feed:global:latest` 按 internal cursor 追加候选，
   写入 session LIST/SET，并更新 latestCursor。

验证（按 11.11.9，M2 阶段至少应满足 1/2/4）：
1) `GET /api/v1/feed/timeline?feedType=RECOMMEND` 连续翻 5 页：不重复、不漏页；同 cursor 重试返回一致（TTL 内）。
2) 提交负反馈后：对应 postId 与其 postType 均不再出现。
3) 不启动/不可达 gorse：仍能返回 latest，且可翻页推进。

#### 11.12.4 M3：接入 Gorse（候选优先来自 Gorse；失败立即降级 latest）

交付物：
1) infrastructure 实现 `IRecommendationPort`：`GorseRecommendationPort`（HTTP client 只选一种；建议 Spring `RestClient`）。
2) session 候选追加策略（按 11.11.3/11.11.6 写死数字）：
   - 需要追加时，优先调用 gorse recommend 拉 `appendBatch = limit * prefetchFactor`
   - 超时/失败：不做复杂重试，直接走 11.11.4 降级（session 扫描优先，其次 latest 补齐）
3) 关键日志：sessionId/scanIndex/returned/scanned/appendRounds/fallbackReason 必须落地（见 11.11.8）。

验证：
- gorse 可用：RECOMMEND 候选主要来自 gorse；gorse 不可达：fallbackReason 可见，仍不白屏可翻页。

#### 11.12.5 M4：写入 Item（内容发布 → upsertItem，独立队列）

交付物：
1) 新增队列：`feed.recommend.item.upsert.queue`，绑定 `social.feed` + `post.published`（独立队列，不与 fanout 共用）。
2) consumer：`FeedRecommendItemUpsertConsumer`
   - 收到 `PostPublishedEvent` 后回表 `contentRepository.findPost(postId)` 取 `postTypes`
   - labels 归一化按 11.2：trim/去空/去重；不拼前缀、不做 JSON
   - 调 `recommendationPort.upsertItem(postId, labels, publishTime)`

验证：
- 发布新内容后，gorse 中能查询到对应 item 且 labels 符合 11.2.1。

#### 11.12.6 M5：写入 Feedback（read + like/comment；A 复用但必须独立队列）

交付物：
1) Read（曝光，选 1B）：仅对 RECOMMEND 实际下发的 items 写 `read` feedback（best-effort，异步/允许丢）。
2) Like/Comment（复用 A）：消费 `interaction.notify` 的 `LIKE_ADDED` / `COMMENT_CREATED`，
   但必须创建“推荐专用队列”绑定 `interaction.notify`，不能复用 `interaction.notify.queue`（避免与通知消费者共用队列）。

验证：
- 触发点赞/评论后，gorse 能看到对应 feedback；同时通知链路不受影响（队列独立）。

#### 11.12.7 M6：反馈通道 C（撤销 unlike/unstar 与未来扩展，独立事件与队列）

背景（为什么要单独拆一步）：
- 当前代码只会在“真的新增点赞”时发通知事件（`delta==+1` 才发 `LIKE_ADDED`），撤销不会产生任何事件：
  - 参考：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/ReactionLikeService.java`
- 但 Phase 3 的上线口径明确要求：撤销语义用反向 feedbackType 表达（`unlike` / `unstar`），并且走 C 通道，避免污染通知语义（见 11.0）。

交付物：
1) 新增推荐反馈专用事件（C 通道）：建议单独 event（不要复用 `InteractionNotifyEvent`）
   - 名称建议：`RecommendFeedbackEvent`（继承 `BaseEvent`）
   - 字段最小化（足够喂给 gorse）：`fromUserId`（或 `userId`）、`postId`、`feedbackType`、`tsMs`、`eventId`
   - feedbackType 最少落地：`unlike`（取消点赞）
   - 其它类型（预留，不要求一次做完）：`unstar` / `share` / `unshare`（将来有域事件再接）
2) MQ 拓扑（推荐专用 routing key + 独立队列，不与通知共用）：
   - 建议 exchange：`social.recommend`（Direct）
   - routingKey：`recommend.feedback`
   - queue：`feed.recommend.feedback.queue`（必须独立；不要复用 `interaction.notify.queue`）
   - DLQ：照现有 MQ 配置风格补齐（避免“消费失败直接丢”）
3) 生产端（撤销点在哪里写）：点赞撤销成功（`delta==-1`）后发布 `RecommendFeedbackEvent(feedbackType=\"unlike\")`
   - 注意：必须以“状态真的变化”为准（靠 delta 判断），不要对幂等请求重复发事件。
4) 消费端：`FeedRecommendFeedbackConsumer` 消费 `feed.recommend.feedback.queue`
   - 调用：`IRecommendationPort.insertFeedback(userId, postId, feedbackType, tsMs)`（best-effort；失败打日志/指标；不阻断主链路）

验证：
- 用户取消点赞后，gorse 侧能看到 `unlike` feedback。
- 通知链路不受影响（因为撤销不走 `interaction.notify`）。

#### 11.12.8 M7：读链路扩展：POPULAR / NEIGHBORS（统一成“候选集 → 过滤 → 回表 → 组装”）

说明：
- 这两条不是 Phase 3 的“最小可交付”必需项（最小只要求 RECOMMEND），但属于 11.6 描述的完整能力，补齐后方案才算“全覆盖”。

交付物：
1) `IRecommendationPort` 补齐调用：
   - POPULAR：`GET /api/popular?user-id={userId}&n=K&offset=O`
   - NEIGHBORS：`GET /api/item/{postId}/neighbors?n=K`
2) timeline 分支（不新增 Controller，仍复用 `GET /api/v1/feed/timeline`）：
   - `feedType=POPULAR`：cursor 是不透明 token，格式：`POP:{offset}`（offset 是扫描指针，不是“已返回条数”）
   - `feedType=NEIGHBORS`：cursor 是不透明 token，格式：`NEI:{seedPostId}:{offset}`（seedPostId 必填；offset 是扫描指针）
3) 两者都必须复用同一套“过滤/组装”规则（见 11.6）：
   - 负反馈：postId + postType
   - 内容不存在/下架：回表 miss 直接剔除
   - 组装顺序：按候选扫描顺序返回（不做本地重排，见 11.9）
4) 翻页推进规则与 RECOMMEND 一致（见 11.11.2）：
   - `nextCursor` 的 offset 以“扫描过的候选”为准，避免过滤后卡住
   - 允许返回不足 limit，但必须能继续翻页推进

验证：
- POPULAR：连续翻页不重复、不漏页（允许过滤导致“每页不足”），offset 会持续推进。
- NEIGHBORS：给定 seedPostId 后能翻页推进（或明确只支持单页并返回 nextCursor=null），且负反馈过滤生效。

#### 11.12.9 M8：内容状态变更同步推荐池（删帖/封禁/撤回 → hide/delete item）

现状风险：
- 读侧可以靠回表过滤掉下架内容（`selectByIds` 只查 `status=2`），但推荐引擎仍可能反复吐出已删/下架 id，导致扫描浪费与体验抖动。
- 当前删帖入口存在（软删），但没有任何 MQ 事件（参考：`ContentService.delete(...)`）。

交付物：
1) 定义“内容下架/删除”事件（二选一，别两套都做）：
   - 选 A：`PostDeletedEvent(postId, operatorId, tsMs)`
   - 选 B：`PostStatusChangedEvent(postId, fromStatus, toStatus, tsMs)`
2) after-commit 发布事件（参考发布链路的 after-commit 写法，避免事务未提交导致消费者读到脏数据）：
   - 发布成功：仍用 `PostPublishedEvent`
   - 删除/下架成功：发布上面的删除/状态变更事件
3) MQ 拓扑（建议复用 `social.feed` exchange，新增 routingKey）：
   - routingKey：`post.deleted`（或 `post.status.changed`）
   - queue：`feed.recommend.item.delete.queue`（独立队列）
4) 推荐写入端口扩展（为 delete/hide 留一个口子）：
   - 扩展 `IRecommendationPort`：增加 `deleteItem(postId)` 或 `hideItem(postId)`（二选一）
5) consumer：消费删帖事件后调用端口 delete/hide（best-effort；失败打日志/指标；不阻断主流程）

验证：
- 删除内容后：推荐池不会继续返回该 postId（读侧过滤 + 推荐侧 hide/delete 双保险）。

#### 11.12.10 M9：上线必补（冷启动回灌 + 可观测性 + 可重复验收）

交付物：
1) 冷启动回灌（必须，见 11.11.5）：扫描 `content_post(status=2)` 回灌 Item。
2) 可观测性（见 11.11.8）：至少能判断是否在降级，以及降级原因。
3) 全量验收用例（见 11.11.9）：四条都必须可复现通过。

---

## 12. 实现者 Checklist（已合并到 0.5）

本节原本是“实现者勾选清单”，但与 `### 0.5 实现进度 Checklist（合并版，自动更新）` 重复，容易出现两个地方不一致。

- 请以 `### 0.5 实现进度 Checklist（合并版，自动更新）` 为唯一真相源（Single Source of Truth）。
- 需要新增/调整任务时，只改 0.5；不要在本文再维护第二份打勾列表。

---

## 13. 出处汇总（你要求“只给标题”）

- 《feed服务项目设计思考》
- 《从小白到架构师(4): Feed 流系统实战》
- 《字节三面挂了！问 “抖音关注流怎么设计”，我答 “推模式”，面试官：顶流大V发一条视频，你打算写 1 亿次 Redis？》
