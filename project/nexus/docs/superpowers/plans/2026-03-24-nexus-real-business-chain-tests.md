# Nexus 真实业务链路测试 Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 `project/nexus` 的所有业务场景补上连接 WSL Docker 真实服务的链路测试，排除 Kafka。

**Architecture:** 以 `nexus-app` 作为真实链路测试承载模块，新增统一 HTTP/业务测试支撑，按业务域拆分测试文件。每个场景只覆盖一条完整业务链路，验证入口、持久化、副作用、最终查询结果四个层面。

**Tech Stack:** Spring Boot Test, JUnit 5, AssertJ, Awaitility, MySQL, Redis, RabbitMQ, Cassandra, Elasticsearch, MinIO, Gorse, etcd

---

## Chunk 1: 场景矩阵与共享测试基座

### Task 1: 固化业务场景矩阵

**Files:**
- Modify: `docs/superpowers/specs/2026-03-24-nexus-real-business-chain-tests-design.md`
- Modify: `docs/superpowers/plans/2026-03-24-nexus-real-business-chain-tests.md`

- [ ] **Step 1: 盘点当前场景与缺口**
- [ ] **Step 2: 把已有覆盖与缺失覆盖补入设计文档**
- [ ] **Step 3: 运行 `rg` 检查新增测试文件能否映射回矩阵**
- [ ] **Step 4: 继续下一任务**

### Task 2: 抽取真实业务测试支撑

**Files:**
- Create: `nexus-app/src/test/java/cn/nexus/integration/support/RealBusinessIntegrationTestSupport.java`
- Create: `nexus-app/src/test/java/cn/nexus/integration/support/RealHttpIntegrationTestSupport.java`
- Modify: `nexus-app/src/test/java/cn/nexus/integration/RealMiddlewareIntegrationTestSupport.java`

- [ ] **Step 1: 先写失败测试或编译失败的测试骨架**
- [ ] **Step 2: 运行最小测试，确认缺少支撑类而失败**
- [ ] **Step 3: 实现最小 HTTP / 业务支撑**
- [ ] **Step 4: 运行支撑相关测试，确认通过**

## Chunk 2: 认证与用户

### Task 3: 认证 HTTP 主链路

**Files:**
- Create: `nexus-app/src/test/java/cn/nexus/integration/auth/AuthHttpRealIntegrationTest.java`
- Reference: `nexus-trigger/src/main/java/cn/nexus/trigger/http/auth/AuthController.java`

- [ ] **Step 1: 写失败测试**
  - 注册
  - 密码登录
  - 短信发送
  - `me`
  - 登出
- [ ] **Step 2: 运行单文件测试，确认红灯**
- [ ] **Step 3: 只补必要测试支撑，不改业务代码**
- [ ] **Step 4: 运行单文件测试，确认绿灯**

### Task 4: 用户资料与隐私主链路

**Files:**
- Create: `nexus-app/src/test/java/cn/nexus/integration/user/UserProfileHttpRealIntegrationTest.java`
- Create: `nexus-app/src/test/java/cn/nexus/integration/user/UserPrivacyHttpRealIntegrationTest.java`
- Reference: `nexus-trigger/src/main/java/cn/nexus/trigger/http/user/*.java`

- [ ] **Step 1: 写失败测试**
  - 我的资料查询/修改
  - 公开资料查询
  - 隐私配置查询/修改
  - 用户主页聚合
  - 内部用户 upsert
- [ ] **Step 2: 运行测试，确认红灯**
- [ ] **Step 3: 复用支撑类补齐登录、造数、断言**
- [ ] **Step 4: 再跑测试，确认绿灯**

## Chunk 3: 内容与评论

### Task 5: 内容发布主链路

**Files:**
- Create: `nexus-app/src/test/java/cn/nexus/integration/content/ContentPublishHttpRealIntegrationTest.java`
- Create: `nexus-app/src/test/java/cn/nexus/integration/content/ContentScheduleRealIntegrationTest.java`
- Reference: `nexus-trigger/src/main/java/cn/nexus/trigger/http/social/ContentController.java`

- [ ] **Step 1: 写失败测试**
  - 上传会话
  - 草稿保存/同步
  - 发布
  - 发布结果查询
  - 历史版本
  - 定时发布创建/更新/取消/查询
- [ ] **Step 2: 运行测试，确认红灯**
- [ ] **Step 3: 补测试支撑与环境清理**
- [ ] **Step 4: 运行测试，确认绿灯**

### Task 6: 评论与评论查询主链路

**Files:**
- Create: `nexus-app/src/test/java/cn/nexus/integration/comment/CommentHttpRealIntegrationTest.java`
- Create: `nexus-app/src/test/java/cn/nexus/integration/comment/CommentConsumerRealIntegrationTest.java`

- [ ] **Step 1: 写失败测试**
  - 发表评论
  - 评论列表/回复列表/热评
  - 删除评论
  - 置顶评论
  - 评论创建/点赞变更/回复计数 consumer
- [ ] **Step 2: 运行测试，确认红灯**
- [ ] **Step 3: 实现最小测试支撑**
- [ ] **Step 4: 运行测试，确认绿灯**

## Chunk 4: 互动、关系、Feed、搜索

### Task 7: 点赞/通知/钱包/投票主链路

**Files:**
- Create: `nexus-app/src/test/java/cn/nexus/integration/interaction/InteractionHttpRealIntegrationTest.java`
- Create: `nexus-app/src/test/java/cn/nexus/integration/interaction/InteractionConsumerRealIntegrationTest.java`

- [ ] **Step 1: 写失败测试**
  - 点赞与取消点赞
  - 点赞状态与 liker 列表
  - 通知列表/已读/全部已读
  - 钱包余额/打赏
  - 投票创建/投票
  - 互动相关 consumer
- [ ] **Step 2: 跑红灯**
- [ ] **Step 3: 补最小支撑**
- [ ] **Step 4: 跑绿灯**

### Task 8: 关系链路与补偿链路

**Files:**
- Create: `nexus-app/src/test/java/cn/nexus/integration/relation/RelationHttpRealIntegrationTest.java`
- Create: `nexus-app/src/test/java/cn/nexus/integration/relation/RelationAsyncRealIntegrationTest.java`

- [ ] **Step 1: 写失败测试**
  - 关注 / 取关 / 拉黑
  - following / followers / state batch
  - relation listener
  - relation outbox publish / retry job
- [ ] **Step 2: 跑红灯**
- [ ] **Step 3: 补最小支撑**
- [ ] **Step 4: 跑绿灯**

### Task 9: Feed 与搜索主链路

**Files:**
- Create: `nexus-app/src/test/java/cn/nexus/integration/feed/FeedHttpRealIntegrationTest.java`
- Create: `nexus-app/src/test/java/cn/nexus/integration/feed/FeedConsumerRealIntegrationTest.java`
- Create: `nexus-app/src/test/java/cn/nexus/integration/search/SearchHttpRealIntegrationTest.java`

- [ ] **Step 1: 写失败测试**
  - timeline / profile feed
  - fanout dispatcher / task
  - recommend item upsert / delete / feedback
  - search / suggest
- [ ] **Step 2: 跑红灯**
- [ ] **Step 3: 补最小支撑**
- [ ] **Step 4: 跑绿灯**

## Chunk 5: 风控、社群、KV、Job

### Task 10: 风控与管理后台链路

**Files:**
- Create: `nexus-app/src/test/java/cn/nexus/integration/risk/RiskHttpRealIntegrationTest.java`
- Create: `nexus-app/src/test/java/cn/nexus/integration/risk/RiskConsumerRealIntegrationTest.java`

- [ ] **Step 1: 写失败测试**
  - 文本 / 图片扫描
  - 风险决策 / 申诉
  - 用户风险状态
  - admin 规则 / 提示词 / case / punishment / decision
  - 扫描 consumer / DLQ
- [ ] **Step 2: 跑红灯**
- [ ] **Step 3: 补最小支撑**
- [ ] **Step 4: 跑绿灯**

### Task 11: 社群、KV、ID、健康

**Files:**
- Create: `nexus-app/src/test/java/cn/nexus/integration/community/CommunityHttpRealIntegrationTest.java`
- Create: `nexus-app/src/test/java/cn/nexus/integration/kv/KvHttpRealIntegrationTest.java`
- Create: `nexus-app/src/test/java/cn/nexus/integration/system/SystemHttpRealIntegrationTest.java`

- [ ] **Step 1: 写失败测试**
  - group join / kick / role / channel config
  - note content / comment content HTTP 接口
  - id segment / snowflake
  - health
- [ ] **Step 2: 跑红灯**
- [ ] **Step 3: 补最小支撑**
- [ ] **Step 4: 跑绿灯**

### Task 12: 各类补偿 Job 链路

**Files:**
- Create: `nexus-app/src/test/java/cn/nexus/integration/job/ReliableJobRealIntegrationTest.java`

- [ ] **Step 1: 写失败测试**
  - content event outbox retry
  - reliable mq outbox retry
  - reliable mq replay
  - user event outbox retry
  - content/comment soft delete cleanup
  - reaction sync worker
- [ ] **Step 2: 跑红灯**
- [ ] **Step 3: 补最小支撑**
- [ ] **Step 4: 跑绿灯**

## Chunk 6: 全量验证

### Task 13: 分组验证与总验收

**Files:**
- Modify: `docs/superpowers/specs/2026-03-24-nexus-real-business-chain-tests-design.md`

- [ ] **Step 1: 运行域级测试命令，记录通过情况**
- [ ] **Step 2: 运行 `nexus-app` 真实链路测试全集**
- [ ] **Step 3: 将每个业务域映射回场景矩阵**
- [ ] **Step 4: 明确剩余风险与未覆盖项**
