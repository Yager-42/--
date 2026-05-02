# Nexus Reliable MQ AOP Full Remediation Design

## Status

Approved direction for implementation planning. This revision is intentionally
strict: implementation plans must treat the rules below as constraints, not as
suggestions.

## Decision

Nexus will complete a full RabbitMQ reliability remediation by standardizing every MQ chain on the existing reliable messaging infrastructure:

- `ReliableMqOutboxService`
- `ReliableMqReplayService`
- `ReliableMqConsumerRecordService`
- `ReliableMqDlqRecorder`
- `reliableMqListenerContainerFactory`

The remediation will add a thin annotation and AOP layer to remove repeated boilerplate. The AOP layer will standardize shell behavior only. It will not own business routing decisions, retry policy decisions, acknowledgment decisions, or business state transitions.

The implementation target is:

- no producer does direct best-effort RabbitMQ publishing for a required business event
- no consumer swallows retryable failures
- no DLQ stops at logging only
- every replayable message has a durable event id and a durable replay record
- every side-effecting consumer has durable idempotency

## Goals

- Convert all current RabbitMQ producer chains to durable outbox publishing unless the chain is explicitly marked best-effort in this design.
- Convert all side-effecting consumers to a common idempotent consume wrapper.
- Convert all DLQ consumers to durable replay record writers.
- Preserve existing queue, exchange, and routing contracts. Any route correction must be listed in the chain inventory with the old route, new route, reason, and regression test.
- Keep retry and replay timing centralized in `ReliableMqPolicy`.
- Reduce repeated `save/start/markDone/markFail/record` code through annotations and aspects.
- Keep business code readable: consumer methods must show business work, while annotations show MQ reliability metadata.

## Non-Goals

- Replacing RabbitMQ with Kafka or another broker.
- Building a generic event bus abstraction above RabbitMQ.
- Moving business state-machine behavior into aspects.
- Moving manual RabbitMQ acknowledgment handling into the first version of the
  consume aspect.
- Making all optional cache invalidation or analytics signals transactional if they are explicitly classified as best-effort.
- Changing existing retry backoff constants outside `ReliableMqPolicy`.
- Removing existing outbox tables, replay tables, or consumer record tables.
- Migrating established domain-specific outbox tables into the generic reliable
  MQ outbox in this remediation.

## Current Project Context

Nexus already has the core reliable MQ pieces:

- `ReliableMqOutboxService` stores producer messages and publishes ready records.
- `ReliableMqReplayService` stores failed DLQ messages and replays ready records.
- `ReliableMqConsumerRecordService` stores `eventId + consumerName` idempotency state.
- `ReliableMqDlqRecorder` adapts DLQ messages into replay records.
- `ReliableMqListenerContainerConfig` applies stateless retry and sends exhausted failures to DLQ.

Many consumers already use `reliableMqListenerContainerFactory`, and many DLQ consumers already call `ReliableMqDlqRecorder`. The remaining issue is inconsistent adoption and duplicated wiring code.

`ReliableMqReplayJob` already exists and calls `ReliableMqReplayService.replayReady(...)`. Implementation must wire DLQ records into that existing replay flow rather than creating a second replay scheduler.

## Architecture

### Layer 1: Existing Reliable MQ Core

The existing services remain the source of truth for reliability state:

- producer durability lives in `reliable_mq_outbox`
- consumer idempotency lives in `reliable_mq_consumer_record`
- DLQ replay state lives in `reliable_mq_replay_record`

The aspect layer delegates to these services. It does not maintain any in-memory retry state and does not bypass these tables.

### Layer 2: Annotation Shell

Add three annotations:

- `@ReliableMqPublish`
- `@ReliableMqConsume`
- `@ReliableMqDlq`

These annotations declare reliability metadata close to the producer or consumer method.

The annotation layer removes repeated code such as:

- extracting event id
- serializing payload for consumer idempotency
- calling `ReliableMqOutboxService.save(...)`
- calling `ReliableMqConsumerRecordService.start(...)`
- calling `markDone(...)` after success
- calling `markFail(...)` before rethrow
- calling `ReliableMqDlqRecorder.record(...)`

The annotation layer must live under the `cn.nexus` package scanned by `nexus-app`. The module that owns the aspect classes must declare `spring-boot-starter-aop`. Annotation-only modules do not need the AOP dependency.

### Layer 3: Business Adapters

Business classes keep explicit ownership of:

- event type
- exchange
- routing key
- consumer name
- original queue for DLQ replay
- original exchange and routing key for replay
- validation rules
- business side effects
- unretryable payload classification

This keeps route and business semantics visible during code review.

## Binding Implementation Rules

These rules exist to prevent implementation drift.

1. A method annotated with a reliable MQ annotation must be invoked through a Spring proxy. Self-invocation, private methods, package-private helper methods, and direct `new` instances are not valid reliable MQ entry points.
2. Reliable producer methods must be public methods on Spring beans. If a consumer needs to emit derived messages, it must call a separate producer bean through the proxy.
3. Reliable consume annotations apply only to listener methods managed by Spring. The implementation must verify that the aspect runs around the actual `@RabbitListener` invocation path.
4. The first version of `@ReliableMqConsume` is for auto-ack listener containers such as `reliableMqListenerContainerFactory`. Manual-ack consumers remain explicitly coded in this remediation.
5. Aspects must fail closed. If expression evaluation, event id extraction, payload extraction, or metadata validation fails, the aspect throws and records no fake success state.
6. No aspect may silently downgrade a required reliable chain to best-effort.
7. No implementation task may introduce a new raw `rabbitTemplate.convertAndSend(...)` call outside approved publisher infrastructure or an explicitly documented best-effort class.
8. A reliable chain is complete only when producer durability, consumer retry, consumer idempotency, DLQ recording, and replay routing are all accounted for or explicitly exempted by chain classification.

## Producer Design

### Annotation

`@ReliableMqPublish` will be applied to public producer methods that currently exist to publish one MQ event. The annotation must declare exchange, routing key, event id expression, and payload expression.

The producer method body is allowed to do argument normalization or validation. It must not call RabbitMQ directly. The aspect stores the payload with `ReliableMqOutboxService.save(...)`.

If validation is required for business correctness, validation must happen before outbox insertion. The implementation must choose one of two explicit forms per method:

- validate in the producer method body before the aspect publishes
- move validation into a dedicated validator called by the aspect before save

An empty producer method is allowed only when all validation is covered by the event id and payload extraction rules and by upstream domain validation.

### Transaction Boundary

If the producer method runs inside an active Spring transaction, outbox insertion participates in that transaction.

If no active transaction exists, the outbox insert commits immediately. This preserves reliability for scheduled producers and infrastructure producers that are not part of a domain transaction.

This remediation will not add after-commit opportunistic publishing to the aspect. `ReliableMqOutboxRetryJob` remains the only generic outbox delivery driver. Adding after-commit publishing requires a separate design because it changes latency and failure-observation behavior.

### Event Id

Every reliable producer must provide a non-blank stable event id.

Allowed sources:

- payload field such as `event.eventId`
- deterministic composite id for derived slice messages, such as `parentEventId:offset:pageSize`
- explicit method argument

Missing event id is a programming error. The aspect must throw and prevent outbox insertion.

The same real-world event must produce the same event id across retries. Event ids must not use random UUIDs generated at publish time unless the UUID was already persisted as part of the business event before the publish method is called.

Derived messages must use deterministic child ids. For example, fanout slice tasks must derive child ids from the parent event id plus stable slice coordinates, not from current time or runtime sequence counters.

### Payload and Type Rules

The payload stored in outbox must be the exact object intended for RabbitMQ conversion. The implementation must not store a wrapper payload unless all consumers and DLQ replay metadata are changed consistently.

Outbox publishing must preserve message type information required by Spring AMQP conversion. If the current `ReliableMqOutboxService` already sets type headers through message conversion, implementation must keep that behavior verified by tests.

Payload objects used by reliable MQ must be JSON-serializable by the application's configured `ObjectMapper`. If an event uses `Instant` or other Java time fields, tests must cover serialization and replay deserialization.

### Producer Scope

The full remediation will review and align at least these producer families:

- content publish/update/delete dispatch
- comment created event
- interaction notification event
- recommendation feedback event
- risk task events
- content schedule events
- content cache eviction, unless explicitly kept best-effort
- feed fanout task messages emitted by dispatcher
- scan completed messages emitted by risk consumers
- search CDC translated publish messages
- reaction notification and recommendation feedback publish messages
- relation counter projection publish messages

Kafka counter producers are outside this RabbitMQ remediation.

### Existing Domain Outbox Rule

`ContentEventOutboxPort` and `UserEventOutboxPort` are established domain-specific outbox implementations. Their internal raw RabbitMQ publish calls are allowed only inside their outbox draining methods.

This remediation must not migrate those domain-specific tables to `ReliableMqOutboxService`. Their public save methods can receive clearer classification and tests, but their table schemas must remain separate.

### Producer Invocation Rule

Consumers that emit downstream messages must not rely on calling an annotated method in the same class, because Spring AOP will not intercept self-invocation. The implementation must use a separate producer Spring bean for downstream emissions. Direct `ReliableMqOutboxService` use inside a consumer is allowed only when the implementation plan records why a separate producer bean would add no stable abstraction.

## Consumer Design

### Annotation

`@ReliableMqConsume` will be applied to side-effecting `@RabbitListener` methods that use auto-ack retry containers. The annotation must declare consumer name, event id expression, and payload expression.

The aspect handles:

- missing event id classification
- `start(eventId, consumerName, payloadJson)`
- duplicate done short-circuit
- business method execution
- `markDone(...)`
- `markFail(...)`
- rethrowing retryable failures so the Rabbit listener container can retry and eventually dead-letter

The aspect must never acknowledge messages directly. It relies on the listener container's existing success and failure behavior.

### Validation

Validation stays in the consumer method or a small helper called by that method.

Invalid messages that can never succeed must throw one standardized permanent-failure exception. The implementation plan must define this exception before converting consumers.

Permanent failures still must be visible. Converted consumers must reject permanent failures into DLQ so `ReliableMqDlqRecorder` can create durable replay or parking records. They must not silently log and ack permanent failures.

### Idempotency Rule

Every consumer that writes a database row, changes Redis state, emits a downstream MQ message, calls an external dependency, or changes user-visible state must use `@ReliableMqConsume`.

Consumers that only log, metrics-only consumers, or pure in-memory cache listeners can be explicitly classified as best-effort. That classification must be visible in code and in the implementation plan.

`consumerName` must be stable and unique per logical consumer. Renaming a consumer name changes idempotency identity and must be treated as a migration decision.

`markDone(...)` must happen only after all side effects owned by that consumer method have completed successfully. If the method emits a downstream reliable message, that downstream message must already be durably saved before `markDone(...)`.

`markFail(...)` must happen before rethrow for retryable failures. The original exception must remain the cause so retry and DLQ behavior remains observable.

### Manual ACK Consumers

`RelationCounterProjectConsumer` uses `relationManualAckListenerContainerFactory` and explicit `Channel` acknowledgments. It is excluded from first-version `@ReliableMqConsume`.

Manual-ack consumers must still satisfy the same reliability outcome:

- durable idempotency before side effects
- ack only after durable success or duplicate-done detection
- requeue only for in-progress duplicate windows
- reject/dead-letter after retryable failure is marked failed
- no swallowed retryable failures

`@ReliableMqManualConsume` is outside this remediation. Adding it requires a separate design with explicit ack/nack rules.

### Consumer Scope

The full remediation will review and align these current consumer families:

- feed fanout dispatcher
- feed fanout task
- content schedule
- interaction notify
- recommend feedback C channel
- recommend feedback A channel
- recommend item upsert
- recommend item delete
- post summary generate
- risk image scan
- risk LLM scan
- comment created
- content cache eviction
- search index CDC consumer and raw publisher
- relation follow and block projection consumers

## DLQ and Replay Design

### Annotation

`@ReliableMqDlq` will be applied to DLQ listener methods that receive `Message`.

The aspect delegates to `ReliableMqDlqRecorder.record(...)`.

The annotation must declare consumer name, original queue, original exchange, original routing key, and fallback payload type. These values must point to the queue and route that should receive replay, not to the DLQ itself.

DLQ listener methods must be empty unless they add business-specific alerting. They must not parse and mutate business state.

### Replay Behavior

Replay records are durable and bounded:

- first DLQ write creates `PENDING`
- replay success marks `DONE`
- replay failure increments attempt and schedules the next retry through `ReliableMqPolicy`
- attempt exhaustion marks `FINAL_FAILED`

Replayed messages return to the original exchange and routing key. They are then processed by the original consumer and its idempotency wrapper.

### DLQ Scope

Every DLQ queue created for the full remediation must either:

- be consumed by a `@ReliableMqDlq` listener, or
- be documented as intentionally parked for manual handling only

The default is automatic replay. Manual-only parking requires a specific reason in the implementation plan.

`ReliableMqReplayJob` is the scheduler for automatic replay. Implementation must not create one replay job per business domain in this remediation.

Replay must publish to original exchange and original routing key. Replaying directly to a queue is not allowed unless the original chain already uses default exchange routing and the implementation documents that route explicitly.

## AOP Boundary Rules

The aspect layer is allowed to:

- read annotation metadata
- evaluate simple Spring Expression Language fields against method arguments
- serialize payloads through the configured `ObjectMapper`
- call existing reliable MQ services
- convert internal failures into consistent runtime exceptions
- log standardized reliability events

The aspect layer must not do:

- choose exchange or routing key dynamically from hidden code
- decide whether business exceptions are safe to ignore
- mutate business state
- call business repositories
- implement custom retry loops
- acknowledge RabbitMQ messages manually
- create in-memory de-duplication

### Spring AOP Constraints

Spring proxy semantics are part of this design:

- annotations on private methods do not count
- annotations on methods called from the same class do not count
- annotations on constructors do not count
- annotations on non-Spring objects do not count
- final classes or final methods must not be used if they prevent proxying

The implementation plan must include tests or architecture checks that prove annotated producer and consumer methods are Spring-proxied.

### Aspect Ordering

Producer aspect ordering must preserve transaction correctness. If a producer method is also transactional, the outbox insert must occur inside the intended transaction.

Consumer aspect ordering must preserve listener retry behavior. The consume aspect must wrap business code and rethrow failures so the listener container retry interceptor still sees the failure.

The implementation plan must state the chosen `@Order` values or ordering mechanism for the MQ aspects relative to transaction advice and listener retry advice.

## Error Handling

Producer errors:

- missing event id throws immediately
- payload serialization failure throws immediately
- outbox insert failure throws immediately and rolls back the surrounding transaction
- broker publish failure is handled by `ReliableMqOutboxService.publishReady(...)` retry state

Consumer errors:

- duplicate done records return without side effects
- in-progress duplicates return without side effects
- invalid missing metadata is rejected as a programming or payload error
- retryable business failures mark consumer record `FAIL` and rethrow
- the listener container handles immediate retry and DLQ routing

DLQ errors:

- missing replay event id is a DLQ recording failure and must be logged at error level
- duplicate replay records are ignored by durable uniqueness
- replay exhaustion results in `FINAL_FAILED`

## Transaction and Side-Effect Rules

The reliable MQ aspect must not create a false atomicity guarantee. It guarantees durable messaging state around a method invocation; it does not make remote calls, Redis writes, and RabbitMQ publishes globally transactional.

Rules:

- database writes and outbox writes that belong to the same business action must share the same Spring transaction when the caller already has a transaction
- consumer idempotency records and local database side effects must share a transaction when both side effects use the same database
- external calls inside consumers must be either idempotent by business key or protected by durable local state before invocation
- downstream reliable MQ emissions from consumers must be saved before the consumer is marked done
- direct downstream RabbitMQ emissions from consumers are forbidden unless explicitly classified best-effort

## Chain Classification

All RabbitMQ chains in Nexus must be classified during implementation.

### Reliable Required

Default category. Use outbox, reliable listener container, durable consumer idempotency, DLQ record, and replay job.

This applies to user-visible and state-changing chains.

### Reliable Producer Only

Allowed when the downstream consumer is already idempotent through its own durable table and does not need `ReliableMqConsumerRecordService`.

This category requires explicit justification.

Domain-specific outbox implementations such as content and user outboxes can be classified here only for their producer side. Their downstream consumers still need reliable consume classification unless already covered by another durable idempotency mechanism.

### Best-Effort

Allowed only for chains where losing one message cannot create long-lived business inconsistency or user-visible errors.

Best-effort chains must be explicitly annotated or documented. Silent direct `rabbitTemplate.convertAndSend(...)` is not allowed.

Best-effort classification must include the reason loss is acceptable and the expected user-visible impact of message loss. "Low probability" is not a valid reason.

## Chain Inventory Rules

The implementation plan must produce a chain inventory table before code conversion. Each RabbitMQ chain must have:

- producer class and method
- exchange and routing key
- payload type
- event id source
- consumer class and method
- listener container factory
- idempotency mechanism
- DLQ queue
- replay route
- chain classification
- allowed raw publish status

The inventory is complete only when every `@RabbitListener` and every `rabbitTemplate.convertAndSend(...)` call is either covered, explicitly exempted, or identified as non-RabbitMQ infrastructure.

## Testing Strategy

### Unit Tests

Add tests for the new aspects:

- producer aspect saves outbox record with exchange, routing key, payload, and event id
- producer aspect rejects blank event id
- consumer aspect skips duplicate done records
- consumer aspect marks done after success
- consumer aspect marks fail and rethrows after business failure
- DLQ aspect records original queue, exchange, routing key, payload type, event id, and error text
- annotated methods are invoked through Spring proxies
- self-invoked annotated methods are not used as reliable MQ entry points
- manual-ack consumers are not accidentally wrapped by the auto-ack consume aspect

### Chain Tests

For representative chains, add tests proving the intended wiring:

- comment created producer uses reliable publish
- recommend feedback consumer uses idempotent consume
- feed fanout dispatcher emits task messages through reliable publish
- one existing DLQ consumer writes replay record through the annotation path

### Static Regression Tests

Add a static or architecture test that scans RabbitMQ producers and rejects raw `rabbitTemplate.convertAndSend(...)` outside approved infrastructure classes and explicit best-effort classes.

Allowed raw publish locations initially include:

- `ReliableMqOutboxService`
- `ReliableMqReplayService`
- domain-specific outbox drain methods such as `ContentEventOutboxPort.tryPublishPending(...)` internals
- domain-specific outbox drain methods such as `UserEventOutboxPort.tryPublishPending(...)` internals
- test fixtures

Any other raw publish must be justified in the allowlist.

Add a second static or architecture test that scans `@RabbitListener` methods and verifies side-effecting listeners are either annotated with `@ReliableMqConsume`, covered by explicit manual-ack reliability code, or documented as best-effort.

## Rollout Plan

Implementation must land in small reviewable batches:

1. Add annotation types, aspects, AOP dependency, and unit tests.
2. Produce the full RabbitMQ chain inventory and classification table.
3. Convert producers that already call `ReliableMqOutboxService.save(...)`.
4. Convert side-effecting auto-ack consumers that already use `ReliableMqConsumerRecordService`.
5. Convert DLQ consumers that already use `ReliableMqDlqRecorder`.
6. Convert downstream publishes inside consumers by extracting producer beans or explicitly using the reliable outbox service.
7. Convert remaining raw RabbitMQ producers to reliable outbox or explicit best-effort.
8. Convert remaining auto-ack consumers to reliable consume or explicit best-effort.
9. Keep manual-ack consumers explicitly coded and add tests proving they meet the manual-ack reliability rules.
10. Add static regression tests and final chain inventory.

Each batch must keep the application compiling.

## Operational Expectations

Operators must be able to inspect:

- outbox backlog by status and `nextRetryAt`
- replay backlog by status and `nextRetryAt`
- consumer record failures by `consumerName`
- `FINAL_FAILED` records requiring manual intervention

This design does not require a new admin UI. SQL inspection and existing logs are sufficient for this remediation.

## Explicitly Rejected Interpretations

- "AOP means producers no longer need stable event ids" is rejected.
- "AOP means consumers can catch and log failures" is rejected.
- "DLQ recording alone is enough without replay route correctness" is rejected.
- "A raw publish is acceptable because it is inside a consumer" is rejected.
- "Manual ack consumers can be auto-converted by the first consume aspect" is rejected.
- "Existing domain outbox tables should be collapsed into the generic outbox as part of this remediation" is rejected.
- "A best-effort chain can be declared because the failure probability is low" is rejected.
- "An annotation on a method is sufficient even if Spring AOP cannot intercept the call" is rejected.

## Open Decisions Resolved

- Use AOP: yes, for repeated shell behavior only.
- Full remediation: yes, all RabbitMQ chains must be reviewed.
- Core reliability state remains in existing services and tables.
- Retry timing remains centralized in `ReliableMqPolicy`.
- Kafka counter messaging remains outside this RabbitMQ-focused change.
- Existing generic replay job remains the replay scheduler.
- First-version consume AOP excludes manual-ack consumers.
- Domain-specific content and user outbox tables are preserved.
