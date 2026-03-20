# 路线2：聚合查询重构规范（仅代理用）

**目标**
- 让代理在 `nexus` 做“聚合查询/读时拼装”重构时，先按类型分类，再选正确策略：详情=局部并发；列表=批量优先；树形=先改查询形状；轻量渲染=不并发。
- 消灭两类坏改法：在循环里开 `CompletableFuture`；把已有批量接口拆回单查。

**适用范围**
- 仅约束代理的行为与输出，不要求人类开发者照抄。
- 仅覆盖读链路“聚合返回”的代码（QueryService/AssembleService/读时拼装）。

---

## 0) 先做分类（必须）

代理拿到一个需求时，先回答一句话：这个返回形状属于哪一类？

1. **详情聚合**：返回 1 个对象（一个页面/一个详情）。
2. **列表聚合**：返回一串对象（卡片列表/关注列表等）。
3. **树形聚合**：返回层级结构（根 + 子节点预览/分页）。
4. **轻量渲染**：主要是内存拼字段，不做跨存储拼装。

如果分类不确定，先停下做最小范围的代码定位（入口、返回 DTO 形状、依赖仓储/端口），再分类。

---

## 1) 决策规则（路线2核心）

一句话原则：**能批量就批量；必须拼独立慢 IO 才并发；树形先改查询形状。**

### 1.1 详情聚合（允许局部并发）

满足任一条件才允许并发：
- 需要拼装的 fragment 是“互相独立的慢 IO”（例如 Redis + DB + 远程 RPC 这种性质）。
- fragment 数量小（建议 2-5 个），且每个 fragment 都能失败降级，不改变用户可见语义。

禁止：
- 仅为了“看起来更快”把同一个存储的多次查询并发化（通常只是在放大压力）。
- 使用默认公共线程池（必须受控 executor）。

### 1.2 列表聚合（强制批量优先）

默认策略：
- 输入先 `去重 + 保序`。
- 所有 IO 必须在批量阶段完成。
- 组装阶段按顺序回填，不允许在循环里做 IO，更不允许在循环里开 future。

允许的并发（很少）：
- “阶段级并发”：例如 base 批量查询和 count 批量查询来自不同存储且互不影响，并且有受控 executor + 超时 + 降级。

### 1.3 树形聚合（先改查询形状）

默认策略：
- 先批量拿 roots。
- 必须提供“批量拿 children/previews”的仓储能力（`Map<rootId, List<child>>`），禁止每个 root 单独查一次。
- 再批量补用户资料等展示字段（roots + children 合并去重后一次性批量查）。

禁止：
- 用 `CompletableFuture` 在每个 root 上并发拉 children（这是用线程池掩盖设计问题）。

### 1.4 轻量渲染（不并发）

只做内存渲染，不引入并发，也不引 IO。

---

## 2) 四类模板（代理必须套模板写）

### 2.1 详情聚合模板（core + fragments）

步骤（固定顺序）：
1. 先拿 `core/稳定快照`（必要时用本地缓存 + SingleFlight 收口并发 miss）。
2. 把补充字段拆成 `fragments`（每个 fragment 独立 IO，失败可降级）。
3. 并发跑 fragments（受控 executor；每个 fragment 都要：超时 + 降级默认值）。
4. 合并 `core + fragments` 生成 DTO。

本仓库例子：
- `C:\\Users\\Administrator\\Desktop\\文档\\project\\nexus\\nexus-trigger\\src\\main\\java\\cn\\nexus\\trigger\\http\\social\\support\\ContentDetailQueryService.java`
- `C:\\Users\\Administrator\\Desktop\\文档\\project\\nexus\\nexus-domain\\src\\main\\java\\cn\\nexus\\domain\\user\\service\\UserProfilePageQueryService.java`

### 2.2 列表聚合模板（ids + batch loaders + render）

步骤（固定顺序）：
1. 收集 ids：去重、保序。
2. 批量拿稳定字段（base）。
3. 批量拿 fragments（count/profile/state 等），优先使用已有批量接口。
4. 只在内存里组装，按原顺序回填。

本仓库例子：
- `C:\\Users\\Administrator\\Desktop\\文档\\project\\nexus\\nexus-domain\\src\\main\\java\\cn\\nexus\\domain\\social\\service\\FeedCardAssembleService.java`
- `C:\\Users\\Administrator\\Desktop\\文档\\project\\nexus\\nexus-domain\\src\\main\\java\\cn\\nexus\\domain\\social\\service\\RelationQueryService.java`

### 2.3 树形聚合模板（roots + batch previews + batch profiles）

步骤（固定顺序）：
1. 批量拿 roots（置顶 + 分页 roots）。
2. 批量拿 previews/children（按 rootId 分组返回 Map）。
3. roots + children 合并 userId 后一次性批量补 profile。
4. 内存过滤可见性，避免为过滤触发 IO。

本仓库例子：
- `C:\\Users\\Administrator\\Desktop\\文档\\project\\nexus\\nexus-domain\\src\\main\\java\\cn\\nexus\\domain\\social\\service\\CommentQueryService.java`

### 2.4 轻量渲染模板（纯内存）

步骤：
1. 不查库、不查缓存、不并发。
2. 仅对已有数据渲染字段。

本仓库例子：
- `C:\\Users\\Administrator\\Desktop\\文档\\project\\nexus\\nexus-domain\\src\\main\\java\\cn\\nexus\\domain\\social\\service\\InteractionService.java`

---

## 3) 代理硬禁令（违反就停止）

- 禁止在循环里开 `CompletableFuture`（列表 item、树形 root、reply 都算循环）。
- 禁止 `supplyAsync()` 不带受控 executor（不准用默认公共线程池）。
- 禁止把已有批量接口拆回单查（例如 `batchGetCount`、`listByUserIds` 这类）。
- 禁止用线程池掩盖树形查询设计问题（先改查询形状）。

---

## 4) 代理自检清单（每次提交前必须过）

- 我是否先完成分类（详情/列表/树形/轻量）？
- 我是否引入了循环并发？如果是，立即回退。
- 我是否把批量接口拆成单查？如果是，立即回退。
- 我是否使用受控 executor，并为 fragment 设置超时与降级？
- 我是否保持旧的失败语义与返回字段语义不变？
- 我是否把树形问题当成线程池问题在解决？

---

## 5) 下一步（从规范到计划）

当你要把这份规范落到代码上时，先选一个试点：
- 详情类试点：`ContentDetailQueryService`、`UserProfilePageQueryService`
- 树形类试点：`CommentQueryService`（先改查询形状，不先并发）

然后再进入 `writing-plans` 输出可执行的实施计划。
