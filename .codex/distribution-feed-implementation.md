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

### 0.3 内容发布成功后已经会触发分发端口（这是接入点）

`ContentService.publish` 在成功分支已经调用：
- `IContentDispatchPort.onPublished(postId, userId)`

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

### 0.5 当前实现进度（自动更新）

> 更新时间：2026-01-12  
> 结论：✅ Phase 1 已完成；⚠️ Phase 2 本轮不实现（如果你之前看到实现痕迹，已回退）。

已完成（Phase 1）：
- ✅ PostPublishedEvent（`nexus-types`）
- ✅ FeedFanoutConfig / FeedFanoutConsumer（`nexus-trigger`）
- ✅ ContentDispatchPort 发布 MQ 消息（`nexus-infrastructure`）
- ✅ IFeedDistributionService + fanout 实现（`nexus-domain`）
- ✅ 粉丝分页查询：IFollowerDao + IRelationRepository.listFollowerIds（MyBatis）
- ✅ Redis InboxTimeline：IFeedTimelineRepository + FeedTimelineRepository（ZSET）
- ✅ Redis 负反馈：IFeedNegativeFeedbackRepository + FeedNegativeFeedbackRepository（SET）
- ✅ 内容批量/分页查询：IContentPostDao + IContentRepository 扩展（selectByIds/selectByUserPage）
- ✅ FeedService：timeline/profile/负反馈走真实链路（保持 Controller/DTO 不变）

未完成（后续）：
- ⏸ Phase 2：在线推 / 离线拉（本轮明确不实现）
- ⏳ Phase 3：推荐与排序（未实现）

可改进点（不影响 Phase 1 交付）：
- MQ 消息序列化可统一为 JSON（目前沿用默认可运行方式，避免引入额外配置分叉）
- timeline 读侧可做批量负反馈过滤（减少 `SISMEMBER` 次数）
- feed fanout 可补齐 DLQ/重试与监控指标（类似 ContentSchedule 的 DLQ 思路）

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

---

## 2. Phase 1（MVP）交付范围：只做 FOLLOW 时间线 + 最小负反馈 + 写扩散

### 2.1 目标用户体验（验收标准，必须满足）

1. A 关注 B，B 发布内容后：A 刷新关注页能看到 B 的新内容（允许秒级延迟）
2. A 关注页连续下拉翻页：不重复、不漏页（不能用 offset）
3. 个人页能按时间倒序拉到历史内容，并能稳定翻页
4. A 对某条内容提交负反馈后：A 的关注页下一次拉取不再返回该条；撤销后可再次出现（只要数据还在）

### 2.2 Phase 1 落地顺序（强制按这个顺序做，避免越写越乱）

1. 新增 `PostPublishedEvent`（types）
2. 增加 Feed MQ 拓扑（trigger：`FeedFanoutConfig`）
3. 改造 `ContentDispatchPort` 发布事件（infrastructure）
4. 增加 fanout consumer（trigger：`FeedFanoutConsumer`）
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
- `feed:neg:{userId}`：用户负反馈集合（SET）

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

新增消费者类：
- `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FeedFanoutConsumer.java`

职责（只做两件事）：
1. 接收 `PostPublishedEvent`
2. 调用 domain 层的“分发服务”执行 fanout（不要在 consumer 里写业务循环）

失败处理建议：
- 参考 `RelationEventListener`：捕获异常并抛 `AmqpRejectAndDontRequeueException`
- 参考文件：`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/listener/social/RelationEventListener.java`

参考骨架（示例，按需调整包名与 import）：
```java
@Slf4j
@Component
@RequiredArgsConstructor
public class FeedFanoutConsumer {

    private final IFeedDistributionService feedDistributionService;

    @RabbitListener(queues = FeedFanoutConfig.QUEUE)
    public void onMessage(PostPublishedEvent event) {
        try {
            feedDistributionService.fanout(event);
        } catch (Exception e) {
            log.error("MQ feed fanout failed, postId={}, authorId={}", event.getPostId(), event.getAuthorId(), e);
            throw new AmqpRejectAndDontRequeueException("feed fanout failed", e);
        }
    }
}
```

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

pseudocode：
```
page = feedTimelineRepo.pageInbox(userId, cursor, limit)
ids = page.postIds
filtered = []
for id in ids:
  if !negRepo.contains(userId, id):
    filtered.add(id)
posts = contentRepo.listPostsByIds(filtered)
items = map(posts) -> FeedItemVO(postId, authorId=post.userId, text=post.contentText, publishTime=post.createTime, source="FOLLOW")
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

Redis Set：
- `submitNegativeFeedback`：`SADD feed:neg:{userId} targetId`
- `cancelNegativeFeedback`：`SREM feed:neg:{userId} targetId`

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
- `StringRedisTemplate.opsForSet()`
- key：`feed:neg:{userId}`

Phase 1 存储策略（固定）：
- 只把 `targetId` 存入 Set（用于读侧过滤）
- `type/reasonCode/extraTags` 暂不持久化（可以继续用 `message=reasonCode` 回传给前端，保持现有行为）

核心实现伪代码：
```
key = "feed:neg:" + userId
add: SADD key targetId
remove: SREM key targetId
contains: SISMEMBER key targetId
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
- `feed.active.ttlDays`（默认 7）
- `feed.rebuild.perFollowingLimit`（默认 20）
- `feed.rebuild.inboxSize`（默认 200）
- `feed.rebuild.maxFollowings`（默认 2000）

---

## 10. Phase 2（优化）：在线推 / 离线拉（把“大 V”从特殊情况变成普通情况）

核心思想：
- 在线推：只对“最近活跃用户”推送（写 inbox）
- 离线拉：离线用户回归时再拉取重建（一次性回填最近一段时间）

来源标注：
- 《从小白到架构师(4): Feed 流系统实战》（在线推、离线拉）

### 10.0 Phase 2 落地顺序（强制按这个顺序做）

1. 新增 Phase 2 Redis key 规范：`feed:active:{userId}`（活跃标记）与配置项 `feed.active.ttlDays`
2. 新增 domain 接口 `IFeedActiveRepository` 与 infrastructure 实现 `FeedActiveRepository`
3. 修改 `FeedService.timeline`：每次拉取 FOLLOW timeline 都调用 `feedActiveRepository.touch(userId)`
4. 修改 `FeedDistributionService.fanout`：只对活跃粉丝写 inbox（作者自己例外）
5. 修改 `FeedTimelineRepository.pageInbox`：读侧刷新 inbox TTL（推荐）
6. 新增离线重建服务（`IFeedInboxRebuildService` / `FeedInboxRebuildService`），并在 inbox miss 时触发
7. 增加 Phase 2 验收点（见 10.4）

### 10.0.1 Phase 2 改动清单（精确到文件，新 Agent 直接照着改）

新增（domain）：
- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/adapter/repository/IFeedActiveRepository.java`
  - `void touch(Long userId)`
  - `boolean isActive(Long userId)`
- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/IFeedInboxRebuildService.java`
  - `void rebuildIfNeeded(Long userId)`（只负责“重建”，不负责组装返回）
- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/FeedInboxRebuildService.java`
  - 依赖：`IRelationAdjacencyCachePort` + `IContentRepository` + `IFeedTimelineRepository` + `IFeedNegativeFeedbackRepository` + `IFeedActiveRepository`

新增（infrastructure）：
- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/FeedActiveRepository.java`
  - Key：`feed:active:{userId}`，value 固定 `"1"`，TTL=`feed.active.ttlDays`
- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/config/FeedActiveProperties.java`
  - `@ConfigurationProperties(prefix = "feed.active")`
  - 字段：`ttlDays`（默认 7）
- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/config/FeedRebuildProperties.java`
  - `@ConfigurationProperties(prefix = "feed.rebuild")`
  - 字段：`perFollowingLimit`（默认 20）、`inboxSize`（默认 200）、`maxFollowings`（默认 2000）

修改（domain）：
- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/FeedService.java`
  - `timeline()` 内：每次 FOLLOW 请求都 `activeRepo.touch(userId)`
  - 首页（`cursor` 为空）且 inbox key miss 时：调用 `feedInboxRebuildService.rebuildIfNeeded(userId)` 再读 inbox
- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/FeedDistributionService.java`
  - fanout 时：只对 `activeRepo.isActive(followerId)` 的粉丝写 inbox（作者自己无条件写）

修改（domain/infrastructure）：
- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/adapter/repository/IFeedTimelineRepository.java`
  - 新增：`boolean inboxExists(Long userId)`（用于判定 inbox key miss）
  - `pageInbox()` 内部不承担“是否 miss”的语义（返回空列表不代表 miss）
- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/FeedTimelineRepository.java`
  - 实现 `inboxExists(userId)`：`EXISTS feed:inbox:{userId}`
  - 可选：在 `pageInbox()` 里对 key 做 `EXPIRE`（读侧续期），保持活跃用户 timeline 不被自然过期

### 10.1 活跃判定（使用独立 marker，避免空 ZSET 的坑）

Phase 2 新增 Redis key：
- `feed:active:{userId}`：String，值固定为 `"1"`，TTL=`feed.active.ttlDays`

活跃/离线定义：
- 活跃用户：`EXISTS feed:active:{userId} == true`
- 离线用户：`EXISTS feed:active:{userId} == false`

`touch` 的时机（Phase 2 固定这样做）：
- 只要调用了 `GET /api/v1/feed/timeline`（FOLLOW），就必须 `touch(userId)`  
  即使当前没有任何内容返回，也要 touch（否则用户会被误判为离线）。

为什么不复用 `feed:inbox:{userId}` 的存在性：
- Redis ZSET 没有“空集合”这种状态：当用户刚打开 App 但暂时没有内容时，inbox key 不存在会被误判为离线
- marker key 不污染 Timeline 数据结构，也不需要塞 “NoMore” 这种特殊值

需要新增的 domain 接口（建议位置）：
- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/adapter/repository/IFeedActiveRepository.java`

建议方法签名：
- `void touch(Long userId)`
- `boolean isActive(Long userId)`

Redis 实现伪代码（语义级）：
```
key = "feed:active:" + userId
touch: SET key "1" EX ttlSeconds
isActive: EXISTS key
```

### 10.2 fanout 的“在线推”（只推活跃粉丝）

目标：把“写扩散放大”限制在活跃粉丝子集上，不要把离线用户的 inbox 写爆。

在 `IFeedDistributionService.fanout` 内，Phase 2 固定流程：
1. 作者自己：无条件写入自己的 inbox（保证发布者体验）
2. 分页拉粉丝（沿用 Phase 1 的 followerIds 分页查询）
3. 对每个 followerId：
   - 若 `!feedActiveRepository.isActive(followerId)`：跳过（离线用户不推）
   - 否则：`timelineRepo.addToInbox(followerId, postId, publishTimeMs)`

重要约束：
- 对离线用户“跳过”时，不要触碰其 inbox TTL（否则等于又把离线用户变成在线缓存）
- 对活跃用户写入时，`addToInbox` 内部会 `EXPIRE` inbox key，保持活跃用户的 timeline 缓存常驻（直到不活跃）

性能建议（Phase 2 可选优化，不影响正确性）：
- `isActive` 对每个 followerId 单独 EXISTS 会产生大量 Redis 往返
- 推荐用 pipeline 或 multiGet 批量判断一个 batch 内哪些 followerId 活跃，再批量 ZADD/EXPIRE

### 10.3 离线用户回归：离线拉重建 inbox（cache miss 重建）

触发条件（Phase 2 固定）：
- 用户请求 `timeline(FOLLOW)` 的首页（`cursor` 为空）
- 并且 `feed:inbox:{userId}` key 不存在（Redis key miss，通过 `IFeedTimelineRepository.inboxExists(userId)` 判定）

说明：
- 这是“离线拉”，发生频率低（用户回归），允许做多次 DB 查询
- 目标不是构建“无限历史”，只回填一小段“最近内容”即可

建议新增领域服务（让 `FeedService` 保持短小）：
- 接口：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/IFeedInboxRebuildService.java`
- 实现：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/FeedInboxRebuildService.java`

依赖（domain 注入接口，不直接用 DAO/Redis）：
- `IRelationAdjacencyCachePort`：拿关注列表（followings，内部可回源重建）
- `IContentRepository`：按作者拉最近 K 条 post
- `IFeedTimelineRepository`：写回 inbox（ZSET）
- `IFeedNegativeFeedbackRepository`：重建时过滤掉负反馈内容
- `IFeedActiveRepository`：回归时 touch active，保证后续 pushes 能进入 inbox

重建流程（pseudocode，Phase 2 固定）：
```
activeRepo.touch(userId)

followings = adjacencyCachePort.listFollowing(userId, feed.rebuild.maxFollowings)

allPosts = []
for each targetId in followings:
  page = contentRepo.listUserPosts(targetId, cursor=null, limit=feed.rebuild.perFollowingLimit)
  allPosts.addAll(page.posts)

sorted = sort(allPosts, by createTime desc then postId desc)
top = sorted.takeFirst(feed.rebuild.inboxSize)

for post in top:
  if negRepo.contains(userId, post.postId):
    continue
  timelineRepo.addToInbox(userId, post.postId, post.createTime)

return timelineRepo.pageInbox(userId, cursor=null, limit=requestLimit)
```

边界情况（必须写进实现里）：
- followings 为空：直接返回空列表（但仍 `touch` active）
- 关注的人都没发过内容：重建后仍为空，返回空（但 active marker 存在，后续有新内容会被推入 inbox）

### 10.4 Phase 2 验收点（必须通过）

1. 在线推生效：同一作者发帖时，写入 inbox 的粉丝数显著小于总粉丝数（只命中活跃标记存在的用户）
2. 离线拉生效：离线用户回归拉 timeline 首页，不应长期只返回空（如果关注对象有已发布内容，应触发重建并返回近况）
3. 资源回收：用户停止使用超过 `feed.active.ttlDays` 后，不再接收 pushes；其 inbox key 在 `feed.inbox.ttlDays` 后自然过期释放内存

### 10.5 Phase 2 可改进点（对照《从小白到架构师(4): Feed 流系统实战》）

下面这些不是“学术洁癖”，都是会在真实线上踩坑的点（来源标注：见标题《从小白到架构师(4): Feed 流系统实战》）。

#### 10.5.1 离线拉触发条件要更严谨（否则会漏内容）

问题：当前 Phase 2 方案里，“是否推送”由 `feed:active:{userId}` 决定，但“是否重建”只看 `feed:inbox:{userId}` 是否 miss。  \n+当 `feed.inbox.ttlDays` 大于 `feed.active.ttlDays` 时，会出现很糟糕的行为：

- 用户超过 `feed.active.ttlDays` 没打开 App：active 过期 → fanout 不再推送给他
- 但他的 inbox key 可能还没过期：首页读取不触发重建
- 结果：用户会漏掉离线期间关注作者的新内容（这直接违反“离线拉”的语义）

改进（推荐二选一）：

A) 更贴近原文（推荐）：把“在线/离线”定义为“inbox 缓存是否存在”，去掉 `feed:active` 这条线
- 在线用户：`EXISTS feed:inbox:{userId} == true`
- fanout：只对 inbox 存在的粉丝写入（“只推送给 Timeline 缓存未失效的用户”）
- 离线拉：首页 `cursor` 为空且 inbox miss 时重建
- 注意：必须解决“空 Timeline 也要有 key”的问题（见 10.5.2）

B) 保留 `feed:active`：离线回归时强制触发一次重建（不依赖 inbox 是否 miss）

pseudocode（语义级）：
```
wasOffline = !activeRepo.isActive(userId)   // touch 之前判断
activeRepo.touch(userId)

if cursor is blank and wasOffline:
  rebuildService.rebuildIfNeeded(userId)
```

#### 10.5.2 解决“空 Timeline / 无更多数据”的缓存穿透（避免反复重建）

问题：当用户关注列表为空、或关注的人都没有发过内容时，离线重建会得到空结果。  \n+如果你只靠 “inbox key 是否存在” 判定 miss，那么空结果可能导致每次首页请求都触发重建（CPU/DB 被白白烧掉）。

改进（推荐二选一）：

A) 原文方案：在 ZSET 里放一个 “NoMore” 标志
- 空 Timeline：ZSET 里只有 NoMore
- 读侧：`pageInbox` 过滤掉 NoMore（对上层仍表现为“空列表”）
- 重建结束：无论是否有内容，都保证 inbox key 存在（避免反复 miss）

B) 更干净（推荐）：用独立 marker key 表达“已重建但为空”
- key：`feed:inbox:empty:{userId}`（String 值 `"1"` + TTL，与 inbox TTL 同步）
- 重建结束后：如果 top 为空，写入该 marker（并且不要创建空 ZSET）
- 首页请求：若 inbox miss 但 empty marker 存在，直接返回空（不重建）
- 如果用户 follow/unfollow 发生变化，可以在关系事件里把这个 marker evict 掉

#### 10.5.3 重建要原子化 + 防并发重建（不要暴露“重建一半”的数据）

问题：多请求并发时，可能出现：
- A 请求触发重建写了一半，B 请求读到了“半页数据”
- 多个请求同时重建，同一份工作被做 N 次

改进（来源：《从小白到架构师(4): Feed 流系统实战》“拉取操作要注意保持原子性不要将重建了一半的 Timeline 暴露出去”）：

- 加重建锁：`SET feed:inbox:rebuild:lock:{userId} 1 NX EX 30`
- 写临时 key：`feed:inbox:tmp:{userId}:{epochMs}`，把 ZADD 都写到临时 key
- 重建完成后 `RENAME` 覆盖正式 inbox key，再 `EXPIRE` 与裁剪（保证切换原子）

#### 10.5.4 大规模推送要拆分任务（避免单条消息做完“全世界”）

问题：即使只推活跃粉丝，头部作者的活跃粉丝也可能很多。单条 MQ 消息里跑完整个 fanout：
- 处理时间太长，失败重试会“从头再来”
- 单机 worker 吃不下，扩不起来

改进（来源：《从小白到架构师(4): Feed 流系统实战》“将大型推送任务拆分成多个子任务”）：
- PostPublishedEvent consumer 只做 dispatcher：按粉丝分页切片成多个 `FeedFanoutTask`
- 每个 Task 只处理一个 slice（比如 1k 粉丝），失败重试只重试这一片
- 多 worker 并行消费 Task，提高吞吐

#### 10.5.5 推模型的关注/取关补偿（让体验更像“正常产品”）

来源：《从小白到架构师(4): Feed 流系统实战》“新增关注或取消关注也要各自实现相应逻辑”：
- follow：如果用户是“在线”，可选地把 targetId 最近 K 条内容回填到该用户 inbox（否则等用户下次首页触发重建）
- unfollow：简单策略是“不回收历史”（让 TTL 自然过期）；更强策略是 evict inbox 让其下次首页重建（会更准，但更重）

---

## 11. Phase 3（推荐与排序）：先留接口，不要现在就实现复杂系统

你只需要在 Phase 3 做两件事：
1. 召回：关注 + 推荐（推荐不可用要有兜底）
2. 排序：简单规则先跑，再演进模型

推荐不可用兜底策略（“广场推荐保底”）：
- 维护一个全站最新 N 条的 ZSET：`feed:global:latest`
- 推荐服务挂了就返回它

来源标注：
- 《feed服务项目设计思考》（广场推荐页保底策略）
