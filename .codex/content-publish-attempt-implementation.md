# 内容发布 Attempt 化改造实现方案（A：失败不影响当前可见版本）

> 目标：在“风控不通过 / 转码未完成”等失败或处理中场景下，不修改 `content_post` 当前已发布内容的可见性；同时保留完整的过程审计与后续异步完成发布的能力。

## 1. 背景与现状问题

当前 `ContentService.publish(...)`（`project/nexus/nexus-domain/.../ContentService.java`）存在以下关键行为：

- 无论风控失败还是转码未完成，都会调用 `upsertPost(...)` 把 `content_post.status` 更新为 `REJECTED(3)` 或 `PENDING_REVIEW(1)`。
- 同时在三条分支（拒绝/处理中/成功）里都会写 `content_history` 快照并使用同一套 `version_num` 递增。

这会导致：

- 对已发布内容进行再次 publish（覆盖式发布）时，只要风控失败或转码未完成，就可能把原本 `PUBLISHED(2)` 的内容变为“不可见/不可展示”。
- `content_history` 目前混合承载了“可见版本快照”和“失败尝试快照”，语义不清晰；若未来希望“历史版本只展示成功可见版本”，会变得难以演进。

## 2. 目标与约束

### 2.1 业务目标（A）
- 风控失败：不影响当前已发布版本的可见性（不修改 `content_post`）。
- 转码未完成：不影响当前已发布版本的可见性（不修改 `content_post`）。
- 仍需可追溯：记录每次发布尝试的输入快照与失败原因/转码任务。
- 支持异步：转码完成后在一定时间内仍能推进到“成功发布”并更新可见版本。

### 2.2 技术约束
- DDD 分层：`domain` 不直接依赖基础设施实现；新增表的访问通过仓储接口与基础设施实现。
- 并发与幂等：同一 `postId` 的发布尝试需要分布式互斥/数据库行锁/乐观锁等手段避免并发漂移。

## 3. 总体方案概述

引入“发布尝试（Attempt）”的过程表 `content_publish_attempt`，把一次发布请求的过程状态（风控、转码、错误原因、异步任务等）从“可见版本链”中拆出来。

- `content_post`：只维护“当前可见态/当前版本号/当前展示内容”。仅在“成功发布”时才更新。
- `content_history`：只记录“成功可见版本”的全量快照（Revision），并建议增强为 `(post_id, version_num)` 唯一。
- `content_publish_attempt`：记录每次 publish 请求（包括失败/处理中/成功）的过程快照与状态机推进，用于审计、排障、异步回调幂等。

> 核心原则：失败/处理中只更新 Attempt，不更新 Post，也不写 History 的版本号。

## 4. 数据模型变更

### 4.1 新表：`content_publish_attempt`

建议表结构（MySQL）：

- 主键与关联
  - `attempt_id` BIGINT NOT NULL：尝试ID（全局唯一）
  - `post_id` BIGINT NOT NULL：目标内容ID
  - `user_id` BIGINT NOT NULL：发起用户

- 幂等与关联
  - `idempotent_token` VARCHAR(128) NOT NULL：幂等键（建议由 clientRequestId 或 userId+postId+payloadHash 生成）
  - `transcode_job_id` VARCHAR(128) DEFAULT NULL：外部转码任务ID（用于回调/轮询定位）

- 过程状态
  - `attempt_status` TINYINT NOT NULL：尝试状态（见 5. 状态机）
  - `risk_status` TINYINT DEFAULT NULL：风控结果（0未评估/1通过/2拒绝）
  - `transcode_status` TINYINT DEFAULT NULL：转码状态（0未开始/1处理中/2完成/3失败）

- 输入快照（审计）
  - `snapshot_content` TEXT：文本快照
  - `snapshot_media` JSON：媒体快照（若当前代码用 String 承载 JSON，可先用 TEXT 再逐步演进）
  - `location_info` JSON/TEXT：位置快照（可选）
  - `visibility` TINYINT：可见性（可选）

- 结果与错误
  - `published_version_num` INT DEFAULT NULL：若最终成功发布，记录对应的可见版本号
  - `error_code` VARCHAR(64) DEFAULT NULL
  - `error_message` TEXT DEFAULT NULL

- 时间字段
  - `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP
  - `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP

索引建议：
- `PRIMARY KEY (attempt_id)`
- `UNIQUE KEY uk_idempotent_token (idempotent_token)`
- `INDEX idx_post_time (post_id, create_time)`
- `INDEX idx_user_time (user_id, create_time)`
- `INDEX idx_transcode_job (transcode_job_id)`

### 4.2 强化 `content_history` 语义

建议把 `content_history` 明确为“可见版本快照（Revision）”，只在成功发布/回滚等产生可见版本时写入。

建议增加：
- `UNIQUE KEY uk_post_ver (post_id, version_num)`

注意：若线上已有历史数据，需先排查是否存在重复 `(post_id, version_num)`；当前实现按版本递增写入，通常不会重复，但仍建议上线前做一次一致性检查。

### 4.3 `content_post` 不变，但语义更收敛

`content_post.status/version_num` 只代表“当前可见内容的状态与版本”。

- 风控失败：不再把已发布内容改成 REJECTED。
- 转码未完成：不再把已发布内容改成 PENDING_REVIEW。

## 5. Attempt 状态机定义

建议 `attempt_status`：

- `0 CREATED`：已创建尝试记录，尚未完成风控/转码决策
- `1 RISK_REJECTED`：风控拒绝（终态）
- `2 TRANSCODING`：转码中（非终态，可由异步回调推进）
- `3 READY_TO_PUBLISH`：风控通过且转码完成，等待提交可见版本（可选中间态）
- `4 PUBLISHED`：已成功提交为可见版本（终态）
- `5 FAILED`：系统失败（终态，比如转码服务异常、DB 冲突重试后失败）
- `6 CANCELED`：用户取消（可选）

状态推进规则（建议数据库更新时带 expected_status 防止并发重复推进）：
- `CREATED -> RISK_REJECTED`
- `CREATED -> TRANSCODING`
- `TRANSCODING -> READY_TO_PUBLISH -> PUBLISHED`
- 任意非终态 -> `FAILED`

## 6. 流程设计

### 6.1 同步 publish（入口）

入口行为建议调整为：

1) 仍保持互斥与 ACL：
   - 分布式锁 `lock:content:post:<postId>`
   - `findPostForUpdate(postId)` 行锁
   - 校验 owner（如果 post 已存在）

2) 创建 Attempt（幂等）：
   - 计算 `idempotent_token`（优先使用客户端传入的 `clientRequestId`；无则服务端 hash）
   - 若 token 已存在且 attempt 仍在进行中，直接返回该 attempt 的状态（避免重复点击造成多次尝试）

3) 风控：
   - 不通过：更新 attempt 为 `RISK_REJECTED`，记录原因，直接返回失败。
   - 通过：继续。

4) 转码：
   - 未就绪：更新 attempt 为 `TRANSCODING` 并记录 `transcode_job_id`，返回 `PROCESSING`（返回体包含 `attemptId` 以便查询进度）。
   - 已就绪：进入“提交可见版本”。

5) 提交可见版本（只有成功发布才做）：
   - 读取当前 `content_post.version_num`，计算 `newVersion = current + 1`
   - 写 `content_history(post_id, newVersion, snapshot_*)`
   - `upsertPost(... status=PUBLISHED, versionNum=newVersion ...)`
   - 更新 attempt 为 `PUBLISHED` 并记录 `published_version_num=newVersion`
   - 调用 `contentDispatchPort.onPublished(...)`

> 关键：风控失败/转码未就绪不会触碰 `content_post`，因此不会影响当前可见版本。

### 6.2 异步推进（转码完成后）

新增一条“推进 attempt 发布”的消费/回调流程（可由 MQ、定时轮询或第三方回调触发）：

- 输入：`attempt_id` 或 `transcode_job_id`
- 行为：
  1) 查询 attempt，确认状态为 `TRANSCODING`
  2) 校验转码结果为成功（失败则 attempt -> FAILED，并记录错误）
  3) 获取 `postId` 分布式锁 + DB 行锁
  4) 执行“提交可见版本”步骤（写 history + 更新 post + dispatch）
  5) attempt -> PUBLISHED

幂等：
- 更新 attempt 状态时携带 `expected_status=TRANSCODING`，避免重复回调导致重复发布。

### 6.3 对用户侧历史查询/回滚的影响

- `ContentService.history(...)`：建议仅返回 `content_history`（可见版本链），语义更稳定。
- 回滚：仍以 `content_history(postId, versionNum)` 为来源，保证版本号唯一后可直接精确定位。

## 7. 并发、幂等与一致性策略

- post 维度互斥：Redisson `RLock`，避免跨实例并发发布同一 `postId`。
- DB 行锁：`SELECT ... FOR UPDATE` 锁定主表行，保证版本号读取与更新在同一事务中串行。
- 乐观锁：`content_post` 仍保留 `WHERE version_num = expectedVersion` 更新条件，作为最后一道防线。
- Attempt 幂等：用 `idempotent_token` 做唯一约束；冲突时读取并返回现有 attempt。
- Attempt 状态推进幂等：更新时带 expected_status（类似 schedule 的 updateScheduleStatus 设计）。

## 8. 接口契约建议（API 层）

为支持“转码未完成返回 attemptId 供查询”，建议扩展发布返回体：

- 成功发布：返回 `postId + status=PUBLISHED`（可选附带 `versionNum`）
- 风控拒绝：返回 `status=REJECTED + attemptId`
- 转码处理中：返回 `status=PROCESSING + attemptId`

并新增查询接口（或复用现有审计风格）：
- GET `/api/v1/content/publish/attempt/{attemptId}?userId=`：返回 attempt 状态与错误信息（ACL：仅发起人可见）

## 9. 代码改动点（按分层）

### 9.1 domain
- 新增模型实体/值对象：`ContentPublishAttemptEntity`、`AttemptStatus`（枚举或常量）
- 新增仓储接口：`IContentPublishAttemptRepository`（或并入现有 `IContentRepository`，但推荐拆分保持职责单一）
- 调整 `ContentService.publish(...)`：
  - 失败/处理中不再调用 `upsertPost`/`saveHistory`
  - 创建/更新 attempt
  - 仅成功时写 `content_history + content_post`

### 9.2 infrastructure
- 新增 MyBatis DAO + Mapper.xml：`IContentPublishAttemptDao` + `ContentPublishAttemptMapper.xml`
- 新增仓储实现：`ContentPublishAttemptRepository`
- 若转码为异步：
  - 新增消费者/回调处理器，调用“推进 attempt 发布”的 domain 方法

### 9.3 docs
- `project/nexus/docs/social_content_tables.sql` 增加新表 DDL 与 `content_history` 唯一约束建议。

## 10. 迁移与上线步骤

建议分两阶段上线，降低风险：

### 阶段 1：引入 Attempt 表 + publish 入口改造（不引入真实异步）
- 上线 DDL：创建 `content_publish_attempt`，并（可选）为 `content_history` 增加唯一约束。
- 改造 `publish`：
  - 风控失败/转码未就绪只写 attempt
  - 成功才写 history + post
- 观察：
  - 确认已发布内容不会因失败发布而下架
  - 记录 attempt 生成量与状态分布

### 阶段 2：接入真实转码异步推进
- 接入转码 jobId
- 实现回调/轮询消费者推进 `TRANSCODING -> PUBLISHED`
- 增加重试、退避、告警（可复用 schedule 的策略）

## 11. 验证与测试建议

- 单元测试：
  - 已发布 post + 风控失败：`content_post.status` 不变；attempt=RISK_REJECTED
  - 已发布 post + 转码未就绪：`content_post.status` 不变；attempt=TRANSCODING
  - 成功发布：history 写入 1 条且版本号递增；post 更新为 PUBLISHED；attempt= PUBLISHED

- 集成测试：
  - 并发两次 publish 同一 postId：只允许一个成功推进版本号；另一个应返回既有 attempt 或版本冲突可重试。

## 12. 风险与回滚策略

- 风险：attempt 表引入后，publish 返回体与调用方需要适配（尤其需要处理 PROCESSING + attemptId）。
- 回滚：
  - 保留旧字段与旧端口实现，必要时可通过配置开关回退到旧流程（但不建议长期双流程）。

---

文档位置：`.codex/content-publish-attempt-implementation.md`

