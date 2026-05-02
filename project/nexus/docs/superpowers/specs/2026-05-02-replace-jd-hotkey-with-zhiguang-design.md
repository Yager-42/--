# Replace JD HotKey With Zhiguang Local Detector

## Context

Nexus currently uses `jd-hotkey-client` through `HotKeyStoreBridge`. The bridge can start the JD client directly or through an isolated helper process, and it requires external JD HotKey infrastructure such as etcd, worker, and dashboard configuration.

Zhiguang provides a simpler local hot key detector based on segmented sliding-window counters. The requested change is to replace the JD HotKey implementation completely with the Zhiguang-style detector while preserving Nexus repository behavior.

The current Nexus business usage is narrow:

- `ContentRepository` calls `HotKeyStoreBridge#isHotKey` to decide whether to use/update the local post cache and extend Redis TTL.
- `FeedCardRepository` calls `HotKeyStoreBridge#isHotKey` to extend hot feed-card Redis TTL.
- Tests mock `HotKeyStoreBridge`, so the business-facing API can remain stable.

## Decision

Use a minimal adapter replacement:

- Keep `HotKeyStoreBridge` as the Nexus-facing bean and preserve `isHotKey(String key)`.
- Replace its internals with a Zhiguang-style in-process segmented sliding-window detector.
- Remove all JD HotKey startup, helper-process, classpath, etcd, and Maven dependency paths.
- Keep existing repository call sites stable except for log text if needed.

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

Existing profile YAML files should be updated so local/dev/docker/wsl profiles no longer imply a JD HotKey external dependency.

## Runtime Design

`HotKeyStoreBridge` becomes the local detector:

- Maintain `ConcurrentHashMap<String, int[]> counters`.
- Maintain an `AtomicInteger current` segment pointer.
- Compute `segments = max(1, windowSeconds / max(1, segmentSeconds))`.
- `isHotKey(key)` returns false when disabled or key is blank.
- Otherwise `isHotKey(key)` records the access in the current segment, computes the total heat for the key, and returns true when heat is at least `levelLow`.
- `level(key)` maps heat to `NONE`, `LOW`, `MEDIUM`, or `HIGH`.
- `rotate()` advances the current segment and clears that segment for all keys.
- `reset(key)` clears a key's counters.

The detector is intentionally process-local. It does not coordinate hotness across multiple Nexus instances. That matches the requested Zhiguang implementation and removes external infrastructure coupling.

## Scheduling

`rotate()` should be scheduled with:

```java
@Scheduled(fixedRateString = "${hotkey.segment-seconds:${hotkey.segmentSeconds:10}}000")
```

The camel-case property is the canonical Nexus property. The hyphenated fallback helps Spring placeholder resolution if operators use kebab-case in YAML.

Nexus already uses scheduled components elsewhere; if scheduling is not enabled globally, implementation must add or reuse the existing scheduling enablement in the application/configuration layer.

## Error Handling

The old JD bridge could fail during external startup or helper HTTP calls. The new detector has no external startup. Repository safety wrappers should remain harmless:

- Exceptions from `isHotKey` should not break the content/feed main path.
- Warning messages should no longer mention `jd-hotkey`.

## Testing

Tests should be written before implementation.

Core detector tests:

- Empty or disabled detector returns false and records no hot behavior.
- With thresholds `2/4/6`, repeated calls to `isHotKey("k")` become hot at the low threshold.
- `heat("k")` and `level("k")` report `LOW`, `MEDIUM`, and `HIGH` at the expected counts.
- Rotating through all segments naturally expires previous heat.
- Segment count handles invalid or small configuration defensively.

Integration-facing tests:

- Existing `FeedCardRepositoryTest` and `ContentRepositoryTest` continue to mock `HotKeyStoreBridge`.
- A dependency/configuration check should ensure there are no remaining references to JD HotKey classes, helper resources, or `jd-hotkey-client` dependency in Nexus source files.

## Migration Impact

The replacement removes these JD-specific artifacts:

- `nexus-infrastructure` Maven dependency on `io.github.ck-jesse:jd-hotkey-client`.
- `HotKeyClientInitializer`.
- `HotKeyBridgeServer`.
- `hotkey-isolated-classpath.txt`.
- Profile configuration values for app name, etcd server, push period, and isolated/direct mode.

No database migration is required. No Redis key schema changes are required. Existing hot key names such as `content_post__{postId}` and `feed_card__{postId}` remain unchanged.

## Acceptance Criteria

- Nexus builds without `jd-hotkey-client`.
- No Nexus production code imports `com.jd.platform.hotkey`.
- Hot key behavior is implemented by local sliding-window counters.
- Existing repository behavior still uses `HotKeyStoreBridge#isHotKey`.
- Tests cover the local detector behavior and the existing repository hot-cache paths.
