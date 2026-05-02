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
- `project/nexus/nexus-app/src/test/java/cn/nexus/contract/mq/ReliableMqArchitectureContractTest.java`
- `project/nexus/docs/operations/reliable-mq-chain-inventory.md`

Modify:

- `project/nexus/nexus-infrastructure/pom.xml`: add `spring-boot-starter-aop`.
- `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/mq/reliable/ReliableMqMessageSupport.java`: reuse serialization and event id helpers if needed.
- Producer ports already using `ReliableMqOutboxService`: `CommentEventPort`, `InteractionNotifyEventPort`, `RecommendFeedbackEventPort`, `RiskTaskPort`, `ContentScheduleProducer`, `ContentCacheEvictPort`.
- Auto-ack consumers already using `ReliableMqConsumerRecordService`: feed, recommendation, risk, summary, schedule, search CDC, interaction notify consumers.
- DLQ consumers already using `ReliableMqDlqRecorder`: all `*DlqConsumer` and `ContentScheduleDLQConsumer`.
- Remaining raw RabbitMQ publishers: `ContentDispatchPort`, `ReactionNotifyMqPort`, `ReactionRecommendFeedbackMqPort`, `RiskProducer`, downstream publishes inside `FeedFanoutDispatcherConsumer`, `RiskImageScanConsumer`, `RiskLlmScanConsumer`, `SearchIndexCdcRawPublisher`, `RelationEventPort`.
- Manual-ack consumer: `RelationCounterProjectConsumer` test coverage only, no AOP conversion.

Do not modify:

- `ContentEventOutboxPort` and `UserEventOutboxPort` table semantics. Their internal drain publishes remain allowlisted domain outbox internals.
- Kafka counter producer and Kafka consumers.

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

- [ ] **Step 1: Write failing publish aspect tests**

Create `ReliableMqPublishAspectTest` with tests that assert:

- A public method annotated with `@ReliableMqPublish(exchange="social.interaction", routingKey="comment.created", eventId="#event.eventId", payload="#event")` causes `ReliableMqOutboxService.save("evt-1", "social.interaction", "comment.created", event)` exactly once.
- Blank event id throws `IllegalArgumentException`.
- A method body that throws does not call `save`.

Run:

`mvn -pl nexus-infrastructure -Dtest=ReliableMqPublishAspectTest test`

Expected: compilation fails because annotation/aspect classes do not exist.

- [ ] **Step 2: Write failing consume aspect tests**

Create `ReliableMqConsumeAspectTest` with tests that assert:

- `StartResult.DUPLICATE_DONE` short-circuits and does not invoke the business method.
- `StartResult.STARTED` invokes the business method and then calls `markDone`.
- Business failure calls `markFail` and rethrows the original failure.
- `ReliableMqPermanentFailureException` is rethrown and not swallowed.

Run:

`mvn -pl nexus-infrastructure -Dtest=ReliableMqConsumeAspectTest test`

Expected: compilation fails because annotation/aspect classes do not exist.

- [ ] **Step 3: Write failing DLQ aspect tests**

Create `ReliableMqDlqAspectTest` with tests that assert:

- A method annotated with `@ReliableMqDlq` delegates to `ReliableMqReplayService.recordFailure(...)`.
- The original queue/exchange/routingKey values passed are the replay target, not the DLQ queue.
- The DLQ method body is invoked only for optional alerting after durable recording succeeds.

Run:

`mvn -pl nexus-infrastructure -Dtest=ReliableMqDlqAspectTest test`

Expected: compilation fails because annotation/aspect classes do not exist.

- [ ] **Step 4: Implement annotations and permanent failure exception**

Add annotations with runtime retention and method target:

- `ReliableMqPublish`: `exchange`, `routingKey`, `eventId`, `payload`
- `ReliableMqConsume`: `consumerName`, `eventId`, `payload`
- `ReliableMqDlq`: `consumerName`, `originalQueue`, `originalExchange`, `originalRoutingKey`, `fallbackPayloadType`, optional `eventId`, optional `lastError`

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
- Use explicit `@Order` and document the value in code comment.

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
- Evaluate optional event id and last error expressions when present.
- Call `ReliableMqReplayService.recordFailure(...)` before optional method body alerting.
- Rethrow recording failures.

- [ ] **Step 9: Add AOP dependency**

Add `spring-boot-starter-aop` to `nexus-infrastructure/pom.xml`, because the aspects live in infrastructure and are scanned by `nexus-app`.

- [ ] **Step 10: Run foundation tests**

Run:

`mvn -pl nexus-infrastructure -Dtest=ReliableMqPublishAspectTest,ReliableMqConsumeAspectTest,ReliableMqDlqAspectTest test`

Expected: all tests pass.

- [ ] **Step 11: Commit**

Run:

`git add project/nexus/nexus-infrastructure/pom.xml project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/mq/reliable project/nexus/nexus-infrastructure/src/test/java/cn/nexus/infrastructure/mq/reliable/aop`

`git commit -m "feat: add reliable mq annotation aspects"`

---

### Task 2: Chain Inventory and Architecture Regression Tests

**Files:**
- Create: `project/nexus/docs/operations/reliable-mq-chain-inventory.md`
- Create: `project/nexus/nexus-app/src/test/java/cn/nexus/contract/mq/ReliableMqArchitectureContractTest.java`

- [ ] **Step 1: Write failing architecture tests**

Create `ReliableMqArchitectureContractTest` with tests that scan Java source files under `project/nexus` and assert:

- Raw `rabbitTemplate.convertAndSend(` or `.convertAndSend(` is not present outside allowlisted files.
- Allowlisted files initially include `ReliableMqOutboxService.java`, `ReliableMqReplayService.java`, `ContentEventOutboxPort.java`, `UserEventOutboxPort.java`, and test files.
- `@RabbitListener` methods in known side-effecting consumers are either annotated with `@ReliableMqConsume`, are manual-ack `RelationCounterProjectConsumer`, or are listed as best-effort in the inventory.

Run:

`mvn -pl nexus-app -Dtest=ReliableMqArchitectureContractTest test`

Expected: fail because raw publishers and unannotated listeners still exist.

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

Expected: fail with current raw publish and unannotated listener findings. Keep the test committed only if it can be temporarily disabled with a clear `@Disabled("enabled after migration tasks")`. Prefer committing it enabled in the final migration task.

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

For each listed producer, write or update a unit test that calls the public publish method through the Spring bean or aspect test harness and verifies `ReliableMqOutboxService.save(...)` receives the same exchange, routing key, event id, and payload as before.

Run:

`mvn -pl nexus-infrastructure,nexus-trigger -Dtest=CommentEventPortTest,InteractionNotifyEventPortTest,RecommendFeedbackEventPortTest,RiskTaskPortTest,ContentScheduleProducerTest,ContentCacheEvictPortTest test`

Expected: missing tests or failures until conversion is complete.

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

Run:

`git add project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/producer project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/cache project/nexus/**/src/test/java`

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
- `SearchIndexCdcConsumer.java`
- Test: add or update matching consumer tests.

- [ ] **Step 1: Write failing representative consumer tests**

For at least `FeedRecommendFeedbackConsumer`, `FeedFanoutDispatcherConsumer`, and `RiskImageScanConsumer`, write tests that assert:

- duplicate done does not invoke business dependency
- started success invokes business dependency and marks done
- business failure marks fail and rethrows
- invalid event id throws `ReliableMqPermanentFailureException`

Run:

`mvn -pl nexus-trigger -Dtest=FeedRecommendFeedbackConsumerTest,FeedFanoutDispatcherConsumerTest,RiskImageScanConsumerTest test`

Expected: fail until annotations/aspect are applied and old manual idempotency code is removed.

- [ ] **Step 2: Convert consumer methods**

For each listed consumer:

- Add `@ReliableMqConsume(consumerName=..., eventId=..., payload=...)` to the `@RabbitListener` method.
- Remove direct `ReliableMqConsumerRecordService` and `ObjectMapper` fields when only used for boilerplate.
- Keep payload validation and business logic in the method.
- Throw `ReliableMqPermanentFailureException` for permanent invalid payloads.
- Let retryable exceptions escape.

- [ ] **Step 3: Preserve downstream emission rules**

Where a consumer emits downstream MQ messages, do not leave raw RabbitMQ publish in place unless this task explicitly records a follow-up in the inventory. Prefer extracting the emission to a producer bean in Task 6.

- [ ] **Step 4: Run consumer tests**

Run:

`mvn -pl nexus-trigger -Dtest=FeedRecommendFeedbackConsumerTest,FeedFanoutDispatcherConsumerTest,RiskImageScanConsumerTest test`

Expected: all representative tests pass.

- [ ] **Step 5: Commit**

Run:

`git add project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer project/nexus/nexus-trigger/src/test/java/cn/nexus/trigger/mq/consumer`

`git commit -m "refactor: annotate reliable mq consumers"`

---

### Task 5: Convert DLQ Consumers to `@ReliableMqDlq`

**Files:**
- Modify all DLQ consumers under `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/*DlqConsumer.java`
- Modify: `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer/ContentScheduleDLQConsumer.java`
- Optional delete dependency: `ReliableMqDlqRecorder` injection from converted classes.
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

- [ ] **Step 2: Convert DLQ listeners**

For each DLQ consumer:

- Add `@ReliableMqDlq`.
- Remove direct `ReliableMqDlqRecorder` field where no alerting remains.
- Keep only alert logging that is business-specific.
- Do not mutate business state.

- [ ] **Step 3: Run DLQ tests**

Run:

`mvn -pl nexus-trigger -Dtest=FeedFanoutDispatcherDlqConsumerTest,RiskImageScanDlqConsumerTest,ContentScheduleDLQConsumerTest test`

Expected: all DLQ tests pass.

- [ ] **Step 4: Commit**

Run:

`git add project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer project/nexus/nexus-trigger/src/test/java/cn/nexus/trigger/mq/consumer`

`git commit -m "refactor: annotate reliable mq dlq listeners"`

---

### Task 6: Convert Downstream Publishes Inside Consumers

**Files:**
- Modify: `FeedFanoutDispatcherConsumer.java`
- Modify: `RiskImageScanConsumer.java`
- Modify: `RiskLlmScanConsumer.java`
- Modify: `SearchIndexCdcRawPublisher.java`
- Create producer beans as needed under `project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/producer/`
- Test matching consumer and producer tests.

- [ ] **Step 1: Write failing downstream publish tests**

Add tests proving downstream publish is durable:

- `FeedFanoutDispatcherConsumer` creates `FeedFanoutTask` messages through a producer bean using deterministic child event id.
- `RiskImageScanConsumer` and `RiskLlmScanConsumer` save scan completed messages through reliable publishing.
- `SearchIndexCdcRawPublisher` saves translated events through reliable publishing.

Run:

`mvn -pl nexus-trigger -Dtest=FeedFanoutDispatcherConsumerTest,RiskImageScanConsumerTest,RiskLlmScanConsumerTest,SearchIndexCdcRawPublisherTest test`

Expected: fail while raw `rabbitTemplate.convertAndSend(...)` remains.

- [ ] **Step 2: Extract producer beans**

Create producer beans for downstream emissions:

- `FeedFanoutTaskProducer`
- `RiskScanCompletedProducer`
- `SearchIndexCdcEventProducer`

Annotate their public methods with `@ReliableMqPublish`.

- [ ] **Step 3: Update consumers**

Replace raw `RabbitTemplate` usage in the listed consumers with producer bean calls. Ensure the producer bean is injected and invoked through Spring proxy.

- [ ] **Step 4: Run downstream publish tests**

Run:

`mvn -pl nexus-trigger -Dtest=FeedFanoutDispatcherConsumerTest,RiskImageScanConsumerTest,RiskLlmScanConsumerTest,SearchIndexCdcRawPublisherTest test`

Expected: all tests pass.

- [ ] **Step 5: Commit**

Run:

`git add project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/consumer project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/producer project/nexus/nexus-trigger/src/test/java/cn/nexus/trigger/mq/consumer`

`git commit -m "refactor: route consumer mq emissions through outbox"`

---

### Task 7: Convert Remaining Raw RabbitMQ Producers or Classify Best-Effort

**Files:**
- Modify: `ContentDispatchPort.java`
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

- [ ] **Step 2: Convert or classify each producer**

For each file:

- If the chain is required, convert to `@ReliableMqPublish` or an explicit `ReliableMqOutboxService.save(...)` call with recorded reason.
- If the chain is best-effort, add it to the inventory with loss impact and allowlist it in architecture test.
- Preserve `ContentEventOutboxPort` and `UserEventOutboxPort` as domain outbox internals.

- [ ] **Step 3: Run producer and architecture tests**

Run:

`mvn -pl nexus-infrastructure,nexus-trigger,nexus-app -Dtest=ReliableMqArchitectureContractTest,ReactionNotifyMqPortTest,ReactionRecommendFeedbackMqPortTest,RiskProducerTest,RelationEventPortTest test`

Expected: all tests pass or only missing optional per-class tests are replaced by architecture coverage.

- [ ] **Step 4: Commit**

Run:

`git add project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port project/nexus/nexus-trigger/src/main/java/cn/nexus/trigger/mq/producer project/nexus/docs/operations/reliable-mq-chain-inventory.md project/nexus/nexus-app/src/test/java/cn/nexus/contract/mq/ReliableMqArchitectureContractTest.java`

`git commit -m "refactor: close raw rabbitmq producer gaps"`

---

### Task 8: Finalize Auto-ACK Consumer Coverage

**Files:**
- Modify any remaining auto-ack `@RabbitListener` classes not handled earlier.
- Update: `project/nexus/docs/operations/reliable-mq-chain-inventory.md`
- Test: `ReliableMqArchitectureContractTest`

- [ ] **Step 1: Run listener architecture test**

Run:

`mvn -pl nexus-app -Dtest=ReliableMqArchitectureContractTest test`

Expected: fail if any side-effecting auto-ack listener lacks `@ReliableMqConsume`.

- [ ] **Step 2: Convert remaining auto-ack consumers**

For each failure:

- Add `@ReliableMqConsume`, or
- classify as best-effort in inventory and architecture allowlist, or
- document as manual-ack explicit if it uses manual ack.

- [ ] **Step 3: Run listener architecture test again**

Run:

`mvn -pl nexus-app -Dtest=ReliableMqArchitectureContractTest test`

Expected: pass.

- [ ] **Step 4: Commit**

Run:

`git add project/nexus project/nexus/docs/operations/reliable-mq-chain-inventory.md`

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

- [ ] **Step 1: Remove temporary disables**

If `ReliableMqArchitectureContractTest` was committed with `@Disabled`, remove it.

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

Run:

`git add project/nexus`

`git commit -m "test: close reliable mq remediation coverage"`

## Self-Review Checklist

- Spec coverage: covered AOP boundaries, producer durability, consumer idempotency, DLQ replay, manual ack exclusion, domain outbox preservation, architecture tests, and inventory closure.
- Placeholder scan: no task uses unresolved markers or unspecified "add tests" language without a concrete assertion list.
- Type consistency: annotation names and service names match the spec: `ReliableMqPublish`, `ReliableMqConsume`, `ReliableMqDlq`, `ReliableMqPermanentFailureException`, `ReliableMqOutboxService`, `ReliableMqConsumerRecordService`, `ReliableMqReplayService`.
