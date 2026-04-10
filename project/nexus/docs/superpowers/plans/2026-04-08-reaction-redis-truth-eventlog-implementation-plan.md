# Reaction Redis-Truth Event Log Implementation Plan

## Goal

Refactor the reaction domain so that:

- Redis is the only online truth source.
- MySQL stores only append-only reaction event logs.
- RabbitMQ is the asynchronous handoff for event-log persistence.
- Redis recovery rebuilds from MySQL event log checkpoints.

## As-Built Architecture

### Online path

- `InteractionController.react()` -> `InteractionService.react()` -> `ReactionLikeService.applyReaction()`
- `ReactionLikeService` writes Redis first through `ReactionCachePort.applyAtomic()`
- Redis success defines API success
- if `delta != 0`, `ReactionLikeService` best-effort publishes:
  - reaction event-log message
  - post like/unlike message
  - comment like changed message
  - notification message
  - recommend feedback message when needed

### Async path

- `ReactionEventLogConsumer` consumes `reaction.event.log.queue`
- consumer appends rows into `interaction_reaction_event_log`
- duplicate `event_id` is treated as success

### Recovery path

- `ReactionRedisRecoveryRunner` replays MySQL event-log rows into Redis
- checkpoint keys are maintained in Redis by stream family

## Implemented File Changes

### Core write path

- Modified: `nexus-domain/src/main/java/cn/nexus/domain/social/service/ReactionLikeService.java`
- Modified: `nexus-domain/src/main/java/cn/nexus/domain/social/adapter/port/IReactionCachePort.java`
- Modified: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/ReactionCachePort.java`
- Added: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/ReactionEventLogMqPort.java`
- Added: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/ReactionLikeUnlikeMqPort.java`
- Added: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/ReactionCommentLikeChangedMqPort.java`
- Added: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/ReactionNotifyMqPort.java`
- Added: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/ReactionRecommendFeedbackMqPort.java`

### Event log persistence

- Added: `nexus-types/src/main/java/cn/nexus/types/event/interaction/ReactionEventLogMessage.java`
- Added: `nexus-trigger/src/main/java/cn/nexus/trigger/mq/config/ReactionEventLogMqConfig.java`
- Added: `nexus-trigger/src/main/java/cn/nexus/trigger/job/social/ReactionEventLogConsumer.java`
- Added: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/dao/social/IInteractionReactionEventLogDao.java`
- Added: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/dao/social/po/InteractionReactionEventLogPO.java`
- Added: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/ReactionEventLogRepository.java`
- Added: `nexus-infrastructure/src/main/resources/mapper/social/InteractionReactionEventLogMapper.xml`
- Added: `docs/migrations/20260408_01_reaction_event_log.sql`

### Recovery

- Added/Modified: `nexus-trigger/src/main/java/cn/nexus/trigger/job/social/ReactionRedisRecoveryRunner.java`

### Removed

- Deleted: `nexus-trigger/src/main/java/cn/nexus/trigger/job/social/ReactionEventLogStreamWorker.java`

## Required Semantics

### Reaction apply

- `delta = 0` means no effective transition and no event-log publish
- `delta != 0` means Redis changed and an event-log message should be published best-effort
- request result is not gated by MySQL append success

### Event log consumer

- required fields must exist
- append result `inserted` means success
- append result `duplicate` means success
- any other append result is failure

### Comment like changed consumer

- must not increment comment-like Redis truth again
- may read Redis truth and update derived MySQL/comment-hot-rank state

## Tests Updated Or Added

### Unit/component tests

- `ReactionLikeServiceTest`
- `InteractionServiceTest`
- `ReactionCachePortTest`
- `ReactionEventLogConsumerTest`
- `ReactionRedisRecoveryRunnerTest`
- `ReactionEventLogRepositoryTest`
- `CommentLikeChangedConsumerTest`

### Real integration tests

- `ReactionHttpRealIntegrationTest`
- `HighConcurrencyConsistencyAuditIntegrationTest`

## Validation Runbook

### Fast verification

```bash
mvn -pl nexus-trigger -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=CommentLikeChangedConsumerTest test
mvn -pl nexus-domain,nexus-trigger -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=ReactionLikeServiceTest,InteractionServiceTest,ReactionRedisRecoveryRunnerTest,ReactionEventLogConsumerTest test
mvn -pl nexus-infrastructure -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=ReactionCachePortTest,ReactionEventLogRepositoryTest test
```

### Real integration verification

```bash
mvn -pl nexus-app -am -Plocal-real-it -Dskip.real.it.tests=false -Dsurefire.failIfNoSpecifiedTests=false -Dtest=ReactionHttpRealIntegrationTest test
mvn -pl nexus-app -am -Plocal-real-it -Dskip.real.it.tests=false -Dsurefire.failIfNoSpecifiedTests=false -Dtest=HighConcurrencyConsistencyAuditIntegrationTest test
```

## Notes From Implementation

- Real integration tests must run without another long-lived local app instance competing for RabbitMQ consumers.
- A locally running `spring-boot:run` process can cause false duplicate-consumption behavior in reaction tests.
- `reaction/likers` is intentionally down and must return business code `0002`.

## Follow-Up Cleanup

Remaining optional cleanup after this plan:

- remove or migrate any residual docs that still describe Redis Stream handoff
- add destructive migration steps for dropping obsolete reaction truth tables if not already handled elsewhere
- run broader review or integration coverage if needed
