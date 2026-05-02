# Nexus Reliable MQ AOP Full Remediation Design

## Status

Approved direction for implementation planning.

## Decision

Nexus will complete a full RabbitMQ reliability remediation by standardizing every MQ chain on the existing reliable messaging infrastructure:

- `ReliableMqOutboxService`
- `ReliableMqReplayService`
- `ReliableMqConsumerRecordService`
- `ReliableMqDlqRecorder`
- `reliableMqListenerContainerFactory`

The remediation will add a thin annotation and AOP layer to remove repeated boilerplate. The AOP layer will standardize shell behavior only. It will not own business routing decisions, retry policy decisions, or business state transitions.

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
- Preserve existing queue, exchange, and routing contracts unless a specific chain already uses an incorrect route.
- Keep retry and replay timing centralized in `ReliableMqPolicy`.
- Reduce repeated `save/start/markDone/markFail/record` code through annotations and aspects.
- Keep business code readable: consumer methods should show business work, while annotations show MQ reliability metadata.

## Non-Goals

- Replacing RabbitMQ with Kafka or another broker.
- Building a generic event bus abstraction above RabbitMQ.
- Moving business state-machine behavior into aspects.
- Making all optional cache invalidation or analytics signals transactional if they are explicitly classified as best-effort.
- Changing existing retry backoff constants outside `ReliableMqPolicy`.
- Removing existing outbox tables, replay tables, or consumer record tables.

## Current Project Context

Nexus already has the core reliable MQ pieces:

- `ReliableMqOutboxService` stores producer messages and publishes ready records.
- `ReliableMqReplayService` stores failed DLQ messages and replays ready records.
- `ReliableMqConsumerRecordService` stores `eventId + consumerName` idempotency state.
- `ReliableMqDlqRecorder` adapts DLQ messages into replay records.
- `ReliableMqListenerContainerConfig` applies stateless retry and sends exhausted failures to DLQ.

Many consumers already use `reliableMqListenerContainerFactory`, and many DLQ consumers already call `ReliableMqDlqRecorder`. The remaining issue is inconsistent adoption and duplicated wiring code.

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

## Producer Design

### Annotation

`@ReliableMqPublish` will be applied to producer methods that currently exist to publish one MQ event.

Conceptual shape:

```java
@ReliableMqPublish(
        exchange = "social.interaction",
        routingKey = "comment.created",
        eventId = "#event.eventId",
        payload = "#event")
public void publish(CommentCreatedEvent event) {
}
```

The method body may be empty or may validate arguments before the aspect runs. The aspect stores the payload with `ReliableMqOutboxService.save(...)`.

### Transaction Boundary

If the producer method runs inside an active Spring transaction, outbox insertion participates in that transaction.

If no active transaction exists, the outbox insert commits immediately. This preserves reliability for scheduled producers and infrastructure producers that are not part of a domain transaction.

The aspect may register an after-commit hook to opportunistically call the outbox publisher, but durable retry does not depend on that hook. `ReliableMqOutboxRetryJob` remains the final delivery driver.

### Event Id

Every reliable producer must provide a non-blank stable event id.

Allowed sources:

- payload field such as `event.eventId`
- deterministic composite id for derived slice messages, such as `parentEventId:offset:pageSize`
- explicit method argument

Missing event id is a programming error. The aspect must throw and prevent outbox insertion.

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

## Consumer Design

### Annotation

`@ReliableMqConsume` will be applied to side-effecting `@RabbitListener` methods.

Conceptual shape:

```java
@RabbitListener(queues = FeedRecommendFeedbackMqConfig.QUEUE,
        containerFactory = "reliableMqListenerContainerFactory")
@ReliableMqConsume(
        consumerName = "FeedRecommendFeedbackConsumer",
        eventId = "#event.eventId",
        payload = "#event")
public void onMessage(RecommendFeedbackEvent event) {
    recommendationPort.insertFeedback(...);
}
```

The aspect handles:

- missing event id classification
- `start(eventId, consumerName, payloadJson)`
- duplicate done short-circuit
- business method execution
- `markDone(...)`
- `markFail(...)`
- rethrowing retryable failures so the Rabbit listener container can retry and eventually dead-letter

### Validation

Validation stays in the consumer method or a small helper called by that method.

Invalid messages that can never succeed should throw an unretryable exception. The listener container must not keep retrying malformed payloads. The exact exception class will be standardized during implementation so all consumers use one permanent-failure signal.

### Idempotency Rule

Every consumer that writes a database row, changes Redis state, emits a downstream MQ message, calls an external dependency, or changes user-visible state must use `@ReliableMqConsume`.

Consumers that only log, metrics-only consumers, or pure in-memory cache listeners may be explicitly classified as best-effort. That classification must be visible in code and in the implementation plan.

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

Conceptual shape:

```java
@RabbitListener(queues = FeedFanoutConfig.DLQ_POST_PUBLISHED)
@ReliableMqDlq(
        consumerName = "FeedFanoutDispatcherConsumer",
        originalQueue = FeedFanoutConfig.QUEUE,
        originalExchange = FeedFanoutConfig.EXCHANGE,
        originalRoutingKey = FeedFanoutConfig.POST_PUBLISHED_ROUTING_KEY,
        fallbackPayloadType = "cn.nexus.types.event.PostPublishedEvent")
public void onMessage(Message message) {
}
```

The aspect delegates to `ReliableMqDlqRecorder.record(...)`.

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

## AOP Boundary Rules

The aspect layer may do:

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

- missing replay event id is a DLQ recording failure and should be logged loudly
- duplicate replay records are ignored by durable uniqueness
- replay exhaustion results in `FINAL_FAILED`

## Chain Classification

All RabbitMQ chains in Nexus must be classified during implementation.

### Reliable Required

Default category. Use outbox, reliable listener container, durable consumer idempotency, DLQ record, and replay job.

This applies to user-visible and state-changing chains.

### Reliable Producer Only

Allowed when the downstream consumer is already idempotent through its own durable table and does not need `ReliableMqConsumerRecordService`.

This category requires explicit justification.

### Best-Effort

Allowed only for chains where losing one message cannot create long-lived business inconsistency or user-visible errors.

Best-effort chains must be explicitly annotated or documented. Silent direct `rabbitTemplate.convertAndSend(...)` is not allowed.

## Testing Strategy

### Unit Tests

Add tests for the new aspects:

- producer aspect saves outbox record with exchange, routing key, payload, and event id
- producer aspect rejects blank event id
- consumer aspect skips duplicate done records
- consumer aspect marks done after success
- consumer aspect marks fail and rethrows after business failure
- DLQ aspect records original queue, exchange, routing key, payload type, event id, and error text

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
- test fixtures

Any other raw publish must be justified in the allowlist.

## Rollout Plan

Implementation should land in small reviewable batches:

1. Add annotation types, aspects, AOP dependency, and unit tests.
2. Convert producers that already call `ReliableMqOutboxService.save(...)`.
3. Convert side-effecting consumers that already use `ReliableMqConsumerRecordService`.
4. Convert DLQ consumers that already use `ReliableMqDlqRecorder`.
5. Convert remaining raw RabbitMQ producers to reliable outbox or explicit best-effort.
6. Convert remaining consumers to reliable consume or explicit best-effort.
7. Add static regression tests and final chain inventory.

Each batch must keep the application compiling.

## Operational Expectations

Operators should be able to inspect:

- outbox backlog by status and `nextRetryAt`
- replay backlog by status and `nextRetryAt`
- consumer record failures by `consumerName`
- `FINAL_FAILED` records requiring manual intervention

This design does not require a new admin UI. SQL inspection and existing logs are sufficient for this remediation.

## Open Decisions Resolved

- Use AOP: yes, for repeated shell behavior only.
- Full remediation: yes, all RabbitMQ chains must be reviewed.
- Core reliability state remains in existing services and tables.
- Retry timing remains centralized in `ReliableMqPolicy`.
- Kafka counter messaging remains outside this RabbitMQ-focused change.
