# Replace JD HotKey With Zhiguang Local Detector

## Context

Nexus currently uses `jd-hotkey-client` through `HotKeyStoreBridge`. The bridge can start the JD client directly or through an isolated helper process, and it requires external JD HotKey infrastructure such as etcd, worker, and dashboard configuration.

Zhiguang provides a simpler local hot key detector based on segmented sliding-window counters. The requested change is to replace the JD HotKey implementation completely with the Zhiguang-style detector while preserving Nexus repository behavior.

The current Nexus business usage is narrow:

- `ContentRepository` calls `HotKeyStoreBridge#isHotKey` to decide whether to use/update the local post cache and extend Redis TTL.
- `FeedCardRepository` calls `HotKeyStoreBridge#isHotKey` to extend hot feed-card Redis TTL.
- Tests mock `HotKeyStoreBridge`, so the business-facing API can remain stable.

## Scope Contract

This change is a hot key detector replacement only.

In scope:

- Replace the JD HotKey implementation used by Nexus runtime code.
- Remove JD HotKey runtime dependencies, helper process code, worker/dashboard Docker services, JD HotKey schema initialization, and backend `HOTKEY_*` environment variables.
- Preserve the existing content/feed cache architecture: Redis remains the source cache, Caffeine remains the L1 hot cache, and repositories keep using `HotKeyStoreBridge#isHotKey`.
- Preserve existing hot key names exactly: `post__{postId}` for content posts and `feed_card__{postId}` for feed cards.

Out of scope:

- No new distributed hot key service.
- No Redis-backed or database-backed hotness counters.
- No new dashboard, metrics subsystem, admin API, or manual hot key management UI.
- No changes to Redis cache key schemas, content/feed repository contracts, counter systems, recommendation systems, or Gorse/etcd usage unrelated to JD HotKey.
- No behavioral rewrite of L1 cache population, Redis TTL extension, single-flight loading, or content/feed read ordering.

Historical docs and archived plans may still mention JD HotKey or etcd. They are not implementation targets. Active runtime source, active compose/scripts, active profile YAML, and build files are implementation targets.

## Decision

Use a minimal adapter replacement:

- Keep `HotKeyStoreBridge` as the Nexus-facing bean and preserve `isHotKey(String key)`.
- Replace its internals with a Zhiguang-style in-process segmented sliding-window detector.
- Remove all JD HotKey startup, helper-process, classpath, worker/dashboard, schema initialization, environment variable, and Maven dependency paths.
- Keep existing repository call sites stable except for JD-specific log text.

This gives a complete implementation replacement with the smallest business-code blast radius.

## Configuration

`HotKeyProperties` remains bound to the `hotkey` prefix, but the semantics change from external JD client configuration to local detector configuration.

Supported properties:

- `hotkey.enabled`: disables detection when false. Default: `true`.
- `hotkey.windowSeconds`: total sliding-window duration. Default: `60`.
- `hotkey.segmentSeconds`: segment size used for rotation. Default: `10`.
- `hotkey.levelLow`: low hotness threshold. Default: `50`.
- `hotkey.levelMedium`: medium hotness threshold. Default: `200`.
- `hotkey.levelHigh`: high hotness threshold. Default: `500`.

Removed properties:

- `hotkey.appName`
- `hotkey.etcdServer`
- `hotkey.pushPeriodMs`
- `hotkey.mode`

Configuration rules:

- Profile YAML must not set removed JD properties.
- `application-dev.yml`, `application-wsl.yml`, `application-docker.yml`, and `application-real-it.yml` must either omit the new threshold fields and rely on defaults, or set the same local detector fields listed above.
- Existing `hotkey.enabled: false` in integration-test profile remains valid and means `isHotKey` always returns false.
- `HOTKEY_APP_NAME`, `HOTKEY_ETCD_SERVER`, and `HOTKEY_PUSH_PERIOD_MS` must be removed from active compose files because they no longer configure anything.

## Runtime Design

`HotKeyStoreBridge` becomes the local detector:

- Maintain per-key segmented counters in memory.
- Maintain a current segment pointer.
- Compute `segments = max(1, windowSeconds / max(1, segmentSeconds))`.
- `isHotKey(key)` returns false when disabled or key is blank.
- Otherwise `isHotKey(key)` records exactly one access in the current segment, computes the total heat for the key, and returns true when heat is at least `levelLow`.
- `level(key)` maps heat to `NONE`, `LOW`, `MEDIUM`, or `HIGH`.
- `rotate()` advances the current segment and clears that segment for all keys.
- `reset(key)` clears a key's counters.

The `isHotKey` method is intentionally a record-and-check operation, not a pure query. This preserves the current repository API without adding separate `record` calls into content/feed repositories.

The detector is intentionally process-local. It does not coordinate hotness across multiple Nexus instances. That matches the requested Zhiguang implementation and removes external infrastructure coupling.

Concurrency rules:

- Counter updates must be thread-safe enough for high-read repository paths.
- Approximate hotness is acceptable.
- Throwing, blocking on external resources, or serializing all reads through a coarse lock is not acceptable.
- The implementation may use JDK concurrency primitives that keep the same observable behavior.
- The implementation must not introduce Redis, MySQL, RabbitMQ, Kafka, etcd, or any network dependency for hotness tracking.

## Scheduling

`rotate()` must be scheduled from the local detector bean using the `hotkey.segmentSeconds` value with a default of 10 seconds.

Nexus already enables scheduling in `cn.nexus.Application`. Do not add a second application entry point or move scheduling ownership into repository classes.

## Error Handling

The old JD bridge could fail during external startup or helper HTTP calls. The new detector has no external startup. Repository safety wrappers must preserve cold-key fallback behavior:

- Exceptions from `isHotKey` must not break the content/feed main path.
- Warning messages must not mention `jd-hotkey`.
- `HotKeyClientInitializer` must be removed rather than repurposed. There is no startup handshake for the local detector.

## Active Runtime Cleanup

Java/build cleanup:

- Remove the `io.github.ck-jesse:jd-hotkey-client` dependency from `nexus-infrastructure`.
- Remove `HotKeyClientInitializer`.
- Remove `HotKeyBridgeServer`.
- Remove `hotkey-isolated-classpath.txt`.
- Remove all `com.jd.platform.hotkey` imports from Nexus production code.

Runtime environment cleanup:

- Remove `hotkey-worker` and `hotkey-dashboard` services from `project/docker-compose.middleware.yml`.
- Remove `project/docker/hotkey/Dockerfile` and `project/docker/hotkey/init/schema.sql`.
- Remove JD HotKey schema initialization from `project/docker/mysql/init-extra.sh`.
- Remove `HOTKEY_APP_NAME`, `HOTKEY_ETCD_SERVER`, and `HOTKEY_PUSH_PERIOD_MS` from `project/docker-compose.yml`.
- Remove JD HotKey worker/dashboard branching, `HOTKEY_PUBLIC_IP`, and `HOTKEY_SERVICES` logic from `project/scripts/up-wsl-middleware.sh`.
- Do not remove etcd, Zookeeper, or other middleware globally if another active service still depends on them; only remove JD HotKey-specific dependencies and startup logic.

## Testing

Tests must be written before implementation.

Core detector tests:

- Empty or disabled detector returns false and records no hot behavior.
- With thresholds `2/4/6`, repeated calls to `isHotKey("k")` become hot at the low threshold.
- `heat("k")` and `level("k")` report `LOW`, `MEDIUM`, and `HIGH` at the expected counts.
- Rotating through all segments naturally expires previous heat.
- Segment count handles invalid or small configuration defensively.
- Calling `level`, `heat`, `reset`, or `rotate` must not increment heat; only `isHotKey` records access.

Integration-facing tests:

- Existing `FeedCardRepositoryTest` and `ContentRepositoryTest` continue to mock `HotKeyStoreBridge`.
- Keep or add a repository test that proves hot feed cards still extend Redis TTL through `feed_card__{postId}`.
- Add or keep a repository test that proves hot content posts still use `post__{postId}` and do not require external JD startup.
- A dependency/configuration check must ensure there are no remaining references to JD HotKey classes, helper resources, `jd-hotkey-client`, hotkey worker/dashboard services, or `HOTKEY_*` JD environment variables in active runtime files.

Recommended verification commands:

- Maven test/build for `nexus-infrastructure`.
- Repository-wide search over active runtime files for `com.jd.platform.hotkey`, `jd-hotkey`, `hotkey-isolated-classpath`, `HotKeyBridgeServer`, `HotKeyClientInitializer`, `HOTKEY_APP_NAME`, and `HOTKEY_ETCD_SERVER`.

## Migration Impact

The replacement removes these JD-specific artifacts:

- `nexus-infrastructure` Maven dependency on `io.github.ck-jesse:jd-hotkey-client`.
- `HotKeyClientInitializer`.
- `HotKeyBridgeServer`.
- `hotkey-isolated-classpath.txt`.
- Profile configuration values for app name, etcd server, push period, and isolated/direct mode.
- JD HotKey worker/dashboard compose services and Docker build context.
- JD HotKey database/schema initialization.
- Backend environment variables that only configure the removed JD client.

No Nexus business database migration is required. No Redis key schema changes are required. Existing hot key names `post__{postId}` and `feed_card__{postId}` remain unchanged.

The old `hotkey_db` was only for JD HotKey dashboard/worker metadata. New deployments must not create or initialize it. Existing local databases may still contain it, but Nexus must no longer depend on it.

## Anti-Drift Rules

Implementation must not:

- Rename `HotKeyStoreBridge` or force content/feed repositories to depend on a new detector type.
- Add network calls, helper processes, JD worker compatibility code, or dashboard integration.
- Keep JD HotKey dependency or classes behind a feature flag.
- Change content/feed cache key names, Redis TTL base values, or L1 cache sizes as part of this change.
- Convert this into a distributed detector.
- Add broad refactors outside hotkey config, active runtime cleanup, and the two existing repository warning messages.

Implementation must:

- Preserve `HotKeyStoreBridge#isHotKey(String)` as the single business-facing method used by current repositories.
- Make `isHotKey` record one access before evaluating hotness.
- Keep disabled and blank-key behavior cold.
- Keep repository failure behavior fail-open to cold-key treatment.
- Prove removal by tests and source searches, not by assumption.

## Acceptance Criteria

- Nexus builds without `jd-hotkey-client`.
- No Nexus production code imports `com.jd.platform.hotkey`.
- Hot key behavior is implemented by local sliding-window counters.
- Existing repository behavior still uses `HotKeyStoreBridge#isHotKey`.
- Tests cover the local detector behavior and the existing repository hot-cache paths.
- Active compose/scripts/config no longer start or configure JD HotKey worker/dashboard/client paths.
- Searches over active runtime files show no JD HotKey artifacts except historical documentation that is explicitly out of scope.
