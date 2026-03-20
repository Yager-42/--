# Aggregation Query Route2 Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 `nexus` 的“聚合查询/读时拼装”按路线2落地：详情类允许局部并发（受控线程池），列表类保持批量优先，不引入循环并发；先拿两个详情服务做试点且保持用户可见语义不变。

**Architecture:** 在 `nexus-app` 提供受控 `aggregationExecutor`（有界队列 + 明确 core/max/keepAlive + 关停策略 + 最小上下文复制），在详情聚合点（`ContentDetailQueryService`、`UserProfilePageQueryService`）用 `CompletableFuture` 并发独立片段并保持原有异常/降级语义（必要时解包 `CompletionException` 维持原异常类型）；列表/树形暂不做“循环 future”，树形的“批量 preview”单独另起计划。

**Tech Stack:** Java 17, Spring Boot 3.2, CompletableFuture, Spring ThreadPoolTaskExecutor, JUnit5, Mockito

---

## Chunk 1: 受控线程池（aggregationExecutor）

### Task 1: 提供受控聚合线程池 Bean

**Files:**
- Create: `nexus-app/src/main/java/cn/nexus/config/AggregationExecutorConfig.java`
- Create: `nexus-app/src/main/java/cn/nexus/config/AggregationExecutorProperties.java`
- Create: `nexus-app/src/test/java/cn/nexus/config/AggregationExecutorWiringTest.java`
- Modify (Optional): `nexus-app/src/main/resources/application.yml`

- [ ] **Step 1: 写一个“受控且可关停”的 executor Bean（参数写全）**

目标：提供一个命名为 `aggregationExecutor` 的 `java.util.concurrent.Executor`，线程名可识别，队列有界，线程数有上限，应用关闭时不会卡死或泄漏线程。

实现要点（保守默认值，后续可按压测调）：
- 使用 `ThreadPoolTaskExecutor`
- 设置 `threadNamePrefix`（例如 `agg-`）
- 设置 `corePoolSize / maxPoolSize / keepAliveSeconds / queueCapacity`（不要留默认值）
  - 建议默认：`core=4`，`max=8`，`keepAliveSeconds=60`，`queueCapacity=256`
- 拒绝策略先用 `CallerRunsPolicy`（队列满了就让当前线程跑，用“变慢”替代“直接失败”）
- 设置 shutdown 行为（必须写清）：
  - `waitForTasksToCompleteOnShutdown=true`
  - `awaitTerminationSeconds=10`（超时就放弃等待，避免停机卡住）
- 最小 ThreadLocal 风险控制（本 plan 选 A）：
  - A. 给 executor 加 `TaskDecorator`，只复制 `MDC`（traceId 等日志上下文）
  - 约束：并发 fragment 不得依赖除 MDC 以外的 ThreadLocal（登录态/租户等必须显式传参）

- [ ] **Step 2: 把线程池参数做成“可配置但不强依赖 yml”**

目标：即使不改 `application.yml` 也能工作（用代码默认值兜底）；需要调参时再在 yml 覆盖。

实现方式（写死，避免执行者卡住）：
- 新增 `AggregationExecutorProperties`（`@ConfigurationProperties(prefix="social.aggregation.executor")`）
- 在 `AggregationExecutorConfig` 里 `@EnableConfigurationProperties(AggregationExecutorProperties.class)`
- yml key（可选覆盖）：
  - `social.aggregation.executor.core-pool-size`
  - `social.aggregation.executor.max-pool-size`
  - `social.aggregation.executor.keep-alive-seconds`
  - `social.aggregation.executor.queue-capacity`
  - `social.aggregation.executor.await-termination-seconds`

- [ ] **Step 3: 验证“接线没断”（Spring 上下文能注入到这个 Bean）**

方式：新增 `AggregationExecutorWiringTest`（放在 `nexus-app`），用 `@SpringBootTest(classes = cn.nexus.Application.class)` 启动上下文，并断言：
- 能注入 `@Qualifier("aggregationExecutor") Executor`
- 注入结果不为 null

这一步的目的：避免“Bean 放在 nexus-app，但 nexus-trigger/nexus-domain 测试上下文里找不到”的隐患在最后才爆。

- [ ] **Step 4: 跑 nexus-app 测试（覆盖接线）**

Run: `mvn -pl nexus-app -am test`
Expected: `AggregationExecutorWiringTest` 通过，且进程能正常退出（不挂线程）。

---

## Chunk 2: 详情聚合试点（并发但不改语义）

### Task 2: 改造 ContentDetailQueryService（并发 author + likeCount）

**Files:**
- Modify: `nexus-trigger/src/main/java/cn/nexus/trigger/http/social/support/ContentDetailQueryService.java`
- Test: `nexus-trigger/src/test/java/cn/nexus/trigger/http/social/support/ContentDetailQueryServiceTest.java`

- [ ] **Step 1: 先写/补一个“失败语义不变”的单测（锁住异常行为）**

目标：保持“作者加载失败不会导致接口失败；likeCount 仍能返回默认值/已有值”的语义。

最小测试思路（示例）：
```java
// userBaseRepository 抛异常时，query 仍返回，且 likeCount 仍按默认/模拟值输出
```

- [ ] **Step 2: 在服务中注入受控 executor**

在 `ContentDetailQueryService` 增加依赖：
- `@Qualifier("aggregationExecutor") Executor aggregationExecutor`

并保持构造器注入（现有 test 需要同步调整构造参数）。

- [ ] **Step 3: 并发跑两个 fragment，但并发阶段只算结果，最后单线程写回 DTO**

在 `buildResponse` 中把：
- `loadAuthor(...)`
- `loadLikeCount(...)`

改为并发（推荐形态）：
- 并发阶段：`future` 只返回“结果值”（例如 `Optional<Author>` / `Long`），不要在 future 线程里直接改同一个 `response` 对象。
- 每个 `future` 自己处理该吞的异常（用 `handle/exceptionally` 返回默认值），避免 `join()` 把异常包装成 `CompletionException` 后把接口打挂。
- 最后合并：在一个线程里把结果写回 `response`。

关键点：
- 继续保持 author/likeCount 的默认值语义（不要把异常抛出去改变用户可见行为）
- 不使用默认公共线程池

- [ ] **Step 4: 跑 trigger 单测（带 -am）**

Run: `mvn -pl nexus-trigger -am test`
Expected: `ContentDetailQueryServiceTest` 通过。

- [ ] **Step 5: （可选）小步提交**

如果需要 commit，但遇到 git safe.directory 报错，先运行：
`git config --global --add safe.directory 'C:/Users/Administrator/Desktop/文档'`

---

### Task 3: 改造 UserProfilePageQueryService（并发 status/count/isFollow/risk）

**Files:**
- Modify: `nexus-domain/src/main/java/cn/nexus/domain/user/service/UserProfilePageQueryService.java`
- Test: `nexus-domain/src/test/java/cn/nexus/domain/user/service/UserProfilePageQueryServiceTest.java`

- [ ] **Step 1: 保留原有“门槛”顺序**

必须先做：
- 参数校验
- block 判定
- `userProfileRepository.get(targetUserId)`（profile 为真值门槛；不存在直接 NOT_FOUND）

这些不要并发化（否则会让控制流变复杂）。

- [ ] **Step 2: 注入受控 executor**

在 `UserProfilePageQueryService` 增加依赖：
- `@Qualifier("aggregationExecutor") Executor aggregationExecutor`

- [ ] **Step 3: 并发跑剩余片段（失败就失败，但异常类型不漂移）**

并发候选（视逻辑分支）：
- `userStatusRepository.getStatus(targetUserId)`
- `relationCachePort.getFollowingCount(targetUserId)`
- `relationCachePort.getFollowerCount(targetUserId)`
- `relationRepository.findRelation(viewerId, targetUserId, RELATION_FOLLOW)`（仅 viewer!=target）
- `riskService.userStatus(targetUserId)`

保持语义的关键点：
- 现在这些调用抛异常会让接口失败；并发后也应失败（不要偷偷吞异常改语义）。
- 注意：`join()` 可能抛 `CompletionException`，需要在汇总处“解包并抛出原始异常”，避免上层错误码映射/断言被悄悄改变。
- `isFollow` 仍只在 viewer!=target 时查询。

- [ ] **Step 4: 跑 domain 单测（带 -am）**

Run: `mvn -pl nexus-domain -am test`
Expected: `UserProfilePageQueryServiceTest` 全通过。

- [ ] **Step 5: （可选）小步提交**

---

## Chunk 3: 收尾与守门

### Task 4: 代码自检与文档对齐

**Files:**
- Reference: `docs/superpowers/specs/2026-03-19-aggregation-query-route2-agent-design.md`

- [ ] **Step 1: grep 自检（防止循环并发）**

在改动范围内自检是否出现“循环里开 future”的迹象（人工 review 为主）。

- [ ] **Step 2: 回归运行（可选）**

如果你能启动应用：跑一遍 `GET /api/v1/content/{postId}` 与 `GET /api/v1/user/profile/page` 的基本场景，确保返回字段与错误码语义没有漂移。

- [ ] **Step 3: 守门验证（最小集合）**

至少满足：
- `mvn -pl nexus-app -am test`（接线 + 线程池关停）
- `mvn -pl nexus-trigger -am test`
- `mvn -pl nexus-domain -am test`

备注：如果当前分支存在“与本次无关的历史失败用例”导致全量 `test` 卡住，可先临时只跑本次相关用例（例如 `-Dtest=XXXTest`，并配合 `-Dsurefire.failIfNoSpecifiedTests=false`），等主干修好历史失败后再补跑全量。

---

## Chunk 4: 树形聚合（CommentQueryService）

目标：把“每个 root 单独查回复预览（N+1）”改成“批量拿预览”，不靠循环 `CompletableFuture` 掩盖设计问题。

已落地实现点（供执行者核对）：
- 新增批量预览接口：`ICommentRepository.batchListReplyPreviewIds(rootIds, limit, viewerId)`
- 基础设施实现：`CommentRepository#batchListReplyPreviewIds`
- DAO + SQL：`ICommentDao.selectReplyPreviewIdsByRootIds` + `CommentMapper.xml`（使用 `ROW_NUMBER()` 做 per-root topN）
- QueryService 组装：`CommentQueryService` 用批量预览回填 `repliesPreview`，并保留 `visibleToViewer` 过滤语义
- 单测：`CommentQueryServiceTest` 锁住“走批量预览，不再循环 pageReplyCommentIds”
