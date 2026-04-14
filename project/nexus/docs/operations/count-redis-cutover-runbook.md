# Count Redis Cutover Runbook

## Purpose

This runbook defines the operational contract for the `align-count-system-with-zhiguang` cutover. It documents the Count Redis key families that become online truth, the replay guarantees the system does and does not provide, the required destructive cutover order, the rollback boundary before and after cleanup, and the legacy state that must be removed once verification passes.

The cutover scope is limited to these supported counters:

- object snapshots: `POST.like`, `COMMENT.like`, `COMMENT.reply`
- user snapshots: `USER.following`, `USER.follower`, `USER.like_received`
- reserved user slots: `USER.post`, `USER.favorite_received`

## Key Contracts

### Online truth keys

Count Redis becomes the only online counter source for the supported metrics above. After cutover, application reads and writes must treat the following key families as canonical:

| Contract | Key family | Notes |
| --- | --- | --- |
| object fact bitmap | `count:fact:object:{type}:{targetId}:{bucket}` | used only for toggle truth on `POST.like` and `COMMENT.like` |
| object aggregation bucket | `count:agg:object:{type}:{targetId}` | asynchronous delta staging before snapshot merge |
| object snapshot | `count:snapshot:object:{type}:{targetId}` | SDS snapshot read path for object counters |
| user aggregation bucket | `count:agg:user:{userId}` | asynchronous delta staging before snapshot merge |
| user snapshot | `count:snapshot:user:{userId}` | SDS snapshot read path for user counters |
| rebuild lock | `count:rebuild-lock:{scope}:{id}` | prevents concurrent rebuild storms |
| rebuild rate limit | `count:rate-limit:{scope}:{id}` | guards repeated malformed or missing snapshot repair |
| replay checkpoint | `count:replay:checkpoint:{stream}` | stores replay progress outside snapshots |

### Field contracts

The field layout is fixed for the current schema version.

| Snapshot family | Fields |
| --- | --- |
| `post` object snapshot | `like` |
| `comment` object snapshot | `like`, `reply` |
| user snapshot | `following`, `follower`, `post`, `like_received`, `favorite_received` |

Operational implications:

- `USER.post` and `USER.favorite_received` remain reserved slots and must stay writable as zero-valued fields even though no hot-path producer owns them yet.
- `COMMENT.reply` is a snapshot-only aggregate. It has no per-user fact bitmap and rebuilds from database truth when a snapshot is missing or malformed.
- `POST.like` and `COMMENT.like` rebuild from Count Redis fact bitmap cardinality and must clear overlapping object aggregation bucket fields before the repaired snapshot returns to service.
- `USER.following` and `USER.follower` rebuild from relation truth. `USER.like_received` is replayed or aggregated only from effective like-side effects and is not reconstructed by the relation rebuild path.

## Accepted Replay Limitations

The design is intentionally Redis-first and does not treat replay durability as part of the synchronous success contract.

Accepted limitations:

1. A user-facing write is successful once the Count Redis fact or snapshot mutation succeeds on the online path.
2. RabbitMQ fanout and MySQL replay-log persistence can fail after that success without changing the original API result.
3. If Count Redis survives, replay resumes from the stored replay checkpoint for the relevant stream.
4. If Count Redis is lost, replay checkpoints stored in Count Redis are lost as well. Replay must restart from checkpoint `0` on a fresh deployment, followed by full recomputation for counters that do not have complete replay coverage.
5. `COMMENT.reply`, `USER.following`, and `USER.follower` still require full recomputation from database truth during disaster rebuild. They are not fully restorable from incremental replay logs alone.
6. `USER.like_received` depends on effective like delta production. If Redis-first writes succeeded but the replay log was never persisted, a cross-Redis disaster rebuild can miss some historical deltas until a separate reconciliation job is introduced.

Operational stance:

- Treat replay as repair tooling, not a linearizable ledger.
- Treat malformed or missing snapshots as self-healable in-place when the truth source still exists.
- Do not advertise cross-Redis disaster replay completeness for the supported metrics in this version.

## Destructive Cutover Order

Do not reorder the cutover steps below. Cleanup is intentionally last because it removes the fallback path.

1. Deploy application code that understands the final Count Redis key contracts, rebuild guards, replay repositories, and repair jobs.
2. Confirm Count Redis is reachable, empty or intentionally prepared, and running with the expected persistence mode.
3. Run targeted verification suites for domain, infrastructure, trigger, and integration counting paths before switching cleanup logic on.
4. Switch supported reads and writes to Count Redis-backed adapters while keeping legacy data untouched but no longer authoritative.
5. Validate live reads for supported object and user counters from Count Redis snapshots and validate repair jobs can rebuild intentionally damaged snapshots.
6. Pause or drain legacy counting middleware that can still emit old-model messages before destructive cleanup starts.
7. Remove legacy counter reads, writes, fake-data paths, and obsolete adapters from the application codebase.
8. Drop or retire legacy counting tables only after confirming no remaining workflow still depends on them.
9. Delete obsolete Redis keys, old aggregation buckets, outdated replay checkpoints, and drained middleware backlog from the previous counting model.
10. Resume normal operations with Count Redis as the only online truth for supported counters.

## Rollback Boundaries

Rollback behavior changes once destructive cleanup starts.

### Before destructive cleanup

Rollback is code-based:

- redeploy the previous application version
- point reads and writes back to the legacy counter path
- stop Count Redis-specific replay or repair jobs if they are producing noise

This phase assumes legacy keys, tables, and middleware state are still present.

### After destructive cleanup

Rollback is no longer a simple deployment reversal.

After any of the following actions complete, the cutover crosses the irreversible boundary:

- legacy Redis key deletion
- legacy replay checkpoint deletion
- legacy counting table drop or retirement that removes old truth data
- middleware backlog purge for old counting semantics

After that boundary:

- old code can be redeployed only as a binary rollback, not as a full data rollback
- restoring the old counting path requires rebuilding state rather than reusing preserved fake data
- Count Redis remains the practical recovery base for supported metrics unless a separate bootstrap or restoration procedure is prepared in advance

## Legacy State Cleanup Checklist

The following state belongs to the pre-cutover counting model and should be removed during the cleanup window once verification passes.

### Redis cleanup

- legacy object counter keys under `counter:object:*`
- legacy user counter keys under `counter:user:*`
- direct reaction count keys under `interact:reaction:cnt:*`
- legacy reaction bitmap keys under `interact:reaction:bm:*`
- obsolete aggregation buckets created by pre-final Count Redis experiments
- replay checkpoints that belong to retired streams or pre-final schemas
- rebuild lock and rate-limit keys created by abandoned test environments before go-live validation

Suggested cleanup commands during the cutover window (run against Count Redis/business Redis as appropriate):

```bash
# legacy counter keyspaces
redis-cli --scan --pattern 'counter:object:*' | xargs -r redis-cli DEL
redis-cli --scan --pattern 'counter:user:*' | xargs -r redis-cli DEL
redis-cli --scan --pattern 'interact:reaction:cnt:*' | xargs -r redis-cli DEL
redis-cli --scan --pattern 'interact:reaction:bm:*' | xargs -r redis-cli DEL
redis-cli --scan --pattern 'interact:reaction:recovery:cp:*' | xargs -r redis-cli DEL

# obsolete test/legacy count redis artifacts
redis-cli --scan --pattern 'count:agg:*' | xargs -r redis-cli DEL
redis-cli --scan --pattern 'count:replay:checkpoint:*' | xargs -r redis-cli DEL
redis-cli --scan --pattern 'count:rebuild-lock:*' | xargs -r redis-cli DEL
redis-cli --scan --pattern 'count:rate-limit:*' | xargs -r redis-cli DEL
```

### Database cleanup

- legacy counting tables that are no longer referenced after the final adapters and tests are in place
- fake-data or bootstrap-only rows that existed only to support the old counting model
- obsolete replay or repair records that target retired stream contracts once the final replay jobs have consumed or superseded them

Retired counting tables in this cutover (drop migration already provided by `docs/migrations/20260409_01_drop_legacy_reaction_tables.sql`):

- `interaction_reaction_count_delta_inbox`
- `interaction_reaction_count`
- `interaction_reaction`

Final counting-domain MySQL tables that must be preserved:

- `interaction_reaction_event_log` (replay/audit ledger)
- `user_counter_repair_outbox` (relation counter repair workflow)

### Middleware cleanup

- queued messages encoded with legacy counting semantics
- dead-letter backlog that would replay deltas into removed key families
- retry backlog from old consumers that no longer exist in the final topology

RabbitMQ cleanup checklist for counting-related queues:

- drain or purge `like.unlike.count.queue` before switching to final semantics
- purge obsolete DLQ messages that reference retired counter keys/payload contracts
- verify no active consumer still binds to retired counting routing keys before purge

## Validation Expectations Before Cleanup

Do not run legacy cleanup until all of the following are true:

- targeted Maven counting suites are green for the final Count Redis path
- supported object and user reads are confirmed against Count Redis snapshots, not legacy keys
- replay runners resume from stored checkpoints in the surviving-Redis case
- rebuild guards are verified for missing or malformed object and user snapshots
- relation repair and like side-effect consumers are producing the expected Count Redis updates

If any validation item fails, stop before cleanup. The safe action is to keep legacy state intact until the mismatch is explained.

### 2026-04-14 Pre-Cleanup Verification Record

Legacy table retirement evidence (`5.3`):

- Drop migration is present: `docs/migrations/20260409_01_drop_legacy_reaction_tables.sql`.
- Runtime code scan over `nexus-app`, `nexus-domain`, `nexus-infrastructure`, `nexus-trigger`, `nexus-api`, `nexus-types` found no active references to `interaction_reaction_count_delta_inbox`, `interaction_reaction_count`, or `interaction_reaction`.
- Preservation set is explicit in this runbook: keep `interaction_reaction_event_log` and `user_counter_repair_outbox`.

Legacy state cleanup scope evidence (`5.4`):

- Redis key cleanup commands are listed for legacy keyspaces (`counter:*`, `interact:reaction:*`) and old Count Redis artifacts (`count:agg:*`, `count:replay:checkpoint:*`, `count:rebuild-lock:*`, `count:rate-limit:*`).
- Middleware cleanup checklist is defined for `like.unlike.count.queue` plus obsolete DLQ/backlog purging.

Targeted Maven verification evidence (`5.5`):

- `2026-04-14`: `mvn -pl nexus-domain -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=ReactionLikeServiceTest test` -> PASS (8 tests).
- `2026-04-14`: `mvn -pl nexus-infrastructure -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=ReactionCachePortTest,ObjectCounterPortTest,UserCounterPortTest test` -> PASS (24 tests).
- `2026-04-14`: `mvn -pl nexus-trigger -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=CommentLikeChangedConsumerTest,SnapshotPostLikeCountAggregateStrategyTest,ReactionRedisRecoveryRunnerTest test` -> PASS (8 tests).
- `2026-04-14`: `mvn -pl nexus-app -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=ReactionHttpRealIntegrationTest#postLike_highConcurrencySmoke_shouldRemainConsistent test` -> FAIL (awaitility timeout in `ReactionHttpRealIntegrationTest:311`, expected `1L`, observed `24L` after 20 seconds).

Cutover decision for this run:

- Keep destructive cleanup gated. Per this runbook, do not execute irreversible cleanup in environments where the real integration counting assertion is still unstable.
