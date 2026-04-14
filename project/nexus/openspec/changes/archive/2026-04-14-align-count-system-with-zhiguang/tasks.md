## 1. Count Redis schema and storage primitives

- [x] 1.1 Add Count Redis schema definitions, slot mappings, key builders, and SDS codec utilities for object and user counters
- [x] 1.2 Add Redis helper operations for bitmap fact writes, aggregation bucket updates, snapshot reads/writes, rebuild locks, rate limits, and replay checkpoints
- [x] 1.3 Add focused unit tests for schema resolution, key generation, fixed-width slot encoding, and negative-value clamping

## 2. Object counter cutover

- [x] 2.1 Replace the current object counter adapter so `POST.like`, `COMMENT.like`, and `COMMENT.reply` read from Count Redis snapshots instead of legacy string counters or DB fallback paths
- [x] 2.2 Refactor like write paths to publish Count Redis aggregation work after effective bitmap state changes and keep comment hot-rank side effects consistent with the new counters
- [x] 2.3 Refactor root reply count handling to write Count Redis reply aggregation and snapshot state without introducing reply bitmap facts
- [x] 2.4 Add unit and integration tests covering single reads, batch reads, effective like toggles, reply deltas, and malformed snapshot rebuild

## 3. User counter cutover

- [x] 3.1 Replace the current user counter adapter so `following`, `follower`, and `like_received` read from Count Redis user snapshots
- [x] 3.2 Wire relation and like side-effect flows to update Count Redis user aggregation and snapshot state for supported counters
- [x] 3.3 Implement user snapshot rebuild from relation truth and add tests for missing or malformed user snapshots

## 4. Replay, rebuild, and operations

- [x] 4.1 Add Count Redis replay event models, repositories, and consumers that persist or consume replayable deltas without Kafka
- [x] 4.2 Add replay runners that resume from stored checkpoints and reapply supported object and user deltas into Count Redis
- [x] 4.3 Add read-time rebuild guards with locking, backoff, and rate limiting for object and user snapshots
- [x] 4.4 Add operational documentation for key contracts, accepted replay limitations, destructive cutover order, rollback boundaries, and legacy state cleanup

## 5. Verification and cutover cleanup

- [x] 5.1 Update integration tests so supported counting scenarios assert Count Redis snapshots rather than legacy counter keys
- [x] 5.2 Remove legacy counter reads, writes, fake-data paths, and obsolete adapters for the supported metrics once replacement tests pass
- [x] 5.3 Drop or retire legacy counting tables that are unused after the new path takes over, while preserving tables still needed outside the counting domain
- [x] 5.4 Clear obsolete Redis keys, aggregation buckets, replay checkpoints, and middleware backlog from the old counting model during the cutover window
- [x] 5.5 Run targeted Maven test suites for domain, infrastructure, trigger, and real integration counting paths and record the final pre-cleanup verification results
