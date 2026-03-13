# Nexus Feed B+ 任务1 前置检查

- 日期：2026-03-10
- 执行者：Codex
- 范围：`project/nexus`
- 对应任务：`26d02c15-7422-43a0-a866-8a131d2acba6`

## 检查结论

当前代码库已经具备“关系查询回归 DB”的基础条件，但还没有把接口、实现、调用链和 SQL 约束统一收束干净。

简单说：

1. `IRelationAdjacencyCachePort` 还保留了 `rebuildFollowing/rebuildFollowers`
2. `RelationAdjacencyCachePort` 仍然是一套完整的 Redis 邻接缓存协议实现
3. `following/followers` 的 DB keyset 查询已经存在于 `user_relation` 路径
4. `user_follower` 这条路径仍然保留 offset 分页与 count(*) 习惯
5. Feed 读取依赖 `listFollowing`，但关系页查询其实已经直接走 DB

## 一、事实

### 1. 接口现状

文件：`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/adapter/port/IRelationAdjacencyCachePort.java`

当前接口方法为：

- `addFollow`
- `removeFollow`
- `listFollowing`
- `listFollowers`
- `pageFollowing`
- `pageFollowers`
- `rebuildFollowing`
- `rebuildFollowers`
- `evict`

结论：

- 方案要求保留的主体查询契约都在
- 方案要求删除的 `rebuildFollowing/rebuildFollowers` 也还在

### 2. Port 实现现状

文件：`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/RelationAdjacencyCachePort.java`

当前实现事实：

- 依赖 `StringRedisTemplate`
- 依赖 `RedissonClient`
- 依赖 `IRelationRepository`
- 依赖 `IFollowerDao`
- `listFollowing/listFollowers` 先走 `pageFollowing/pageFollowers`
- `pageFollowing/pageFollowers` 当前是“缓存 ready 则读 Redis，否则回 DB”双路径
- 存在 `rebuildFollowing/rebuildFollowers`
- 存在 `tmpKey/finalKey/readyKey/rebuildingKey/lockKey`
- 存在 `rename` 原子切换
- 存在 rebuild 期间镜像增量补写
- 存在围绕 rebuild 的分布式锁与等待 ready 逻辑
- rebuild 加载 following/followers 时使用 `IFollowerDao.selectFollowingRows/selectFollowerRows`
- rebuild 加载过程是 `offset + limit` 扫描

结论：

- 这是完整关系邻接缓存协议，不是轻量门面
- 当前实现和 B+ 正式方案目标正面冲突，后续必须大幅删减

### 3. DB 查询入口现状

文件：

- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/adapter/repository/IRelationRepository.java`
- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/RelationRepository.java`
- `project/nexus/nexus-infrastructure/src/main/resources/mapper/social/RelationMapper.xml`

当前已有 DB 查询入口：

- `pageActiveFollowsBySource(Long sourceId, Date cursorTime, Long cursorTargetId, int limit)`
- `pageActiveFollowsByTarget(Long targetId, Date cursorTime, Long cursorSourceId, int limit)`

对应 SQL 事实：

- 基于 `user_relation`
- 条件包含 `source_id/target_id + relation_type + status`
- 游标条件为：`create_time < cursorTime OR (create_time = cursorTime AND user_id < cursorUserId)`
- 排序为 `ORDER BY create_time DESC, target_id/source_id DESC`
- 使用 `LIMIT #{limit}`

结论：

- `following/followers` 的 keyset pagination 已经存在
- 后续可以直接复用，不需要新发明分页协议

### 4. `user_follower` 路径现状

文件：

- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/dao/social/IFollowerDao.java`
- `project/nexus/nexus-infrastructure/src/main/resources/mapper/social/FollowerMapper.xml`
- `project/nexus/docs/social_schema.sql`

当前事实：

- `user_follower` 表存在
- 表结构索引只有：`uk_user_follower(user_id, follower_id)` 和 `idx_user_time(user_id, create_time)`
- `IFollowerDao` 提供 `selectFollowerIds/selectFollowingIds/selectFollowerRows/selectFollowingRows/countFollowers`
- `FollowerMapper.xml` 的分页查询全部是 `LIMIT ... OFFSET ...`
- `selectFollowingIds/selectFollowingRows` 按 `follower_id` 查询，但 schema 中没有以 `follower_id` 为前缀的专用索引

结论：

- `user_follower` 适合承接 followers 读模型，但当前 SQL 还不是 keyset
- `following` 若走 `user_follower` 反查，当前索引不够好
- `followers` 若要优先走 `user_follower`，后续需要把分页方式和索引约束补齐

### 5. 调用方现状

#### 5.1 直接依赖 `IRelationAdjacencyCachePort` 的服务

已确认调用方：

- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/RelationService.java`
- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/FeedService.java`
- `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/FeedInboxRebuildService.java`

#### 5.2 当前调用事实

- `RelationService` 在 `follow/unfollow` 成功后的 `afterCommit` 中调用 `addFollow/removeFollow`
- `FeedService` 有两处 `listFollowing(userId, limit)` 调用
- `FeedInboxRebuildService` 在构建 inbox rebuild 目标集合时调用 `listFollowing(userId, limit)`

#### 5.3 反向事实

- 代码库中未发现 `rebuildFollowing(` 的业务调用点，只有接口定义与实现本身
- 代码库中未发现 `rebuildFollowers(` 的业务调用点，只有接口定义与实现本身
- 代码库中未发现 `listFollowers(` 的业务调用点
- 代码库中未发现 `pageFollowing/pageFollowers` 的业务调用点
- 关系页查询服务 `RelationQueryService` 当前已经直接走 `IRelationRepository.pageActiveFollowsBySource/pageActiveFollowsByTarget`

结论：

- 删除 rebuild 语义的外部破坏面很小
- `pageFollowing/pageFollowers` 虽然要保留在 Port 层，但当前并不是关系页主路径
- 关系页其实已经先一步走在 B+ 方案想要的方向上：直接 DB keyset

### 6. 计数与深分页现状

当前事实：

- `RelationMapper.xml` 中 `countActiveBySource/countActiveByTarget` 仍然存在
- `FollowerMapper.xml` 中 `countFollowers` 仍然存在
- `IRelationRepository.listFollowerIds/countFollowerIds` 仍在被 `FeedDistributionService` 等 fanout 逻辑使用
- `listFollowerIds` 目前是 `offset + limit`

结论：

- “禁止热路径频繁 count(*)” 这条约束在关系页/Feed 查询上需要继续守住
- 但 fanout 分发侧当前仍依赖 follower count 和 offset 切片，这属于相邻链路，不是本次 adjacency port 收缩的主任务
- 后续编码时要避免把这个相邻链路误伤成和 B+ 同一个改造面

## 二、推论

### 1. 可以直接保留的接口

建议保留：

- `addFollow`
- `removeFollow`
- `listFollowing`
- `listFollowers`
- `pageFollowing`
- `pageFollowers`
- `evict`

原因：

- 这些方法仍然能作为“关系查询门面 + 最小写后处理门面”存在
- 保留它们可以减少 `RelationService`、`FeedService`、`FeedInboxRebuildService` 的改动范围

### 2. 应直接删除的语义

建议删除：

- `rebuildFollowing`
- `rebuildFollowers`
- `tmpKey`
- `ready/rebuilding`
- rebuild 锁
- 临时集合 `rename`
- rebuild 期间镜像增量补写
- rebuild 扫描 `IFollowerDao.selectFollowingRows/selectFollowerRows`

原因：

- 这些东西不是业务本质，只是为了维护完整邻接缓存而存在
- 它们正是并发复杂度和 corner case 的来源

### 3. 后续编码可以直接复用的 DB 路径

建议优先复用：

- `IRelationRepository.pageActiveFollowsBySource`
- `IRelationRepository.pageActiveFollowsByTarget`
- `RelationMapper.xml` 中现有 keyset 查询

原因：

- 这条路径已经是成熟的 DB keyset 分页
- `RelationQueryService` 已经在用，说明契约和数据格式是通的

### 4. 当前索引与 SQL 缺口

已确认缺口：

1. `user_relation` 文档索引当前只有 `idx_source_status(source_id, status)`，不足以完整覆盖 `source_id/target_id + relation_type + status + create_time` 的 keyset 热路径
2. `RelationMapper.xml` 的 `pageActiveByTarget` 走 `target_id` 条件，但 `social_schema.sql` 中未看到以 `target_id` 为前缀的专用索引定义
3. `user_follower` 对 `follower_id` 方向的 following 反查没有专用索引
4. `FollowerMapper.xml` 全部还是 offset 分页，不满足正式方案的分页硬约束

结论：

- 代码层的 DB 查询入口够用
- DDL/索引层还不够扎实，这会成为任务 4 的明确改造点

## 三、任务 2~4 的直接输入

### 任务 2 输入

- 删 `IRelationAdjacencyCachePort` 的 `rebuildFollowing/rebuildFollowers`
- 删实现类中的同名方法
- 删任何围绕 rebuild 的残留调用和注释

### 任务 3 输入

- 把 `RelationAdjacencyCachePort` 收缩为纯 DB 门面
- `listFollowing/listFollowers` 直接走 DB
- `pageFollowing/pageFollowers` 直接走 `IRelationRepository` keyset 查询
- 去掉 Redis/Redisson/IFollowerDao 依赖

### 任务 4 输入

- 校正 `following/followers` 的最终 DB 路径与索引要求
- 如果 followers 保持走 `user_relation.pageActiveFollowsByTarget`，要补 target 向索引
- 如果 followers 改走 `user_follower`，要一并补 keyset SQL 与索引

## 四、验收结论

本任务已完成预期检查目标：

- 已确认保留接口清单
- 已确认删除语义清单
- 已确认 DB 查询入口与 keyset 现状
- 已确认索引与 SQL 缺口
- 已确认调用方影响面

下一步可以进入任务 2：`收缩 IRelationAdjacencyCachePort 契约并删除 rebuild 语义`
