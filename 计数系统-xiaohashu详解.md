# xiaohashu 计数系统详解

## 1. 总体定位

`xiaohashu` 的计数系统是一个“业务模块先产生活动消息，count 服务做 Redis/DB 双写维护，data-align 再做日增量对账”的体系。

它没有像 `zhiguang` 那样区分“位图事实层”和“SDS 汇总层”，而是采用更直接的结构：

- 实时缓存层：Redis Hash
- 持久化层：`t_note_count`、`t_user_count`
- 事件层：RocketMQ 多 Topic 拆分
- 补偿层：`xiaohashu-data-align` 日增量对账任务

核心模块：

- 业务生产端
  - `xiaohashu-note`
  - `xiaohashu-user-relation`
  - `xiaohashu-comment`
- 计数服务
  - `xiaohashu-count`
- 对账补偿
  - `xiaohashu-data-align`

核心代码：

- `xiaohashu/xiaohashu-count/xiaohashu-count-biz/src/main/java/com/quanxiaoha/xiaohashu/count/biz/consumer/*`
- `xiaohashu/xiaohashu-user-relation/.../FollowUnfollowConsumer.java`
- `xiaohashu/xiaohashu-note/.../LikeUnlikeNoteConsumer.java`
- `xiaohashu/xiaohashu-note/.../CollectUnCollectNoteConsumer.java`
- `xiaohashu/xiaohashu-comment/.../Comment2DBConsumer.java`
- `xiaohashu/xiaohashu-data-align/...`

## 2. 数据模型

SQL 定义在：

- `xiaohashu/sql/xiaohashu_init.sql`

### 2.1 行为表

- `t_following`
- `t_fans`
- `t_note_like`
- `t_note_collection`
- `t_comment`

这些表保存关系事实或行为事实。

### 2.2 计数表

- `t_note_count`
  - `note_id`
  - `like_total`
  - `collect_total`
  - `comment_total`

- `t_user_count`
  - `user_id`
  - `fans_total`
  - `following_total`
  - `note_total`
  - `like_total`
  - `collect_total`

这是 `xiaohashu` 的长期持久化快照层。

### 2.3 Redis 计数缓存

定义在：

- `xiaohashu/xiaohashu-count/xiaohashu-count-biz/src/main/java/com/quanxiaoha/xiaohashu/count/biz/constant/RedisKeyConstants.java`

用户计数：

- Key：`count:user:{userId}`
- Field：
  - `fansTotal`
  - `followingTotal`
  - `noteTotal`
  - `likeTotal`
  - `collectTotal`

笔记计数：

- Key：`count:note:{noteId}`
- Field：
  - `likeTotal`
  - `collectTotal`
  - `commentTotal`

评论计数：

- Key：`count:comment:{commentId}`
- Field：
  - `childCommentTotal`
  - `likeTotal`

本质上都是 Redis Hash。

## 3. Topic 拆分方式

定义在：

- `xiaohashu/xiaohashu-count/xiaohashu-count-biz/src/main/java/com/quanxiaoha/xiaohashu/count/biz/constant/MQConstants.java`

与计数直接相关的 Topic 有：

- `CountFollowingTopic`
- `CountFansTopic`
- `CountFans2DBTopic`
- `CountFollowing2DBTopic`
- `CountNoteLikeTopic`
- `CountNoteLike2DBTopic`
- `CountNoteCollectTopic`
- `CountNoteCollect2DBTTopic`
- `CountNoteCommentTopic`
- `NoteOperateTopic`
- `CommentLikeUnlikeTopic`
- `CountCommentLike2DBTTopic`

可以看出它不是一个统一事件总线，而是“每种计数一条或两条独立链路”。

## 4. 关注/粉丝计数链路

### 4.1 业务入口

用户关系主链路在：

- `xiaohashu/xiaohashu-user-relation/.../FollowUnfollowConsumer.java`

这个消费者处理关注/取关顺序消息，完成：

1. 关注时写 `t_following`
2. 同事务写 `t_fans`
3. 更新 Redis 粉丝 ZSet
4. 发送两条计数 MQ
   - `TOPIC_COUNT_FOLLOWING`
   - `TOPIC_COUNT_FANS`

取关时则反向删除关系记录并发送同样的计数消息。

### 4.2 following 计数

`CountFollowingConsumer` 逻辑很直接：

1. 消费 `CountFollowingTopic`
2. 解析 `CountFollowUnfollowMqDTO`
3. 对 `count:user:{userId}` 的 `followingTotal` 做 `+1/-1`
4. 再发一条 `CountFollowing2DBTopic`

`CountFollowing2DBConsumer` 负责：

1. 消费落库消息
2. 调用 `UserCountDOMapper.insertOrUpdateFollowingTotalByUserId(count, userId)`

也就是说 following 是：

- Redis 先更新
- DB 再异步更新
- 单条消息，不做聚合

代码里明确说明了原因：

- 单个用户短时间内无法关注大量用户
- 所以不需要像点赞那样做批量聚合

### 4.3 fans 计数

`CountFansConsumer` 和 following 不同，它做了聚合：

1. 消费 `CountFansTopic`
2. 用 `BufferTrigger`
3. 1 秒或 1000 条触发一次聚合
4. 按 `targetUserId` 分组
5. 汇总成 `userId -> delta`
6. 更新 Redis `fansTotal`
7. 发送 `CountFans2DBTopic`

`CountFans2DBConsumer` 再把聚合后的结果写入 `t_user_count`。

这里说明：

- `xiaohashu` 认为粉丝侧热点更明显，值得聚合
- following 侧则按单条直接处理

## 5. 笔记点赞计数链路

### 5.1 行为事实先落业务表

点赞行为主链路并不在 count 服务，而在 note 服务：

- `xiaohashu/xiaohashu-note/.../LikeUnlikeNoteConsumer.java`

这一步做的是：

1. 消费点赞/取消点赞顺序消息
2. 按 `userId` 分组
3. 再按 `noteId` 二次分组
4. 对同一用户同一笔记的偶数次操作直接抵消
5. 奇数次操作只保留最后一次
6. 批量写入 `t_note_like`

这是 `xiaohashu` 点赞链路里最关键的“去抖+幂等折叠”点。

也就是说：

- count 服务不负责判重
- 判重主要发生在 note 业务事实落库阶段

### 5.2 count 服务更新 Redis

`CountNoteLikeConsumer` 消费 `LikeUnlikeTopic`。

它会：

1. 用 `BufferTrigger` 把 1 秒内消息聚合
2. 按 `noteId` 分组
3. 统计该笔记净增量 `finalCount`
4. 同时带出 `creatorId`
5. 若 `count:note:{noteId}` 存在，则更新 `likeTotal`
6. 若 `count:user:{creatorId}` 存在，则更新作者收到的 `likeTotal`
7. 把聚合后的结果发到 `CountNoteLike2DBTopic`

注意这里有一个非常明确的设计：

- Redis key 不存在时，不主动初始化
- 只在 key 已存在时增量更新
- 初始化放到查询链路去做

### 5.3 DB 落库

`CountNoteLike2DBConsumer` 负责：

1. 消费聚合后的点赞增量列表
2. 对每条记录在事务中：
   - 更新 `t_note_count.like_total`
   - 更新 `t_user_count.like_total`

也就是：

- 笔记本身的获赞数
- 作者收到的总点赞数

是在同一条落库链路里一起维护的。

## 6. 笔记收藏计数链路

和点赞几乎同构。

### 6.1 业务事实

在：

- `xiaohashu/xiaohashu-note/.../CollectUnCollectNoteConsumer.java`

它先更新 `t_note_collection`，成功后再发 `CountNoteCollectTopic`。

### 6.2 Redis 计数

`CountNoteCollectConsumer` 会：

1. 聚合 1 秒内收藏/取消收藏消息
2. 按 `noteId` 汇总净增量
3. 更新 `count:note:{noteId}.collectTotal`
4. 更新 `count:user:{creatorId}.collectTotal`
5. 再发 `CountNoteCollect2DBTTopic`

### 6.3 DB 落库

`CountNoteCollect2DBConsumer` 在事务中同步更新：

- `t_note_count.collect_total`
- `t_user_count.collect_total`

## 7. 笔记评论计数链路

### 7.1 评论落库后发计数 MQ

评论主链路在：

- `xiaohashu/xiaohashu-comment/.../Comment2DBConsumer.java`

它会：

1. 批量把评论元数据写入 `t_comment`
2. 把评论内容写入 KV 服务
3. 同步一级评论到 Redis 热点评论 ZSet
4. 组装 `CountPublishCommentMqDTO`
5. 发送 `CountNoteCommentTopic`

### 7.2 note 维度评论数

`CountNoteCommentConsumer` 会：

1. 聚合消息
2. 把每批消息展开成评论 DTO 列表
3. 按 `noteId` 分组
4. 统计评论条数
5. 更新 Redis `count:note:{noteId}.commentTotal`
6. 同步执行 `NoteCountDOMapper.insertOrUpdateCommentTotalByNoteId`

这里和点赞/收藏不同：

- 评论计数没有再拆出一个“2DB 主题”
- Redis 更新和 DB 更新在同一个消费者里完成

### 7.3 评论删除反向扣减

删除链路在：

- `xiaohashu/xiaohashu-comment/.../DeleteCommentConsumer.java`

会做：

- 减少笔记评论总数
- 减少一级评论的子评论数
- 同时更新 Redis 和 `t_note_count/t_comment`

所以评论计数比点赞/收藏更像“评论业务内部自维护”。

## 8. 笔记发布数链路

`NoteServiceImpl` 在笔记发布/删除后发送：

- `NoteOperateTopic:publishNote`
- `NoteOperateTopic:deleteNote`

`CountNotePublishConsumer` 消费后：

1. 更新 Redis `count:user:{creatorId}.noteTotal`
2. 更新 `t_user_count.note_total`

这里没有额外的 2DB topic，和评论数一样是单消费者内完成。

## 9. 评论点赞计数链路

虽然用户问题重点通常在 note/user 计数，但 `xiaohashu` 还维护了评论维度计数：

- Redis Key：`count:comment:{commentId}`
- 字段：
  - `likeTotal`
  - `childCommentTotal`

相关消费者：

- `CountCommentLikeConsumer`
- `CountCommentLike2DBConsumer`
- `CountNoteChildCommentConsumer`

这说明 `xiaohashu` 的计数体系覆盖范围更散，除了用户和笔记，还深入到了评论实体。

## 10. 读链路

### 10.1 评论查询

`CommentServiceImpl` 的读侧最典型。

它会：

1. 先读 Redis Hash
   - 笔记评论总数：`buildNoteCommentTotalKey(noteId)`
   - 一级评论子评论数：`buildCountCommentKey(parentCommentId)`
2. 如果 Redis 缺失
3. 再查数据库：
   - `t_note_count.comment_total`
   - `t_comment.reply_total/like_total`
4. 再异步把结果回填 Redis

这一点非常关键：

- `xiaohashu` 的 Redis 计数不是永久真相源
- 它更像“有过期时间的热点缓存”
- 真正的持久基线仍然是 `t_note_count / t_user_count / t_comment`

### 10.2 搜索链路

搜索索引构建直接 join 计数表：

- `xiaohashu/xiaohashu-search/xiaohashu-search-biz/src/main/resources/mapper/SelectMapper.xml`

例如：

- ES 笔记索引 join `t_note_count`
- ES 用户索引 join `t_user_count`

这说明：

- 搜索对外的计数来源主要依赖 DB 快照
- 不是直接依赖 Redis

## 11. data-align 补偿链路

这是 `xiaohashu` 计数体系里很重要的一层。

### 11.1 日增量记录

例如 `TodayNoteLikeIncrementData2DBConsumer`：

1. 消费 `CountNoteLikeTopic`
2. 取出变更的 `noteId` 和 `noteCreatorId`
3. 用 Bloom Filter 判断这一天是否已经登记过该对象
4. 若没有，则把对象 ID 记入 `t_data_align_*_temp_日期_分片`

同类逻辑还覆盖：

- note like
- note collect
- following
- fans
- user like
- user collect
- note publish

### 11.2 次日对账任务

例如：

- `NoteLikeCountShardingXxlJob`
- `FansCountShardingXxlJob`

处理方式：

1. 读取昨天临时表里的变更对象 ID
2. 对事实表做 `count(*)`
   - 点赞数从 `t_note_like`
   - 粉丝数从 `t_fans`
3. 回写 `t_note_count / t_user_count`
4. 如果 Redis key 存在，也同步覆盖 Redis Hash
5. 触发搜索索引重建
6. 删除该批临时记录

所以 `xiaohashu` 的最终一致性并不只靠主链路 MQ，还靠次日的增量纠偏任务。

## 12. 一致性策略总结

`xiaohashu` 的一致性分三层：

1. 业务事实层

- 先写 `t_note_like / t_note_collection / t_following / t_fans / t_comment`
- 顺序消费、唯一索引、内存折叠来减少重复写

2. 实时计数层

- count 服务消费业务消息
- 更新 Redis Hash
- 再异步落库到 `t_note_count / t_user_count`

3. 离线补偿层

- data-align 记录当日有变化的对象
- 次日分片任务按事实表 `count(*)` 回刷快照

这套方案的特点是：

- 实现直接，容易理解
- Redis 只是热点缓存，不承担唯一事实职责
- DB 计数表是主要快照基线
- 离线对账是系统级兜底

## 13. 业务链路汇总

### 13.1 点赞

1. 用户点赞请求进入 note 服务
2. 顺序消息落 `t_note_like`
3. 成功后发 MQ 给 count 服务
4. count 服务聚合后更新 Redis：
   - 笔记点赞数
   - 作者获赞数
5. 再发 2DB Topic 落 `t_note_count / t_user_count`
6. data-align 记录今日增量
7. 次日任务按事实表重新 count 纠偏

### 13.2 收藏

1. note 服务更新 `t_note_collection`
2. 发 `CountNoteCollectTopic`
3. count 服务聚合后更新 Redis
4. 再异步更新 `t_note_count / t_user_count`
5. data-align 次日对账

### 13.3 关注/取关

1. user-relation 服务更新 `t_following / t_fans`
2. 更新粉丝列表 ZSet
3. 分别发 following/fans 计数 Topic
4. count 服务更新 Redis
5. 再异步更新 `t_user_count`
6. data-align 次日纠偏

### 13.4 评论

1. comment 服务批量写 `t_comment`
2. 发 `CountNoteCommentTopic`
3. count 服务更新 Redis + `t_note_count`
4. 删除评论时做反向扣减
5. 查询时若 Redis 缺失，再从 DB 回填

## 14. 结论

`xiaohashu` 的计数系统本质上是：

- 业务事实表先落地
- count 服务异步维护 Redis Hash 和计数表
- data-align 再做日增量对账

它不是一个统一的“计数基础设施”，而是一个按业务类型拆开的计数服务集群，优点是落地快、模块清晰，代价是：

- 主题多
- 链路分散
- 对账依赖重
- 没有像位图那样的统一事实层
