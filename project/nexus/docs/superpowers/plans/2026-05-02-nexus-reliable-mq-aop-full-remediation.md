# Nexus Reliable MQ AOP Full Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Standardize Nexus RabbitMQ producer, consumer, and DLQ chains on the existing reliable MQ services with a thin annotation/AOP shell and complete chain inventory.

**Architecture:** Keep durability state in the existing reliable MQ tables and services. Add annotations and Spring AOP in `nexus-infrastructure`, then migrate producers, auto-ack consumers, and DLQ consumers in batches. Manual-ack consumers stay explicit and are verified by tests.

**Tech Stack:** Java 17, Spring Boot 3.2, Spring AMQP, Spring AOP, MyBatis, JUnit 5, Mockito, Maven.

---

## File Structure

Create:

- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/mq/reliable/annotation/ReliableMqPublish.java`
- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/mq/reliable/annotation/ReliableMqConsume.java`
- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/mq/reliable/annotation/ReliableMqDlq.java`
- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/mq/reliable/exception/ReliableMqPermanentFailureException.java`
- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/mq/reliable/aop/ReliableMqExpressionEvaluator.java`
- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/mq/reliable/aop/ReliableMqPublishAspect.java`
- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/mq/reliable/aop/ReliableMqConsumeAspect.java`
- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/mq/reliable/aop/ReliableMqDlqAspect.java`
- `project/nexus/nexus-infrastructure/src/test/java/cn/nexus/infrastructure/mq/reliable/aop/ReliableMqPublishAspectTest.java`
- `project/nexus/nexus-infrastructure/src/test/java/cn/nexus/infrastructure/mq/reliable/aop/ReliableMqConsumeAspectTest.java`
- `project/nexus/nexus-infrastructure/src/test/java/cn/nexus/infrastructure/mq/reliable/aop/ReliableMqDlqAspectTest.java`
- `project/nexus/nexus-infrastructure/src/test/java/cn/nexus/infrastructure/mq/reliable/aop/ReliableMqAopWiringTest.java`
- `project/nexus/nexus-app/src/test/java/cn/nexus/contract/mq/ReliableMqArchitectureContractTest.java`
- `project/nexus/docs/operations/reliable-mq-chain-inventory.md`

Modify:

- `project/nexus/nexus-infrastructure/pom.xml`: add `spring-boot-starter-aop`.
- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/mq/reliable/ReliableMqMessageSupport.java`: reuse serialization and event id helpers from aspects instead of duplicating JSON/event-id parsing.
- Producer ports already using `ReliableMqOutboxService`: `CommentEventPort`, `InteractionNotifyEventPort`, `RecommendFeedbackEventPort`, `RiskTaskPort`, `ContentScheduleProducer`, `ContentCacheEvictPort`.
- Auto-ack consumers already using `ReliableMqConsumerRecordService`: feed, recommendation, risk, summary, schedule, interaction notify consumers. Search CDC must stay inventory-driven until its listener container retry/DLQ behavior is proven equivalent to the reliable container.
- DLQ consumers already using `ReliableMqDlqRecorder`: `FeedRecommendItemDeleteDlqConsumer`, `RelationBlockDlqConsumer`, `InteractionNotifyDlqConsumer`, `RiskLlmScanDlqConsumer`, `FeedFanoutTaskDlqConsumer`, `RiskImageScanDlqConsumer`, `FeedRecommendItemUpsertDlqConsumer`, `RelationFollowDlqConsumer`, `FeedRecommendFeedbackADlqConsumer`, `FeedFanoutDispatcherDlqConsumer`, `FeedRecommendFeedbackDlqConsumer`, `ContentScheduleDLQConsumer`, `PostSummaryGenerateDlqConsumer`.
- Remaining raw RabbitMQ publishers: `ContentDispatchPort`, `ReactionNotifyMqPort`, `ReactionRecommendFeedbackMqPort`, `RiskProducer`, downstream publishes inside `FeedFanoutDispatcherConsumer`, `RiskImageScanConsumer`, `RiskLlmScanConsumer`, `SearchIndexCdcRawPublisher`, `RelationEventPort`.
- Manual-ack consumer: `RelationCounterProjectConsumer` test coverage only, no AOP conversion.

Do not modify:

- `ContentEventOutboxPort` and `UserEventOutboxPort` table semantics. Their internal drain publishes remain allowlisted domain outbox internals.
- Kafka counter producer and Kafka consumers.

## Non-Negotiable Implementation Constraints

- Do not change database schemas in this remediation.
- Do not migrate `ContentEventOutboxPort` or `UserEventOutboxPort` to `ReliableMqOutboxService`.
- Do not add after-commit opportunistic publishing to the new publish aspect.
- Do not convert manual-ack consumers to `@ReliableMqConsume`.
- Do not introduce new raw RabbitMQ publishes outside the final architecture allowlist.
- Do not leave an architecture test disabled at the end of Task 10.
- Do not use broad `git add project/nexus` or whole-module staging in any task.
- Do not commit a red test state. Task 2 audit-mode architecture test can be committed only when it is enabled and passing by asserting the current finding count.
- Every step with an expected failure is a RED verification step only. The same task must turn that command green before its commit step.

## Chain Inventory Baseline

Before migration, create `project/nexus/docs/operations/reliable-mq-chain-inventory.md` with one row per RabbitMQ chain. The inventory must include producer class/method, exchange, routing key, payload type, event id source, consumer class/method, listener container factory, idempotency mechanism, DLQ queue, replay route, chain classification, and raw publish status.

Initial chains to inventory:

- `CommentEventPort.publish` -> `CommentCreatedConsumer.onMessage`
- `InteractionNotifyEventPort.publish` -> `InteractionNotifyConsumer.onMessage`
- `RecommendFeedbackEventPort.publish` -> `FeedRecommendFeedbackConsumer.onMessage`
- `RiskTaskPort` and `RiskProducer` -> risk image/LLM/review queues
- `ContentScheduleProducer` -> `ContentScheduleConsumer.onMessage`
- `ContentCacheEvictPort` -> `ContentCacheEvictConsumer.onMessage`
- `ContentDispatchPort` and `ContentEventOutboxPort` -> feed, summary, and content event consumers
- `FeedFanoutDispatcherConsumer` -> `FeedFanoutTaskConsumer`
- `RiskImageScanConsumer` and `RiskLlmScanConsumer` -> scan completed route
- `SearchIndexCdcRawPublisher` -> `SearchIndexCdcConsumer`
- `ReactionNotifyMqPort` -> interaction notify route
- `ReactionRecommendFeedbackMqPort` -> recommendation feedback route
- `RelationEventPort` -> `RelationCounterProjectConsumer`
- All DLQ routes declared in `FeedFanoutConfig`, `FeedRecommendFeedbackMqConfig`, `FeedRecommendFeedbackAMqConfig`, `FeedRecommendItemMqConfig`, `InteractionNotifyMqConfig`, `PostSummaryMqConfig`, `RiskMqConfig`, `RelationMqConfig`, and `ContentScheduleDelayConfig`

---

### Task 1: Reliable MQ Annotation and Aspect Foundation

**Files:**
- Create: `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/mq/reliable/annotation/ReliableMqPublish.java`
- Create: `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/mq/reliable/annotation/ReliableMqConsume.java`
- Create: `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/mq/reliable/annotation/ReliableMqDlq.java`
- Create: `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/mq/reliable/exception/ReliableMqPermanentFailureException.java`
- Create: `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/mq/reliable/aop/ReliableMqExpressionEvaluator.java`
- Create: `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/mq/reliable/aop/ReliableMqPublishAspect.java`
- Create: `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/mq/reliable/aop/ReliableMqConsumeAspect.java`
- Create: `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/mq/reliable/aop/ReliableMqDlqAspect.java`
- Modify: `project/nexus/nexus-infrastructure/pom.xml`
- Test: `project/nexus/nexus-infrastructure/src/test/java/cn/nexus/infrastructure/mq/reliable/aop/ReliableMqPublishAspectTest.java`
- Test: `project/nexus/nexus-infrastructure/src/test/java/cn/nexus/infrastructure/mq/reliable/aop/ReliableMqConsumeAspectTest.java`
- Test: `project/nexus/nexus-infrastructure/src/test/java/cn/nexus/infrastructure/mq/reliable/aop/ReliableMqDlqAspectTest.java`
- Test: `project/nexus/nexus-infrastructure/src/test/java/cn/nexus/infrastructure/mq/reliable/aop/ReliableMqAopWiringTest.java`

- [ ] **Step 1: Write failing publish aspect tests**

Create `ReliableMqPublishAspectTest` with tests that assert:

- A public method annotated with `@ReliableMqPublish(exchange="social.interaction", routingKey="comment.created", eventId="#event.eventId", payload="#event")` causes `ReliableMqOutboxService.save("evt-1", "social.interaction", "comment.created", event)` exactly once.
- Blank event id throws `IllegalArgumentException`.
- A method body that throws does not call `save`.

Run:

`mvn -pl nexus-infrastructure -Dtest=ReliableMqPublishAspectTest test`

Expected: compilation fails because annotation/aspect classes do not exist.

Do not commit at this point.

- [ ] **Step 1.5: Write failing AOP wiring tests**

Create `ReliableMqAopWiringTest` with a minimal Spring test configuration. The tests must assert:

- A Spring bean public method annotated with `@ReliableMqPublish` is proxied and triggers `ReliableMqOutboxService.save(...)`.
- A direct self-invocation method that calls another annotated method in the same class does not count as a reliable publish entry point and is rejected by an architecture assertion.
- A method annotated with both `@Transactional` and `@ReliableMqPublish` calls `ReliableMqOutboxService.save(...)` while `TransactionSynchronizationManager.isActualTransactionActive()` is true.

Run:

`mvn -pl nexus-infrastructure -Dtest=ReliableMqAopWiringTest test`

Expected: compilation fails because annotation/aspect classes do not exist.

Do not commit at this point.

- [ ] **Step 2: Write failing consume aspect tests**

Create `ReliableMqConsumeAspectTest` with tests that assert:

- `StartResult.DUPLICATE_DONE` short-circuits and does not invoke the business method.
- `StartResult.STARTED` invokes the business method and then calls `markDone`.
- Business failure calls `markFail` and rethrows the original failure.
- `ReliableMqPermanentFailureException` is rethrown and not swallowed.

Run:

`mvn -pl nexus-infrastructure -Dtest=ReliableMqConsumeAspectTest test`

Expected: compilation fails because annotation/aspect classes do not exist.

Do not commit at this point.

- [ ] **Step 3: Write failing DLQ aspect tests**

Create `ReliableMqDlqAspectTest` with tests that assert:

- A method annotated with `@ReliableMqDlq` delegates to `ReliableMqReplayService.recordFailure(...)`.
- The original queue/exchange/routingKey values passed are the replay target, not the DLQ queue.
- The DLQ method body is invoked only for explicit alerting after durable recording succeeds.

Run:

`mvn -pl nexus-infrastructure -Dtest=ReliableMqDlqAspectTest test`

Expected: compilation fails because annotation/aspect classes do not exist.

Do not commit at this point.

- [ ] **Step 4: Implement annotations and permanent failure exception**

Add annotations with runtime retention and method target:

- `ReliableMqPublish`: `exchange`, `routingKey`, `eventId`, `payload`
- `ReliableMqConsume`: `consumerName`, `eventId`, `payload`
- `ReliableMqDlq`: `consumerName`, `originalQueue`, `originalExchange`, `originalRoutingKey`, `fallbackPayloadType`, `eventId` defaulting to empty string, `lastError` defaulting to empty string

Add `ReliableMqPermanentFailureException extends RuntimeException`.

- [ ] **Step 5: Implement expression evaluator**

Implement `ReliableMqExpressionEvaluator` using Spring Expression Language with method parameter names available through `MethodBasedEvaluationContext`. It must:

- Evaluate `#event.eventId` and similar expressions.
- Return `String` for event id expressions.
- Return `Object` for payload expressions.
- Throw `IllegalArgumentException` when expression output is null or blank where nonblank is required.

- [ ] **Step 6: Implement publish aspect**

Implement `ReliableMqPublishAspect`:

- `@Around("@annotation(annotation)")`
- Proceed first so producer method validation can run.
- Evaluate event id and payload after successful method execution.
- Call `ReliableMqOutboxService.save(eventId, exchange, routingKey, payload)`.
- Do not call `publishReady`.
- Use explicit `@Order`.
- Choose ordering so `ReliableMqOutboxService.save(...)` runs inside an active transaction when the annotated method is transactional.
- Keep a test in `ReliableMqAopWiringTest` proving the transaction is active at `save(...)` time.

- [ ] **Step 7: Implement consume aspect**

Implement `ReliableMqConsumeAspect`:

- Evaluate event id and payload before business method execution.
- Serialize payload through `ObjectMapper`.
- Call `consumerRecordService.startManual(eventId, consumerName, payloadJson)`.
- For `DUPLICATE_DONE` and `IN_PROGRESS`, return without invoking business logic.
- For `INVALID`, throw `ReliableMqPermanentFailureException`.
- On success, call `markDone`.
- On any thrown exception, call `markFail(eventId, consumerName, exception.getMessage())`, then rethrow.

- [ ] **Step 8: Implement DLQ aspect**

Implement `ReliableMqDlqAspect`:

- Require the intercepted method to have an `org.springframework.amqp.core.Message` argument.
- Evaluate event id and last error expressions only when their annotation values are nonblank.
- Call `ReliableMqReplayService.recordFailure(...)` before explicit method body alerting.
- Rethrow recording failures.

- [ ] **Step 9: Add AOP dependency**

Add `spring-boot-starter-aop` to `nexus-infrastructure/pom.xml`, because the aspects live in infrastructure and are scanned by `nexus-app`.

- [ ] **Step 10: Run foundation tests**

Run:

`mvn -pl nexus-infrastructure -Dtest=ReliableMqPublishAspectTest,ReliableMqConsumeAspectTest,ReliableMqDlqAspectTest,ReliableMqAopWiringTest test`

Expected: all tests pass.

- [ ] **Step 11: Commit**

Run:

`git add project/nexus/nexus-infrastructure/pom.xml project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/mq/reliable/annotation/ReliableMqPublish.java project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/mq/reliable/annotation/ReliableMqConsume.java project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/mq/reliable/annotation/ReliableMqDlq.java project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/mq/reliable/exception/ReliableMqPermanentFailureException.java project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/mq/reliable/aop/ReliableMqExpressionEvaluator.java project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/mq/reliable/aop/ReliableMqPublishAspect.java project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/mq/reliable/aop/ReliableMqConsumeAspect.java project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/mq/reliable/aop/ReliableMqDlqAspect.java project/nexus/nexus-infrastructure/src/test/java/cn/nexus/infrastructure/mq/reliable/aop/ReliableMqPublishAspectTest.java project/nexus/nexus-infrastructure/src/test/java/cn/nexus/infrastructure/mq/reliable/aop/ReliableMqConsumeAspectTest.java project/nexus/nexus-infrastructure/src/test/java/cn/nexus/infrastructure/mq/reliable/aop/ReliableMqDlqAspectTest.java project/nexus/nexus-infrastructure/src/test/java/cn/nexus/infrastructure/mq/reliable/aop/ReliableMqAopWiringTest.java`

`git commit -m "feat: add reliable mq annotation aspects"`

---

### Task 2: Chain Inventory and Architecture Regression Tests

**Files:**
- Create: `project/nexus/docs/operations/reliable-mq-chain-inventory.md`
- Create: `project/nexus/nexus-app/src/test/java/cn/nexus/contract/mq/ReliableMqArchitectureContractTest.java`

- [ ] **Step 1: Write failing architecture tests**

Create `ReliableMqArchitectureContractTest` with tests that scan Java source files under `project/nexus` and report:

- Raw `rabbitTemplate.convertAndSend(` or `.convertAndSend(` is not present outside allowlisted files.
- Allowlisted files initially include `ReliableMqOutboxService.java`, `ReliableMqReplayService.java`, `ContentEventOutboxPort.java`, `UserEventOutboxPort.java`, and test files.
- `@RabbitListener` methods in known side-effecting consumers are either annotated with `@ReliableMqConsume`, are manual-ack `RelationCounterProjectConsumer`, or are listed as best-effort in the inventory.
- The initial version runs in audit mode by asserting the exact current finding count. It must remain enabled.

Run:

`mvn -pl nexus-app -Dtest=ReliableMqArchitectureContractTest test`

Expected: pass in audit mode while printing or asserting the current finding list.

- [ ] **Step 2: Create chain inventory**

Create `project/nexus/docs/operations/reliable-mq-chain-inventory.md` with the columns required by the spec:

- Producer
- Exchange
- Routing key
- Payload type
- Event id source
- Consumer
- Listener container
- Idempotency
- DLQ queue
- Replay route
- Classification
- Raw publish status

Mark each chain as one of:

- `Reliable Required`
- `Reliable Producer Only`
- `Best-Effort`
- `Manual ACK Explicit`
- `Domain Outbox Internal`

- [ ] **Step 3: Add inventory-aware allowlists**

Update `ReliableMqArchitectureContractTest` so allowlisted best-effort and manual-ack paths are read from hard-coded constants matching the inventory. Do not make the test parse markdown.

- [ ] **Step 4: Run architecture test and record expected failures**

Run:

`mvn -pl nexus-app -Dtest=ReliableMqArchitectureContractTest test`

Expected: pass in audit mode with the current raw publish and unannotated listener findings represented as expected counts. Task 10 will change audit mode to enforcement mode with zero unexpected findings.

- [ ] **Step 5: Commit inventory**

Run:

`git add project/nexus/docs/operations/reliable-mq-chain-inventory.md project/nexus/nexus-app/src/test/java/cn/nexus/contract/mq/ReliableMqArchitectureContractTest.java`

`git commit -m "test: inventory reliable mq chains"`

---

### Task 3: Convert Existing Reliable Producers to `@ReliableMqPublish`

**Files:**
- Modify: `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/CommentEventPort.java`
- Modify: `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/InteractionNotifyEventPort.java`
- Modify: `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/RecommendFeedbackEventPort.java`
- Modify: `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/RiskTaskPort.java`
- Modify: `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/producer/ContentScheduleProducer.java`
- Modify: `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/cache/ContentCacheEvictPort.java`
- Test: existing unit tests plus new producer-specific tests where missing.

- [ ] **Step 1: Write or update producer tests**

For each listed producer, write or update a unit test that calls the public publish method through a Spring proxy or the aspect test harness and verifies `ReliableMqOutboxService.save(...)` receives the same exchange, routing key, event id, and payload as before.

Run:

`mvn -pl nexus-infrastructure,nexus-trigger -Dtest=CommentEventPortTest,InteractionNotifyEventPortTest,RecommendFeedbackEventPortTest,RiskTaskPortTest,ContentScheduleProducerTest,ContentCacheEvictPortTest test`

Expected: newly created tests fail until conversion is complete. If a listed test class does not exist yet, create it in this step instead of relying on Maven to skip it.

Do not commit at this point.

- [ ] **Step 2: Convert producer methods**

For each producer:

- Add `@ReliableMqPublish`.
- Remove direct `ReliableMqOutboxService` field if the class no longer needs it.
- Keep validation in the method body where it already exists.
- Preserve exchange, routing key, and event id source.
- Do not call `publishReady`.

- [ ] **Step 3: Run producer tests**

Run:

`mvn -pl nexus-infrastructure,nexus-trigger -Dtest=CommentEventPortTest,InteractionNotifyEventPortTest,RecommendFeedbackEventPortTest,RiskTaskPortTest,ContentScheduleProducerTest,ContentCacheEvictPortTest test`

Expected: all producer tests pass.

- [ ] **Step 4: Commit**

Run `git status --short` first. Stage only files changed for Task 3.

Run:

`git add project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/CommentEventPort.java project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/InteractionNotifyEventPort.java project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/RecommendFeedbackEventPort.java project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/RiskTaskPort.java project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/producer/ContentScheduleProducer.java project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/cache/ContentCacheEvictPort.java`

Also add only the producer test files created or modified in this task. Do not stage unrelated test files.

`git commit -m "refactor: annotate reliable mq producers"`

---

### Task 4: Convert Existing Idempotent Auto-ACK Consumers to `@ReliableMqConsume`

**Files:**
- Modify auto-ack consumers already using `ReliableMqConsumerRecordService`:
- `FeedRecommendFeedbackConsumer.java`
- `FeedRecommendFeedbackAConsumer.java`
- `FeedRecommendItemUpsertConsumer.java`
- `FeedRecommendItemDeleteConsumer.java`
- `FeedFanoutDispatcherConsumer.java`
- `FeedFanoutTaskConsumer.java`
- `ContentScheduleConsumer.java`
- `InteractionNotifyConsumer.java`
- `PostSummaryGenerateConsumer.java`
- `RiskImageScanConsumer.java`
- `RiskLlmScanConsumer.java`
- Test: add or update matching consumer tests.

Do not convert `SearchIndexCdcConsumer` in this task unless `SearchIndexMqConfig.searchIndexListenerContainerFactory` is first proven to have equivalent retry and DLQ behavior to `reliableMqListenerContainerFactory`. If not proven, leave it for Task 8 chain classification.

- [ ] **Step 1: Write failing representative consumer tests**

Create or update tests for `FeedRecommendFeedbackConsumer`, `FeedFanoutDispatcherConsumer`, and `RiskImageScanConsumer` that assert:

- duplicate done does not invoke business dependency
- started success invokes business dependency and marks done
- business failure marks fail and rethrows
- invalid event id throws `ReliableMqPermanentFailureException`

Run:

`mvn -pl nexus-trigger -Dtest=FeedRecommendFeedbackConsumerTest,FeedFanoutDispatcherConsumerTest,RiskImageScanConsumerTest test`

Expected: fail until annotations/aspect are applied and old manual idempotency code is removed.

Do not commit at this point.

- [ ] **Step 2: Convert consumer methods**

For each listed consumer:

- Add `@ReliableMqConsume(consumerName=..., eventId=..., payload=...)` to the `@RabbitListener` method.
- Confirm the listener uses `containerFactory = "reliableMqListenerContainerFactory"` before annotating.
- Remove direct `ReliableMqConsumerRecordService` and `ObjectMapper` fields when only used for boilerplate.
- Keep payload validation and business logic in the method.
- Throw `ReliableMqPermanentFailureException` for permanent invalid payloads.
- Let retryable exceptions escape.

- [ ] **Step 3: Preserve downstream emission rules**

Where a consumer emits downstream MQ messages, leave a temporary raw publish only when the inventory row marks it `Task 6 pending`. Do not add new raw publishes in this task.

- [ ] **Step 4: Run consumer tests**

Run:

`mvn -pl nexus-trigger -Dtest=FeedRecommendFeedbackConsumerTest,FeedFanoutDispatcherConsumerTest,RiskImageScanConsumerTest test`

Expected: all representative tests pass.

- [ ] **Step 5: Commit**

Run:

`git add project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FeedRecommendFeedbackConsumer.java project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FeedRecommendFeedbackAConsumer.java project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FeedRecommendItemUpsertConsumer.java project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FeedRecommendItemDeleteConsumer.java project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FeedFanoutDispatcherConsumer.java project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FeedFanoutTaskConsumer.java project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/ContentScheduleConsumer.java project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/InteractionNotifyConsumer.java project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/PostSummaryGenerateConsumer.java project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/RiskImageScanConsumer.java project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/RiskLlmScanConsumer.java`

Also add only the consumer test files created or modified in this task.

`git commit -m "refactor: annotate reliable mq consumers"`

---

### Task 5: Convert DLQ Consumers to `@ReliableMqDlq`

**Files:**
- Modify: `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FeedRecommendItemDeleteDlqConsumer.java`
- Modify: `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/RelationBlockDlqConsumer.java`
- Modify: `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/InteractionNotifyDlqConsumer.java`
- Modify: `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/RiskLlmScanDlqConsumer.java`
- Modify: `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FeedFanoutTaskDlqConsumer.java`
- Modify: `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/RiskImageScanDlqConsumer.java`
- Modify: `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FeedRecommendItemUpsertDlqConsumer.java`
- Modify: `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/RelationFollowDlqConsumer.java`
- Modify: `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FeedRecommendFeedbackADlqConsumer.java`
- Modify: `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FeedFanoutDispatcherDlqConsumer.java`
- Modify: `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FeedRecommendFeedbackDlqConsumer.java`
- Modify: `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/ContentScheduleDLQConsumer.java`
- Modify: `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/PostSummaryGenerateDlqConsumer.java`
- Delete dependency from converted classes: remove `ReliableMqDlqRecorder` injection where annotation recording fully replaces it.
- Test: add or update DLQ consumer tests.

- [ ] **Step 1: Write failing DLQ conversion tests**

For `FeedFanoutDispatcherDlqConsumer`, `RiskImageScanDlqConsumer`, and `ContentScheduleDLQConsumer`, write tests that verify the annotation metadata maps to:

- original queue, not DLQ queue
- original exchange
- original routing key
- fallback payload type
- consumer name

Run:

`mvn -pl nexus-trigger -Dtest=FeedFanoutDispatcherDlqConsumerTest,RiskImageScanDlqConsumerTest,ContentScheduleDLQConsumerTest test`

Expected: fail until conversion is complete.

Do not commit at this point.

- [ ] **Step 2: Convert DLQ listeners**

For each DLQ consumer:

- Add `@ReliableMqDlq`.
- Remove direct `ReliableMqDlqRecorder` field where no alerting remains.
- Keep only alert logging that is business-specific.
- Do not mutate business state.
- Verify `originalQueue`, `originalExchange`, and `originalRoutingKey` match the replay target in the corresponding config class.
- For `RelationFollowDlqConsumer` and `RelationBlockDlqConsumer`, confirm replay routes back to the relation projection queue and not to a DLQ queue.

- [ ] **Step 3: Run DLQ tests**

Run:

`mvn -pl nexus-trigger -Dtest=FeedFanoutDispatcherDlqConsumerTest,RiskImageScanDlqConsumerTest,ContentScheduleDLQConsumerTest test`

Expected: all DLQ tests pass.

- [ ] **Step 4: Commit**

Run:

`git add project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FeedRecommendItemDeleteDlqConsumer.java project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/RelationBlockDlqConsumer.java project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/InteractionNotifyDlqConsumer.java project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/RiskLlmScanDlqConsumer.java project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FeedFanoutTaskDlqConsumer.java project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/RiskImageScanDlqConsumer.java project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FeedRecommendItemUpsertDlqConsumer.java project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/RelationFollowDlqConsumer.java project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FeedRecommendFeedbackADlqConsumer.java project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FeedFanoutDispatcherDlqConsumer.java project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FeedRecommendFeedbackDlqConsumer.java project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/ContentScheduleDLQConsumer.java project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/PostSummaryGenerateDlqConsumer.java`

Also add only the DLQ test files created or modified in this task.

`git commit -m "refactor: annotate reliable mq dlq listeners"`

---

### Task 6: Convert Downstream Publishes Inside Consumers

**Files:**
- Modify: `FeedFanoutDispatcherConsumer.java`
- Modify: `RiskImageScanConsumer.java`
- Modify: `RiskLlmScanConsumer.java`
- Modify: `SearchIndexCdcRawPublisher.java`
- Create: `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/producer/FeedFanoutTaskProducer.java`
- Create: `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/producer/RiskScanCompletedProducer.java`
- Create: `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/producer/SearchIndexCdcEventProducer.java`
- Test matching consumer and producer tests.

- [ ] **Step 1: Write failing downstream publish tests**

Add tests proving downstream publish is durable:

- `FeedFanoutDispatcherConsumer` creates `FeedFanoutTask` messages through a producer bean using deterministic child event id.
- `RiskImageScanConsumer` and `RiskLlmScanConsumer` save scan completed messages through reliable publishing.
- `SearchIndexCdcRawPublisher` saves translated events through reliable publishing and updates the existing `SearchIndexCdcRawPublisherTest` so it no longer verifies raw `RabbitTemplate.convertAndSend(...)`.

Run:

`mvn -pl nexus-trigger -Dtest=FeedFanoutDispatcherConsumerTest,RiskImageScanConsumerTest,RiskLlmScanConsumerTest,SearchIndexCdcRawPublisherTest test`

Expected: fail while raw `rabbitTemplate.convertAndSend(...)` remains.

Do not commit at this point.

- [ ] **Step 2: Extract producer beans**

Create producer beans for downstream emissions:

- `FeedFanoutTaskProducer`
- `RiskScanCompletedProducer`
- `SearchIndexCdcEventProducer`

Annotate their public methods with `@ReliableMqPublish`.

Each producer method must receive the already constructed event object. It must not regenerate event ids.

- [ ] **Step 3: Update consumers**

Replace raw `RabbitTemplate` usage in the listed consumers with producer bean calls. Ensure the producer bean is injected and invoked through Spring proxy.

For `FeedFanoutDispatcherConsumer`, keep child event id generation in the dispatcher because it owns slice coordinates. The new producer only persists the provided `FeedFanoutTask`.

- [ ] **Step 4: Run downstream publish tests**

Run:

`mvn -pl nexus-trigger -Dtest=FeedFanoutDispatcherConsumerTest,RiskImageScanConsumerTest,RiskLlmScanConsumerTest,SearchIndexCdcRawPublisherTest test`

Expected: all tests pass.

- [ ] **Step 5: Commit**

Run:

`git add project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/FeedFanoutDispatcherConsumer.java project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/RiskImageScanConsumer.java project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/RiskLlmScanConsumer.java project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/SearchIndexCdcRawPublisher.java project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/producer/FeedFanoutTaskProducer.java project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/producer/RiskScanCompletedProducer.java project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/producer/SearchIndexCdcEventProducer.java`

Also add only the downstream publish test files created or modified in this task.

`git commit -m "refactor: route consumer mq emissions through outbox"`

---

### Task 7: Convert Remaining Raw RabbitMQ Producers or Classify Best-Effort

**Files:**
- Modify or classify: `ContentDispatchPort.java`
- Modify: `ReactionNotifyMqPort.java`
- Modify: `ReactionRecommendFeedbackMqPort.java`
- Modify: `RiskProducer.java`
- Modify: `RelationEventPort.java`
- Update: `project/nexus/docs/operations/reliable-mq-chain-inventory.md`
- Test producer tests.

- [ ] **Step 1: Write failing raw publish architecture test**

Enable or update `ReliableMqArchitectureContractTest` so raw publish in these files fails.

Run:

`mvn -pl nexus-app -Dtest=ReliableMqArchitectureContractTest test`

Expected: fail listing the remaining raw producers.

Do not commit at this point.

- [ ] **Step 2: Convert or classify each producer**

For each file:

- If the chain is required, convert to `@ReliableMqPublish` or an explicit `ReliableMqOutboxService.save(...)` call with recorded reason.
- If the chain is best-effort, add it to the inventory with loss impact and allowlist it in architecture test.
- Preserve `ContentEventOutboxPort` and `UserEventOutboxPort` as domain outbox internals.
- For `ContentDispatchPort`, first determine whether any active domain service still uses it instead of `ContentEventOutboxPort`. If unused, remove the bean or classify it as dead code in the inventory rather than adding another publish path.
- For `RelationEventPort`, keep relation projection manual-ack semantics and convert only the producer-side durability.

- [ ] **Step 3: Run producer and architecture tests**

Run:

`mvn -pl nexus-infrastructure,nexus-trigger,nexus-app -Dtest=ReliableMqArchitectureContractTest,ReactionNotifyMqPortTest,ReactionRecommendFeedbackMqPortTest,RiskProducerTest,RelationEventPortTest test`

Expected: all existing and newly created tests pass. If a per-class producer test is not created, the architecture test must explicitly cover that producer's raw publish removal or best-effort classification.

- [ ] **Step 4: Commit**

Run:

`git add project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/ContentDispatchPort.java project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/ReactionNotifyMqPort.java project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/ReactionRecommendFeedbackMqPort.java project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/RelationEventPort.java project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/producer/RiskProducer.java project/nexus/docs/operations/reliable-mq-chain-inventory.md project/nexus/nexus-app/src/test/java/cn/nexus/contract/mq/ReliableMqArchitectureContractTest.java`

Also add only the producer tests created or modified in this task.

`git commit -m "refactor: close raw rabbitmq producer gaps"`

---

### Task 8: Finalize Auto-ACK Consumer Coverage

**Files:**
- Modify only auto-ack `@RabbitListener` classes reported by `ReliableMqArchitectureContractTest` after Tasks 1-7.
- Update: `project/nexus/docs/operations/reliable-mq-chain-inventory.md`
- Test: `ReliableMqArchitectureContractTest`

- [ ] **Step 1: Run listener architecture test in enforcement mode**

Run:

`mvn -pl nexus-app -Dtest=ReliableMqArchitectureContractTest test`

Expected: pass only when every side-effecting auto-ack listener is annotated with `@ReliableMqConsume`, covered by manual-ack explicit rules, or listed as best-effort in the inventory. If it fails, continue with Step 2 and do not commit.

- [ ] **Step 2: Convert remaining auto-ack consumers**

For each listener coverage finding from `ReliableMqArchitectureContractTest`:

- Add `@ReliableMqConsume`, or
- classify as best-effort in inventory and architecture allowlist, or
- document as manual-ack explicit if it uses manual ack.
- For listeners using `searchIndexListenerContainerFactory`, either prove that factory has retry and DLQ behavior equivalent to `reliableMqListenerContainerFactory`, or classify and fix the container before adding `@ReliableMqConsume`.
- Do not change listeners that are not reported by the architecture test and not listed in the chain inventory.

- [ ] **Step 3: Run listener architecture test again**

Run:

`mvn -pl nexus-app -Dtest=ReliableMqArchitectureContractTest test`

Expected: pass.

- [ ] **Step 4: Commit**

Run:

`git add project/nexus/docs/operations/reliable-mq-chain-inventory.md project/nexus/nexus-app/src/test/java/cn/nexus/contract/mq/ReliableMqArchitectureContractTest.java`

Also add only the remaining listener source and test files changed in Task 8. Do not stage unrelated consumer files.

`git commit -m "test: enforce reliable mq listener coverage"`

---

### Task 9: Manual ACK Reliability Verification

**Files:**
- Modify test only unless bug found: `project/nexus/nexus-trigger/src/test/java/cn/nexus/trigger/listener/social/RelationCounterProjectConsumerTest.java`
- Modify if bug found: `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/listener/social/RelationCounterProjectConsumer.java`

- [ ] **Step 1: Write manual ack tests**

Add tests for `RelationCounterProjectConsumer`:

- duplicate done calls `basicAck`
- fresh processing duplicate calls `basicNack(requeue=true)`
- business failure marks fail and calls `basicNack(requeue=false)`
- successful processing marks done before `basicAck`

Run:

`mvn -pl nexus-trigger -Dtest=RelationCounterProjectConsumerTest test`

Expected: fail if current code does not expose test seams or misses required behavior.

Do not commit at this point.

- [ ] **Step 2: Fix only required manual ack behavior**

If tests fail because of missing behavior, update `RelationCounterProjectConsumer`. Do not convert it to `@ReliableMqConsume`.

- [ ] **Step 3: Run manual ack tests**

Run:

`mvn -pl nexus-trigger -Dtest=RelationCounterProjectConsumerTest test`

Expected: pass.

- [ ] **Step 4: Commit**

Run:

`git add project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/listener/social/RelationCounterProjectConsumer.java project/nexus/nexus-trigger/src/test/java/cn/nexus/trigger/listener/social/RelationCounterProjectConsumerTest.java`

`git commit -m "test: verify manual ack reliable mq consumer"`

---

### Task 10: Final Verification and Inventory Closure

**Files:**
- Update: `project/nexus/docs/operations/reliable-mq-chain-inventory.md`
- Update: `project/nexus/nexus-app/src/test/java/cn/nexus/contract/mq/ReliableMqArchitectureContractTest.java`

- [ ] **Step 1: Verify architecture test is in enforcement mode**

`ReliableMqArchitectureContractTest` must remain enabled. It must assert zero unexpected raw publish and listener coverage findings outside the final allowlist.

- [ ] **Step 2: Verify inventory has no unclassified rows**

Search the inventory for explicit unclassified markers.

Run:

`rg -n "UNCLASSIFIED|UNKNOWN|NOT_CLASSIFIED" project/nexus/docs/operations/reliable-mq-chain-inventory.md`

Expected: no matches.

- [ ] **Step 3: Run focused reliable MQ tests**

Run:

`mvn -pl nexus-infrastructure,nexus-trigger,nexus-app -Dtest='*ReliableMq*Test,*Mq*ConsumerTest,*Mq*ProducerTest,RelationCounterProjectConsumerTest' test`

Expected: pass.

- [ ] **Step 4: Run module test suites**

Run:

`mvn -pl nexus-infrastructure,nexus-trigger,nexus-app -am test`

Expected: pass.

- [ ] **Step 5: Commit final closure**

Run `git status --short` first. Stage only files changed for Task 10.

Run:

`git add project/nexus/docs/operations/reliable-mq-chain-inventory.md project/nexus/nexus-app/src/test/java/cn/nexus/contract/mq/ReliableMqArchitectureContractTest.java`

Also add only the final closure source and test files changed in Task 10. Do not stage entire modules.

`git commit -m "test: close reliable mq remediation coverage"`

## Self-Review Checklist

- Spec coverage: covered AOP boundaries, producer durability, consumer idempotency, DLQ replay, manual ack exclusion, domain outbox preservation, architecture tests, and inventory closure.
- Placeholder scan: no task uses unresolved markers or unspecified "add tests" language without a concrete assertion list.
- Type consistency: annotation names and service names match the spec: `ReliableMqPublish`, `ReliableMqConsume`, `ReliableMqDlq`, `ReliableMqPermanentFailureException`, `ReliableMqOutboxService`, `ReliableMqConsumerRecordService`, `ReliableMqReplayService`.
