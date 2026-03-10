# Nexus Feed B+ 方案草稿

- 日期：2026-03-10
- 状态：待选择完善
- 适用范围：`project/nexus`

## 一句话结论

B+ 方案的核心不是继续强化 `RelationAdjacencyCachePort` 这套复杂邻接缓存，
而是把“关系页查询”和“Feed 热路径优化”拆开处理：

- 关系页：以 DB 为真相源
- Feed 热路径：只做专项轻优化
- 禁止继续扩张在线 rebuild / tmpKey / rename / 分布式锁 这一套复杂协议

## 目标

1. 降低 `RelationAdjacencyCachePort` 的并发复杂度
2. 保住 Feed 现有业务语义
3. 在不引入复杂 rebuild 协议的前提下，尽量保住 QPS
4. 让后续维护者能看懂、敢改、不容易再写坏

## 当前判断

- `RelationAdjacencyCachePort` 不是新引入缓存，而是已有缓存
- 当前方案把它往“并发安全重建”方向推复杂了
- 如果继续在这套模型上修补，会不断冒出新的 corner case
- 更合理的路线是：保留接口，降低实现复杂度

## 方案主体

### 1. 接口层策略

默认建议：保留 `IRelationAdjacencyCachePort` 接口不变，只替换实现。

保留方法：
- `listFollowing`
- `listFollowers`
- `pageFollowing`
- `pageFollowers`
- `addFollow`
- `removeFollow`
- `evict`

删除或废弃复杂语义：
- 在线 rebuild
- `tmpKey`
- `rename` 原子切换
- `ready/rebuilding` 标记
- 重建期间写镜像
- 分布式锁保护的邻接缓存重建

### 2. 读路径策略

默认建议：
- 关系页分页：直接走 DB keyset pagination
- Feed 用到的小范围 `listFollowing(userId, limit)`：允许做短 TTL 轻缓存
- 深分页：禁止缓存

### 3. 写路径策略

默认建议：
- `follow/unfollow/block` 成功后，不再维护完整邻接缓存
- 若保留轻缓存，则写后直接删 key
- 不做复杂双写和在线重建

## 对当前 Feed 的影响

### 低影响
- `FeedService` 和 `FeedInboxRebuildService` 的接口调用方式可保持不变
- 业务语义不变：关注源、关注作者集合、inbox rebuild 目标集合不变

### 中等影响
- `listFollowing(userId, limit)` 更可能走 DB 或轻缓存，冷路径会慢一些
- inbox rebuild 更依赖 DB 和预算控制

### 高收益
- 删除关系邻接缓存 rebuild 后，大部分并发竞态会直接消失
- 后续 code review 和维护成本明显下降

## 当前推荐的 B+ 版本（已确认部分）

- 关系页：DB
- Feed：DB 优先
- 重建协议：删除
- 锁：删除
- 邻接表完整缓存：删除
- `listFollowing`：不做缓存，直接 DB
- `listFollowers`：不做缓存，直接 DB

## 已确认选择

1. 撤回 `listFollowing` 轻缓存
2. 关系查询全部 DB 优先

## 已确认选择（更新）

1. 撤回 `listFollowing` 轻缓存
2. 关系查询全部 DB 优先
3. 保留 `IRelationAdjacencyCachePort` 现有接口主体，只替换内部实现
4. `pageFollowing/pageFollowers` 保留在 Port 层，对外契约不变，内部直接走 DB
5. 删除 `rebuildFollowing/rebuildFollowers` 接口与实现，不再保留 rebuild 语义
6. 明确写入约束：没有监控证据，不允许重新给关系查询加轻缓存

## 已确认选择（更新）

1. 撤回 `listFollowing` 轻缓存
2. 关系查询全部 DB 优先
3. 保留 `IRelationAdjacencyCachePort` 现有接口主体，只替换内部实现
4. `pageFollowing/pageFollowers` 保留在 Port 层，对外契约不变，内部直接走 DB
5. 删除 `rebuildFollowing/rebuildFollowers` 接口与实现，不再保留 rebuild 语义
6. 明确写入约束：没有监控证据，不允许重新给关系查询加轻缓存
7. `following / followers` 查询统一要求：keyset pagination + 专用索引优先
8. 禁止 offset 深分页，禁止在热路径频繁 count(*)

## 已确认选择（最终版）

1. 撤回 `listFollowing` 轻缓存
2. 关系查询全部 DB 优先
3. 保留 `IRelationAdjacencyCachePort` 现有接口主体，只替换内部实现
4. `pageFollowing/pageFollowers` 保留在 Port 层，对外契约不变，内部直接走 DB
5. 删除 `rebuildFollowing/rebuildFollowers` 接口与实现，不再保留 rebuild 语义
6. 明确写入约束：没有监控证据，不允许重新给关系查询加轻缓存
7. `following / followers` 查询统一要求：keyset pagination + 专用索引优先
8. 禁止 offset 深分页，禁止在热路径频繁 count(*)
9. 允许在单次请求作用域内，对 `listFollowing/listFollowers` 做显式结果复用
10. 请求内结果复用不是 Redis 缓存，不跨请求、不设 TTL、不做失效广播

## 当前状态

本草稿已完成主要决策，可以作为 B+ 方案的收敛版本继续细化为可实施清单。
