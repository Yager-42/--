# Zhiguang HotKey Replacement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Nexus JD HotKey usage with the Zhiguang-style local sliding-window detector and remove active JD HotKey runtime paths.

**Architecture:** Keep `HotKeyStoreBridge#isHotKey(String)` as the repository-facing API. Replace its internals with an in-process, segmented, record-and-check detector backed only by JDK concurrency primitives. Remove JD client startup, helper process, worker/dashboard compose services, hotkey DB initialization, and JD-only configuration.

**Tech Stack:** Java 17, Spring Boot, JUnit 5, Mockito, Maven, Docker Compose YAML, Bash.

---

## File Structure

- Modify `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/config/HotKeyProperties.java`: replace JD client fields with local detector fields.
- Replace `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/config/HotKeyStoreBridge.java`: implement the local sliding-window detector behind the existing bean/API.
- Delete `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/config/HotKeyClientInitializer.java`: local detector has no startup handshake.
- Delete `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/config/HotKeyBridgeServer.java`: isolated helper process is JD-only.
- Delete `project/nexus/nexus-infrastructure/src/main/resources/hotkey-isolated-classpath.txt`: helper classpath is JD-only.
- Modify `project/nexus/nexus-infrastructure/pom.xml`: remove `io.github.ck-jesse:jd-hotkey-client`.
- Modify `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/ContentRepository.java`: update JD-specific warning text only.
- Modify `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/FeedCardRepository.java`: update JD-specific warning text only.
- Create `project/nexus/nexus-infrastructure/src/test/java/cn/nexus/infrastructure/config/HotKeyStoreBridgeTest.java`: detector behavior tests.
- Modify `project/nexus/nexus-infrastructure/src/test/java/cn/nexus/infrastructure/adapter/social/repository/ContentRepositoryTest.java`: prove hot content posts use `post__{postId}` for TTL extension.
- Keep `project/nexus/nexus-infrastructure/src/test/java/cn/nexus/infrastructure/adapter/social/repository/FeedCardRepositoryTest.java`: existing `feed_card__{postId}` TTL extension test already covers the required path.
- Modify `project/nexus/nexus-app/src/main/resources/application-dev.yml`: remove JD hotkey properties.
- Modify `project/nexus/nexus-app/src/main/resources/application-wsl.yml`: remove JD hotkey properties.
- Modify `project/nexus/nexus-app/src/main/resources/application-docker.yml`: remove JD hotkey properties.
- Keep `project/nexus/nexus-app/src/test/resources/application-real-it.yml`: `hotkey.enabled: false` remains valid.
- Modify `project/docker-compose.yml`: remove JD-only backend `HOTKEY_*` environment variables.
- Modify `project/docker-compose.middleware.yml`: remove JD HotKey worker/dashboard services and the JD-only `etcd` service plus `etcd-data` volume.
- Modify `project/docker/mysql/init-extra.sh`: remove `hotkey_db` creation and schema loading.
- Delete `project/docker/hotkey/Dockerfile`.
- Delete `project/docker/hotkey/init/schema.sql`.
- Modify `project/scripts/up-wsl-middleware.sh`: remove hotkey worker/dashboard branching and `HOTKEY_PUBLIC_IP`.
- Modify `project/scripts/start-local-all.ps1`: remove HotKey Dashboard/etcd readiness checks and final HotKey URL output that only supported JD HotKey.
- Modify `project/scripts/stop-local-all.ps1`: remove `HotKeyBridgeServer` helper shutdown.
- Modify `project/DEPLOY.md`: remove JD HotKey dashboard/worker/etcd instructions and describe hot key detection as local, in-process behavior with no user-facing service.

## Execution Rules

- Do not change content/feed repository behavior except JD-specific warning text.
- Do not change Redis key names, Redis TTL constants, Caffeine cache sizes, single-flight behavior, or repository constructor signatures.
- Do not add Redis, MySQL, RabbitMQ, Kafka, HTTP, helper process, dashboard, or admin API support for hot key detection.
- Do not keep JD HotKey behind a feature flag.
- Do not use broad delete commands. Delete only the files named in this plan.
- Keep unrelated working-tree changes untouched, especially existing `zhiguang_be` changes.
- Stop and inspect if a verification search matches an active runtime file not listed in this plan.

## Task 1: Detector Tests First

**Files:**
- Create: `project/nexus/nexus-infrastructure/src/test/java/cn/nexus/infrastructure/config/HotKeyStoreBridgeTest.java`

- [ ] **Step 1: Write failing detector tests**

Create `HotKeyStoreBridgeTest` with tests for:

- disabled detector returns false and does not heat the key
- blank key returns false and does not create heat
- thresholds `2/4/6` produce LOW, MEDIUM, HIGH after repeated `isHotKey("k")`
- `level`, `heat`, `reset`, and `rotate` do not increment heat
- rotating through all segments expires previous heat
- invalid window/segment values still produce a working single-segment detector

Use package `cn.nexus.infrastructure.config`. Instantiate `HotKeyProperties`, set test thresholds, then instantiate `new HotKeyStoreBridge(properties)`.

Required test method names:

- `disabledDetectorShouldStayCold`
- `blankKeyShouldStayCold`
- `isHotKeyShouldRecordAndExposeLevels`
- `heatLevelResetAndRotateShouldNotRecordAccess`
- `rotateThroughAllSegmentsShouldExpireHeat`
- `invalidWindowAndSegmentShouldUseSingleSegment`

- [ ] **Step 2: Run detector tests and verify RED**

Run:

`mvn -f project/nexus/pom.xml -pl nexus-infrastructure -Dtest=HotKeyStoreBridgeTest test`

Expected: compilation/test failure because `HotKeyStoreBridge` still exposes JD-specific behavior and lacks the local detector methods `heat`, `level`, `rotate`, or `reset`.

## Task 2: Local Detector Implementation

**Files:**
- Modify: `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/config/HotKeyProperties.java`
- Modify: `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/config/HotKeyStoreBridge.java`

- [ ] **Step 1: Replace `HotKeyProperties` fields**

Keep `enabled = true`. Remove `appName`, `etcdServer`, `pushPeriodMs`, and `mode`. Add:

- `windowSeconds = 60`
- `segmentSeconds = 10`
- `levelLow = 50`
- `levelMedium = 200`
- `levelHigh = 500`

Update class comments to describe local hot key detection, not JD HotKey.

- [ ] **Step 2: Replace `HotKeyStoreBridge` internals**

Implement:

- enum `Level { NONE, LOW, MEDIUM, HIGH }`
- constructor injection of `HotKeyProperties`
- per-key segmented in-memory counters using only JDK concurrency primitives
- `boolean isHotKey(String key)` as record-and-check
- `int heat(String key)`
- `Level level(String key)`
- scheduled `rotate()` using `hotkey.segmentSeconds`, defaulting to 10 seconds
- `reset(String key)`

Rules:

- no `com.jd.platform.hotkey` imports
- no HTTP client
- no process lifecycle fields
- no helper classpath handling
- blank/disabled returns cold without recording
- only `isHotKey` increments heat
- `heat`, `level`, `rotate`, and `reset` never increment heat
- all thresholds use `HotKeyProperties`
- hot means `heat >= levelLow`
- levels check high before medium before low
- invalid `windowSeconds` or `segmentSeconds` values are normalized defensively and never throw during bean construction
- `rotate` clears exactly the next active segment for every tracked key

- [ ] **Step 3: Run detector tests and verify GREEN**

Run:

`mvn -f project/nexus/pom.xml -pl nexus-infrastructure -Dtest=HotKeyStoreBridgeTest test`

Expected: all `HotKeyStoreBridgeTest` tests pass.

- [ ] **Step 4: Commit local detector**

Run:

`git add project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/config/HotKeyProperties.java project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/config/HotKeyStoreBridge.java project/nexus/nexus-infrastructure/src/test/java/cn/nexus/infrastructure/config/HotKeyStoreBridgeTest.java`

`git commit -m "refactor: replace jd hotkey bridge with local detector"`

## Task 3: Repository Boundary Tests And Log Text

**Files:**
- Modify: `project/nexus/nexus-infrastructure/src/test/java/cn/nexus/infrastructure/adapter/social/repository/ContentRepositoryTest.java`
- Modify: `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/ContentRepository.java`
- Modify: `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/FeedCardRepository.java`

- [ ] **Step 1: Write failing content repository hot-key test**

Add a test in `ContentRepositoryTest` that:

- creates a local repository instance with `SocialCacheHotTtlProperties.contentPostSeconds = 300`
- stubs Redis JSON for `interact:content:post:101`
- stubs `stringRedisTemplate.getExpire("interact:content:post:101", TimeUnit.SECONDS)` to `60L`
- stubs `hotKeyStoreBridge.isHotKey("post__101")` to `true`
- calls `findPost(101L)`
- verifies `stringRedisTemplate.expire("interact:content:post:101", 300L, TimeUnit.SECONDS)`

Name the test `findPost_hotKeyShouldExtendRedisTtlUsingPostHotKey`. Create a local helper record or private factory inside the test class so the test can access the `StringRedisTemplate`, `ValueOperations`, `SocialCacheHotTtlProperties`, and `HotKeyStoreBridge` mocks. Do not change production constructors to make this test easier.

- [ ] **Step 2: Run repository tests and verify RED if helper changes are needed**

Run:

`mvn -f project/nexus/pom.xml -pl nexus-infrastructure -Dtest=ContentRepositoryTest,FeedCardRepositoryTest test`

Expected: new test initially fails because the current helper does not expose enough mocks or because TTL extension is not yet asserted.

- [ ] **Step 3: Adjust tests, not production behavior**

Make test-only helper changes in `ContentRepositoryTest` so the new test can configure:

- `SocialCacheHotTtlProperties`
- `HotKeyStoreBridge`
- `StringRedisTemplate`
- `ValueOperations`

Do not change content/feed repository behavior except warning messages.

- [ ] **Step 4: Remove JD-specific warning text**

In `ContentRepository` and `FeedCardRepository`, replace warning message text `jd-hotkey isHotKey failed` with `hotkey isHotKey failed`. Do not rename `isHotKeySafe`, `hotkeyKey`, or repository constants.

- [ ] **Step 5: Run repository tests and verify GREEN**

Run:

`mvn -f project/nexus/pom.xml -pl nexus-infrastructure -Dtest=ContentRepositoryTest,FeedCardRepositoryTest test`

Expected: all repository tests pass.

- [ ] **Step 6: Commit repository boundary updates**

Run:

`git add project/nexus/nexus-infrastructure/src/test/java/cn/nexus/infrastructure/adapter/social/repository/ContentRepositoryTest.java project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/ContentRepository.java project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/FeedCardRepository.java`

`git commit -m "test: preserve repository hotkey boundaries"`

## Task 4: Remove JD Java Runtime Artifacts

**Files:**
- Delete: `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/config/HotKeyClientInitializer.java`
- Delete: `project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/config/HotKeyBridgeServer.java`
- Delete: `project/nexus/nexus-infrastructure/src/main/resources/hotkey-isolated-classpath.txt`
- Modify: `project/nexus/nexus-infrastructure/pom.xml`

- [ ] **Step 1: Remove the JD Maven dependency**

Delete the dependency block for:

`io.github.ck-jesse:jd-hotkey-client:2.0.0`

- [ ] **Step 2: Delete JD-only Java/resource files**

Delete:

- `HotKeyClientInitializer.java`
- `HotKeyBridgeServer.java`
- `hotkey-isolated-classpath.txt`

- [ ] **Step 3: Run active Java artifact search**

Run:

`rg -n "com\\.jd\\.platform\\.hotkey|jd-hotkey-client|hotkey-isolated-classpath|HotKeyBridgeServer|HotKeyClientInitializer" project/nexus/nexus-infrastructure project/nexus/nexus-app`

Expected: no matches.

- [ ] **Step 4: Run infrastructure tests**

Run:

`mvn -f project/nexus/pom.xml -pl nexus-infrastructure test`

Expected: build succeeds and tests pass.

- [ ] **Step 5: Commit JD Java cleanup**

Run:

`git add project/nexus/nexus-infrastructure/pom.xml project/nexus/nexus-infrastructure/src/main/java/cn/nexus/infrastructure/config project/nexus/nexus-infrastructure/src/main/resources`

`git commit -m "refactor: remove jd hotkey runtime artifacts"`

## Task 5: Active Configuration Cleanup

**Files:**
- Modify: `project/nexus/nexus-app/src/main/resources/application-dev.yml`
- Modify: `project/nexus/nexus-app/src/main/resources/application-wsl.yml`
- Modify: `project/nexus/nexus-app/src/main/resources/application-docker.yml`
- Keep: `project/nexus/nexus-app/src/test/resources/application-real-it.yml`
- Modify: `project/docker-compose.yml`

- [ ] **Step 1: Remove JD hotkey profile fields**

In `application-dev.yml`, `application-wsl.yml`, and `application-docker.yml`, remove the JD-only `hotkey` blocks entirely. Rely on `HotKeyProperties` defaults for local detection. Do not add duplicate threshold blocks to profile YAML.

- [ ] **Step 2: Keep real integration test hotkey disabled**

Ensure `application-real-it.yml` still contains:

`hotkey.enabled: false`

No other hotkey fields are required there.

- [ ] **Step 3: Remove backend JD environment variables**

In `project/docker-compose.yml`, remove:

- `HOTKEY_APP_NAME`
- `HOTKEY_ETCD_SERVER`

Verify `HOTKEY_PUSH_PERIOD_MS` is absent.

- [ ] **Step 4: Search active config**

Run:

`rg -n "appName|etcdServer|pushPeriodMs|mode: isolated|HOTKEY_APP_NAME|HOTKEY_ETCD_SERVER|HOTKEY_PUSH_PERIOD_MS" project/nexus/nexus-app/src/main/resources project/nexus/nexus-app/src/test/resources project/docker-compose.yml project/scripts/start-local-all.ps1 project/DEPLOY.md`

Expected: no matches in active hotkey configuration or deployment instructions.

- [ ] **Step 5: Commit config cleanup**

Run:

`git add project/nexus/nexus-app/src/main/resources/application-dev.yml project/nexus/nexus-app/src/main/resources/application-wsl.yml project/nexus/nexus-app/src/main/resources/application-docker.yml project/nexus/nexus-app/src/test/resources/application-real-it.yml project/docker-compose.yml`

`git commit -m "chore: remove jd hotkey configuration"`

## Task 6: Active Docker, Script, And Deployment Cleanup

**Files:**
- Modify: `project/docker-compose.middleware.yml`
- Modify: `project/docker/mysql/init-extra.sh`
- Delete: `project/docker/hotkey/Dockerfile`
- Delete: `project/docker/hotkey/init/schema.sql`
- Modify: `project/scripts/up-wsl-middleware.sh`
- Modify: `project/scripts/start-local-all.ps1`
- Modify: `project/scripts/stop-local-all.ps1`
- Modify: `project/DEPLOY.md`

- [ ] **Step 1: Remove hotkey services from middleware compose**

In `project/docker-compose.middleware.yml`, remove:

- `hotkey-worker`
- `hotkey-dashboard`
- `etcd`
- `etcd-data`

Do not remove Zookeeper.

- [ ] **Step 2: Remove hotkey schema initialization**

In `project/docker/mysql/init-extra.sh`, remove:

- `hotkey_db` database creation
- `HOTKEY_SCHEMA_EXISTS`
- loading `/schema/hotkey-schema.sql`

Keep Gorse database creation and Nexus schema loading.

- [ ] **Step 3: Delete JD HotKey Docker files**

Delete:

- `project/docker/hotkey/Dockerfile`
- `project/docker/hotkey/init/schema.sql`

- [ ] **Step 4: Simplify WSL middleware script**

In `project/scripts/up-wsl-middleware.sh`, remove:

- `HOTKEY_PUBLIC_IP` discovery/export/echo
- `HOTKEY_SERVICES`
- split base/hotkey service handling
- automatic addition of `mysql`, `mysql-extra-init`, and `etcd` for hotkey services

The script must parse `--build` and `--remove-orphans`, compute requested services, run compose up for those services, and keep existing waits for `mysql-extra-init` and `rabbitmq-init`.

- [ ] **Step 5: Clean PowerShell local scripts**

In `project/scripts/start-local-all.ps1`, remove:

- `etcd` port readiness checks
- `HotKey Dashboard` port readiness checks
- final `HotKey: http://localhost:9901` output

In `project/scripts/stop-local-all.ps1`, remove the `HotKeyBridgeServer` process shutdown line. Keep frontend, backend, and backend launcher shutdown.

- [ ] **Step 6: Clean deployment documentation**

In `project/DEPLOY.md`, remove user instructions that describe:

- HotKey Dashboard URL, ports, accounts, or health checks
- etcd as a HotKey dependency
- `HOTKEY_PUBLIC_IP`
- `docker/hotkey/Dockerfile`
- `/jd/workers`
- backend log expectation `hotkey client started`

Add one short deployment note: hot key detection is now local in the Nexus process and has no dashboard, worker, etcd, or extra database setup.

- [ ] **Step 7: Search active runtime environment**

Run:

`rg -n "hotkey-worker|hotkey-dashboard|HOTKEY_PUBLIC_IP|HOTKEY_SERVICES|hotkey_db|hk_user|hotkey-schema|l2cache-jd-hotkey|jd-hotkey|HotKeyBridgeServer|HotKey Dashboard|/jd/workers|hotkey client started" project/docker-compose.middleware.yml project/docker/mysql/init-extra.sh project/docker project/scripts project/DEPLOY.md`

Expected: no matches.

- [ ] **Step 8: Commit Docker/script/doc cleanup**

Run:

`git add project/docker-compose.middleware.yml project/docker/mysql/init-extra.sh project/scripts/up-wsl-middleware.sh project/scripts/start-local-all.ps1 project/scripts/stop-local-all.ps1 project/DEPLOY.md project/docker/hotkey`

`git commit -m "chore: remove jd hotkey middleware"`

## Task 7: Final Verification

**Files:**
- Verify: active runtime files and tests

- [ ] **Step 1: Run targeted tests**

Run:

`mvn -f project/nexus/pom.xml -pl nexus-infrastructure -Dtest=HotKeyStoreBridgeTest,ContentRepositoryTest,FeedCardRepositoryTest test`

Expected: all tests pass.

- [ ] **Step 2: Run infrastructure test suite**

Run:

`mvn -f project/nexus/pom.xml -pl nexus-infrastructure test`

Expected: build succeeds and tests pass.

- [ ] **Step 3: Run active runtime JD artifact search**

Run:

`rg -n "com\\.jd\\.platform\\.hotkey|jd-hotkey|hotkey-isolated-classpath|HotKeyBridgeServer|HotKeyClientInitializer|HOTKEY_APP_NAME|HOTKEY_ETCD_SERVER|HOTKEY_PUSH_PERIOD_MS|HOTKEY_PUBLIC_IP|HOTKEY_SERVICES|hotkey-worker|hotkey-dashboard|hotkey_db|hotkey-schema|HotKey Dashboard|/jd/workers|hotkey client started" project/nexus/nexus-infrastructure project/nexus/nexus-app/src/main/resources project/nexus/nexus-app/src/test/resources project/docker-compose.yml project/docker-compose.middleware.yml project/docker project/scripts project/DEPLOY.md`

Expected: no matches.

- [ ] **Step 4: Confirm historical docs are the only remaining references**

Run:

`rg -n "jd-hotkey|HOTKEY_APP_NAME|HOTKEY_ETCD_SERVER|HOTKEY_PUBLIC_IP|HOTKEY_SERVICES|hotkey-worker|hotkey-dashboard|hotkey_db|HotKey Dashboard|/jd/workers" project`

Expected: matches are limited to historical docs/specs/plans outside active runtime paths. If active runtime files still match, fix them before completion.

- [ ] **Step 5: Check git state**

Run:

`git status --short`

Expected: only intentional changes are present. Existing unrelated `zhiguang_be` working-tree changes must remain untouched.

## Self-Review

Spec coverage:

- Local detector behavior: Tasks 1 and 2.
- Repository API preservation and key names: Task 3.
- JD Java artifact removal: Task 4.
- Profile and compose env cleanup: Task 5.
- Worker/dashboard/schema/script/deployment cleanup: Task 6.
- Acceptance verification: Task 7.

No broad architecture changes are planned. The implementation keeps content/feed repositories on `HotKeyStoreBridge#isHotKey`, keeps cache key names stable, and does not introduce distributed/network hotness tracking.
