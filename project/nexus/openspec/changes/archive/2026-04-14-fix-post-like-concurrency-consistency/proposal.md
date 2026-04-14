## Why

`ReactionHttpRealIntegrationTest#postLike_highConcurrencySmoke_shouldRemainConsistent` currently fails under concurrent repeated `ADD LIKE` from the same user on the same post. The expected final like count is `1`, but observed snapshot counts can inflate (`9`, `24`), which breaks the idempotent like contract and weakens counting correctness under load.

## What Changes

- Ensure concurrent repeated `ADD LIKE` operations for the same `(userId, targetId, targetType=POST, reactionType=LIKE)` remain idempotent on final count.
- Align Count Redis fact state updates and snapshot aggregation so effective delta is applied exactly once for duplicate-like concurrency races.
- Add deterministic concurrency-focused verification in integration paths for post-like consistency, including snapshot and event-log assertions.
- Clarify cutover verification expectations so destructive cleanup remains gated when real integration consistency checks are unstable.

## Capabilities

### New Capabilities
- `post-like-concurrency-consistency`: Guarantee idempotent final count semantics for concurrent repeated post-like requests from the same user, with aligned Count Redis fact/snapshot behavior and verification expectations.

### Modified Capabilities
- None.

## Impact

- Affected code: `nexus-domain` reaction like service flow, `nexus-infrastructure` reaction/count Redis adapter behavior, and `nexus-app` real integration tests for high-concurrency like scenarios.
- Affected systems: Count Redis fact bitmap and snapshot aggregation path for `POST.like`; RabbitMQ/event-log verification path touched by assertions but not redefined as synchronous success criterion.
- APIs: No endpoint contract changes expected; behavior correction focuses on idempotency and final consistency under concurrency.
