## Why

Nexus currently splits counting responsibility across the reaction cache, ad-hoc counter facades, and database fallback paths. That design works for single-metric likes, but it does not provide zhiguang-style compressed multi-dimensional counters, explicit snapshot rebuild, or a single Count Redis model for object and user counters.

This change replaces the current counting path with a dedicated Count Redis design that matches zhiguang's core model without introducing Kafka. It keeps Redis-first write latency while standardizing how counters, state, rebuild, and replay are handled.

## What Changes

- **BREAKING** Replace the current object counter facade and reaction count cache usage with a unified Count Redis implementation for `POST.like`, `COMMENT.like`, and `COMMENT.reply`.
- **BREAKING** Replace the current user counter cache with Count Redis user snapshots for `USER.following`, `USER.follower`, and `USER.like_received`, while keeping reserved slots for `USER.post` and `USER.favorite_received`.
- Add zhiguang-style Count Redis data structures: bitmap fact keys for toggle state, Redis hash aggregation buckets, fixed-slot SDS counter snapshots, rebuild locks, and replay checkpoints.
- Add read-time rebuild and bad-data self-healing when Count Redis snapshots are missing or malformed.
- Add replay support from MySQL event logs and asynchronous delta fanout through the existing RabbitMQ stack, without making Kafka a dependency.
- Remove dependency on legacy `counter:object:*`, `counter:user:*`, and direct `interact:reaction:cnt:*` counter reads as the primary counter source after cutover.

## Capabilities

### New Capabilities
- `count-object-state-and-snapshot`: Maintain zhiguang-style object counter state and snapshots for `POST.like`, `COMMENT.like`, and `COMMENT.reply`.
- `count-user-state-and-snapshot`: Maintain zhiguang-style user counter snapshots for `USER.following`, `USER.follower`, and `USER.like_received`.
- `count-rebuild-and-replay`: Rebuild corrupted snapshots from truth sources and replay incremental logs into Count Redis without Kafka.

### Modified Capabilities

None.

## Impact

- Affected code: `nexus-domain` social services, `nexus-infrastructure` counter/social adapters, `nexus-trigger` MQ consumers and recovery jobs, Redis key contracts, MySQL event-log repositories, integration tests, and counting documentation.
- APIs: domain counter ports keep their current method shapes, but their backing semantics move to Count Redis snapshots and rebuild logic.
- Systems: Count Redis becomes the single online counter store; RabbitMQ remains the asynchronous fanout mechanism; MySQL event logs remain the replay and audit source.
