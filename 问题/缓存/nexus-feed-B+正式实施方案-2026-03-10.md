# Nexus Feed B+ 正式实施方案

- 日期：2026-03-10
- 执行者：Codex
- 范围：`project/nexus`
- 来源：`缓存/nexus-feed-B+方案草稿-2026-03-10.md`
- 状态：正式决议，可直接进入实施排期

## 一句话结论

本方案的最终决议是：关系查询统一回到 DB 真相源，保留 `IRelationAdjacencyCachePort` 的主要对外契约，但删除完整邻接缓存、在线 rebuild、重建锁、原子切换和镜像增量这一整套复杂协议。

这不是“缓存优化方案”，这是一次“把错误复杂度拆掉”的收敛改造。

## 背景与问题定义

当前 `RelationAdjacencyCachePort` 一类实现的核心问题，不是功能做不到，而是承担了过多不该由它承担的职责：

1. 既想做关系查询接口，又想做完整邻接缓存容器
2. 既想做在线 rebuild，又想保证并发一致性
3. 既想通过锁保护重建，又想把写流量镜像到临时结构
4. 既想对 Feed 读路径提速，又把关系页、重建任务、热路径耦合到同一套协议里

这类设计的直接后果是：

- 并发路径变多，corner case 变多
- 静态 code review 很难确认所有状态转换都正确
- 维护者必须同时理解缓存、锁、临时 key、切换时机、增量补写，复杂度失控
- 一旦出错，问题会表现为数据不一致、短暂缺失、重复数据、过期数据或难以复现的线上抖动

更关键的是：当前业务上下文并没有给出足够证据，证明 `following/followers` 这一层值得维护如此重的 Redis 邻接缓存协议。

## 最终决议

### 1. 真相源决议

- `following` 查询：DB 是唯一真相源
- `followers` 查询：DB 是唯一真相源
- Feed 依赖的关系集合：DB 是唯一真相源

### 2. 缓存决议

- 撤回此前对 `listFollowing` 轻缓存的选择
- 不为 `listFollowing` 增加跨请求缓存
- 不为 `listFollowers` 增加跨请求缓存
- 不新增任何 Redis 关系邻接表缓存
- 没有监控证据，不允许重新把关系查询接回轻缓存

### 3. 接口决议

保留以下对外契约，避免调用方大范围改线：

- `listFollowing`
- `listFollowers`
- `pageFollowing`
- `pageFollowers`
- `addFollow`
- `removeFollow`
- `evict`

删除以下接口或语义：

- `rebuildFollowing`
- `rebuildFollowers`
- 任意形式的在线 rebuild 语义

## 目标与非目标

### 目标

1. 删除关系缓存协议中的高并发脆弱点
2. 保留 Feed 与关系域的现有业务语义
3. 把实现改成可维护、可验证、可回滚的形态
4. 把 DB 查询规则写成硬约束，避免后续再次滑回复杂缓存

### 非目标

1. 不追求在本次改造中继续榨取关系查询的极限 QPS
2. 不在没有监控数据前提下新增轻缓存试验
3. 不引入新的 rebuild 任务、补偿链路或缓存一致性协议
4. 不修改上层业务的用户可见语义

## 实施原则

1. **接口尽量不动，实现必须大幅简化**
2. **删除特殊情况，优先删除补丁式状态机**
3. **关系查询默认走 DB，不拿 Redis 兜底**
4. **深分页禁止 offset，统一 keyset pagination**
5. **热路径禁止频繁 `count(*)`**
6. **允许的唯一优化，是单次请求作用域内的显式结果复用**

## 接口层实施策略

### `IRelationAdjacencyCachePort`

实施要求：

- 保留现有主体接口，减少调用方改造面
- `pageFollowing/pageFollowers` 继续保留在 Port 层
- 对调用方维持“通过 Port 查询关系”的使用方式
- Port 的职责收缩为“关系查询门面”，不再承担完整缓存容器职责

### `RelationAdjacencyCachePort`

实施要求：

- 内部实现改为 DB 直读
- 删除 Redis 邻接表 rebuild 流程
- 删除 `tmpKey` 构建与 `rename` 切换
- 删除 `ready/rebuilding` 标记
- 删除重建期间镜像增量写入
- 删除围绕 rebuild 的分布式锁协议

## DB 查询实施策略

### 1. 分页规则

- `following / followers` 一律使用 keyset pagination
- 明确禁止 offset 深分页
- 分页游标必须基于稳定排序字段构建，避免翻页抖动和重复数据

### 2. 索引规则

- 优先使用关系表上的专用索引
- `followers` 查询优先走 `user_follower` 或等价专用读模型
- 不允许依赖全表扫描去支撑关系页和 Feed 热路径

### 3. 计数规则

- 热路径不做高频 `count(*)`
- 如页面必须展示总数，应走异步统计、近似值或独立读模型，而不是把精确总数塞进热查询

### 4. 请求内复用规则

本次允许的唯一“小优化”是：单次请求作用域内显式结果复用。

具体要求：

- 只允许在同一次调用链里复用已经查出的 following/follower 结果
- 复用方式采用局部变量、方法参数透传或同一 service 方法内部显式共享
- 不新增 Redis
- 不跨请求保存
- 不设 TTL
- 不做失效广播
- 不引入 ThreadLocal 全局隐式缓存

这意味着：它只是一次查询结果的“少查一次”，不是新的缓存层。

## Feed 链路影响评估

### 不变项

- Feed 的业务语义不变
- 关注关系决定可见性的逻辑不变
- Feed 重建任务的目标用户集合语义不变

### 变化项

- Feed 相关的关系读取默认直接走 DB
- 冷路径的单次查询延迟可能上升
- 但高并发下的数据一致性风险、锁竞争风险和 rebuild 竞态风险显著下降

### 结论

这是一笔明确划算的交易：

- 用可预测的 DB 查询成本
- 换掉不可预测的缓存协议复杂度

## 具体改造范围

以下对象属于本方案的直接改造范围：

1. `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/RelationAdjacencyCachePort.java`
2. `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/FeedService.java`
3. `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/FeedInboxRebuildService.java`
4. 关系页相关调用 `pageFollowing/pageFollowers` 的服务或应用层入口
5. `IRelationAdjacencyCachePort` 及其相关实现与装配位置

## 实施步骤

### 第一步：收缩接口语义

- 从接口和实现中删除 `rebuildFollowing/rebuildFollowers`
- 删除所有依赖 rebuild 语义的调用链、调度入口和实现残留
- 保证 Port 只保留查询与基础失效语义

### 第二步：改写 Port 实现

- 将 `listFollowing/listFollowers` 改成直接 DB 查询
- 将 `pageFollowing/pageFollowers` 改成直接 DB keyset 分页
- 去掉 Redis 邻接表、临时 key、切换 key、状态 key 和配套锁

### 第三步：清理写路径

- `addFollow/removeFollow` 不再维护完整邻接缓存
- `evict` 若仍保留，仅作为兼容清理入口，不再承担 rebuild 触发职责
- 删除与镜像增量补写相关的逻辑分支

### 第四步：落地请求内复用

- 只在明确存在重复查询的同一次调用链中做显式复用
- 复用逻辑写在 service 内部，不扩散为通用缓存框架
- 没有重复查询证据的地方，不做任何额外优化

### 第五步：清理文档与约束

- 删除所有暗示“未来可以随时加回关系轻缓存”的表述
- 把“无监控证据不得重开缓存”写入架构约束
- 在后续 code review 中把它当成硬规则执行

## 验收标准

实施完成后，至少必须满足以下条件：

1. `following/followers` 的读路径不再依赖 Redis 邻接缓存
2. 代码中不存在在线 rebuild、`tmpKey`、`rename` 切换、`ready/rebuilding` 状态标记和镜像增量补写
3. `pageFollowing/pageFollowers` 明确走 keyset pagination
4. 关系页与 Feed 的用户可见业务语义不发生意外变化
5. 高并发下不存在围绕关系邻接缓存 rebuild 的锁竞争与过期竞态
6. 请求内复用若存在，必须是显式、局部、无跨请求状态的实现

## 回滚策略

本方案的回滚边界很明确：

- 若 DB 查询性能不达标，应优先补索引、调 SQL、缩短结果集、拆读模型
- 不允许把回滚理解成“重新打开 rebuild 缓存协议”
- 真正需要重新评估缓存时，前提必须是：有监控证据证明 DB 已经成为瓶颈，且索引与查询优化已经做完

换句话说，回滚的第一选择是继续修 DB，不是把复杂度塞回 Redis。

## 后续约束

1. 没有真实监控数据，不讨论重新加关系轻缓存
2. 没有压测或线上证据，不允许用“可能以后会热”作为设计依据
3. 后续若要优化 Feed 热路径，应优先做 SQL、索引、结果集裁剪和调用链去重
4. 任何新方案如果再次引入 rebuild、锁、双写镜像、临时 key 切换，默认视为倒退方案

## 结论

这份方案不是保守，而是回到正确的数据边界：

- 关系数据归 DB
- Port 负责门面，不负责缓存协议
- Feed 保持语义稳定
- 优化只做有证据、低复杂度、可验证的部分

正式实施时，必须把“删除复杂度”当成主目标，而不是换一种方式把复杂度重新藏起来。
