## Why

Class 2 user counters currently use `rebuildAllCounters(userId)` as the normal RabbitMQ projection path for follow, unfollow, publish, unpublish, and delete events. Each rebuild executes 3 MySQL COUNT queries and enumerates all author posts for `like_received` summation. That is O(user posts) per event, so hot users or projection backlogs can materially reduce counter-system QPS.

## What Changes

- **BREAKING**: Change Class 2 projection from event-time full rebuilds to O(1) Redis SDS atomic increments for `following`, `follower`, and `post`, using the existing `UserCounterService.incrementXxx()` methods.
- Keep `rebuildAllCounters(userId)` as a cold repair path for missing, malformed, drifted, replayed, or operator-requested snapshots, not as the normal processor path.
- Split heavy best-effort `like_received` rebuild work from the Class 2 repair path, so Class 1 derived display values do not dominate Class 2 recovery cost.
- Add a lightweight MySQL `post_counter_projection` state table so publish/unpublish/delete events apply only real published-state edges and reject stale post events by monotonic `relationEventId`.
- Follow/unfollow use existing `saveFollowerIfAbsent` / `deleteFollowerIfPresent` return values as edge detectors for Redis INCRBY calls.
- No delta outbox, rebuild request, or background worker tables. No new async workers.
- Treat the change as destructive: no compatibility migration, legacy projection behavior, or old counter snapshots need to be preserved.

## Capabilities

### Modified Capabilities

- `count-user-state-and-snapshot`: Class 2 user counters shall be maintained by synchronous Redis atomic increments on normal events, with post projection state tracking for published-state edges.
- `count-rebuild-and-replay`: User-counter rebuilds shall be repair-only and separated from normal projection throughput. Class 2 repair shall not synchronously recompute `like_received`.

## Impact

- Affected code:
  - `nexus-domain/src/main/java/cn/nexus/domain/social/service/RelationCounterProjectionProcessor.java`
  - `nexus-domain/src/main/java/cn/nexus/domain/counter/adapter/service/IUserCounterService.java`
  - `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/counter/service/UserCounterService.java`
  - relation/post projection tests in `nexus-domain`, `nexus-infrastructure`, and `nexus-trigger`
- New persistence:
  - A lightweight MySQL `post_counter_projection` table keyed by `post_id` (the only new table)
- No new background workers, no delta outbox, no rebuild request table
- Public APIs stay compatible; internal projection and rebuild behavior changes.
