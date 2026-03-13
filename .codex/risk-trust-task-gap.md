# 风控与信任服务：任务对照（文档 vs 代码）

日期：2026-01-29  
执行者：Codex（Linus-mode）

## 输入

- 文档：`风控与信任服务-实现方案.md`（18.2「任务拆解」1-10）
- 代码：`project/nexus/*`（当前仓库状态）

## 任务对照表

| ID | 文档任务（18.2） | 状态 | 代码证据（关键文件） | 备注/缺口 |
|---:|---|---|---|---|
| 1 | 定义统一风控决策契约（`/api/v1/risk/decision`） | DONE | `project/nexus/nexus-api/src/main/java/cn/nexus/api/social/IRiskApi.java`；`project/nexus/nexus-api/src/main/java/cn/nexus/api/social/risk/dto/RiskDecisionRequestDTO.java`；`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/RiskController.java` | - |
| 2 | 落地核心表结构（`decision_log/case/punishment/rule_version/prompt_version`） | DONE | `project/nexus/docs/social_schema.sql` | - |
| 3 | 存储层（MyBatis Mapper + Repository） | DONE | `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/RiskDecisionLogRepository.java`；`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/RiskCaseRepository.java`；`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/RiskPunishmentRepository.java`；`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/RiskRuleVersionRepository.java`；`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/RiskFeedbackRepository.java`；`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/RiskPromptVersionRepository.java`；`project/nexus/nexus-infrastructure/src/main/resources/mapper/social/RiskPromptVersionMapper.xml` | - |
| 4 | 在线决策入口（不阻塞 LLM） | DONE | `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/RiskService.java`；`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/RiskController.java` | 在线链路落 `risk_decision_log`，异步通过 MQ 投递；不在在线链路直接调用 LLM |
| 5 | RabbitMQ 拓扑与事件契约（`LlmScanRequested/ScanCompleted`） | DONE | `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/config/RiskMqConfig.java`；`project/nexus/nexus-types/src/main/java/cn/nexus/types/event/risk/LlmScanRequestedEvent.java`；`project/nexus/nexus-types/src/main/java/cn/nexus/types/event/risk/ImageScanRequestedEvent.java`；`project/nexus/nexus-types/src/main/java/cn/nexus/types/event/risk/ReviewCaseCreatedEvent.java`；`project/nexus/nexus-types/src/main/java/cn/nexus/types/event/risk/ScanCompletedEvent.java`；`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/RiskLlmScanConsumer.java`；`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/RiskImageScanConsumer.java` | `ScanCompleted` 作为旁路事件 best-effort 发布（可选绑定队列消费） |
| 6 | risk-worker 多模态 LLM 扫描流水线 | DONE | `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/RiskLlmScanConsumer.java`；`project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/RiskImageScanConsumer.java`；`project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/DashscopeRiskLlmPort.java`；`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/risk/RiskAsyncService.java` | 去重缓存 + inflight 锁 + 分钟预算器 + 失败降级均已落地 |
| 7 | 内容发布接入风控（带图默认隔离 + 投递扫描） | DONE | `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/ContentService.java`；`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/RiskService.java` | `publish` 走统一决策；命中 REVIEW 会进入 `PENDING_REVIEW` |
| 8 | 扫描结果回写业务处置（PASS fanout；BLOCK 下架/处罚；REVIEW 工单） | DONE | `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/risk/RiskAsyncService.java`；`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/ContentService.java`；`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/InteractionService.java`；`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/RiskService.java` | 自动处罚与抽检能力默认关闭/0%，避免改变用户可见行为 |
| 9 | 人审/运营最小后台（cases/punishments/decisions）+ 抽检策略 | DONE | `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/http/social/RiskAdminController.java`；`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/RiskService.java`；`project/nexus/nexus-app/src/main/resources/application-dev.yml` | - |
| 10 | 上线开关与验证（Shadow/Canary + 指标 + 压测/验收用例） | PARTIAL | `project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/RiskService.java`（canary 逻辑）；`project/nexus/nexus-domain/src/main/java/cn/nexus/domain/social/service/risk/RiskAsyncService.java`（autoPunish 开关）；`project/nexus/nexus-app/src/main/resources/application-dev.yml`（risk.llm.* / risk.sample.* / risk.autoPunish.*） | 已补齐部分开关（抽检/自动处罚）；仍缺：指标埋点与更完整的验收/压测用例 |

## 已完成（Done）

- 任务 1 / 2 / 3 / 4 / 5 / 6 / 7 / 8 / 9

## 未完成/部分完成（Gap）

- 任务 10：指标埋点与更完整的上线开关矩阵缺失
