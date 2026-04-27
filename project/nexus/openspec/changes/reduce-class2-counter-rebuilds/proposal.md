## Why

Class 2 user counters currently use `rebuildAllCounters(userId)` as the normal RabbitMQ projection path for follow, unfollow, publish, unpublish, and delete events. That makes event throughput depend on repeated MySQL aggregate queries and per-author post scans, so hot users or projection backlogs can materially reduce counter-system QPS.

## What Changes

- **BREAKING**: Change Class 2 projection semantics from event-time full rebuilds to idempotent incremental projection for `following`, `follower`, and `post`, with normal processors writing durable MySQL counter-delta outbox rows instead of synchronously mutating Redis.
- Keep `rebuildAllCounters(userId)` as a repair path for missing, malformed, drifted, replayed, or operator-requested snapshots, not as the normal processor path.
- Add MySQL-backed coalesced dirty-user rebuild scheduling so repeated repair requests for the same user collapse into a bounded durable repair.
- Split heavy best-effort `like_received` rebuild work from the Class 2 repair path, so Class 1 derived display values do not dominate Class 2 recovery cost.
- Add a MySQL `post_counter_projection` state table so publish/unpublish/delete events apply only real published-state edges, reject stale post events by monotonic `relationEventId`, and rely on the business invariant that a post's author does not change.
- Add a MySQL `user_counter_delta_outbox` table so Class 2 projection-state changes and Redis counter mutations are separated by a durable eventually-consistent boundary.
- Treat the change as destructive: no compatibility migration, legacy projection behavior, or old counter snapshots need to be preserved.

## Capabilities

### New Capabilities

### Modified Capabilities

- `count-user-state-and-snapshot`: Class 2 user counters shall be maintained by idempotent incremental projection on normal events, with post projection state tracking for published-state edges.
- `count-rebuild-and-replay`: User-counter rebuilds shall be repair-only, guarded/coalesced, and separated from normal projection throughput.

## Impact

- Affected code:
  - `nexus-domain/src/main/java/cn/nexus/domain/social/service/RelationCounterProjectionProcessor.java`
  - `nexus-domain/src/main/java/cn/nexus/domain/counter/adapter/service/IUserCounterService.java`
  - `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/counter/service/UserCounterService.java`
  - relation/post projection tests in `nexus-domain`, `nexus-infrastructure`, and `nexus-trigger`
- Likely new or changed persistence:
  - A lightweight MySQL `post_counter_projection` table keyed by `post_id`
  - A MySQL `user_counter_rebuild_request` table keyed by `user_id`
  - A MySQL `user_counter_delta_outbox` table with idempotency key `(source_event_id, counter_user_id, counter_type)`
- Public APIs stay compatible; internal projection and rebuild behavior changes.
