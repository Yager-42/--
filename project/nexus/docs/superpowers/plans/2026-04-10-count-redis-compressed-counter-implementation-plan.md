# Count Redis Compressed Counter Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the current Nexus online counter path for `POST.like`, `COMMENT.like`, `COMMENT.reply`, `USER.following`, and `USER.follower` with a dedicated Count Redis deployment backed by a custom Redis module, while leaving all unrelated Redis usage unchanged.

**Architecture:** Add a standalone `count-redis-module` native component that provides compressed `CountInt` and `RoaringState` types plus custom commands. In the Java application, introduce a dedicated Count Redis connection and adapter layer that rewires the current reaction/object/user counter ports to the module commands, adds durable repair for user counter write failures, and updates local docker/test wiring to boot a separate Count Redis service.

**Tech Stack:** Redis 7 / `redis-stack-server`, Redis module (`redis-module-rs` + roaring bitmap binding), Java 17, Spring Boot 3.2, Spring Data Redis, RabbitMQ, MySQL, Maven, Cargo, Docker Compose.

---

## Execution Context

All file paths and commands in this plan are relative to the Nexus repo root:

- repo root: current workspace repo root (`nexus`)
- absolute repo root: `C:/Users/Administrator/Desktop/文档/project/nexus`

When this plan references shared infrastructure files outside the repo root, it uses explicit relative paths from that repo root:

- project compose root: `..`
- main compose file: `..\docker-compose.yml`
- middleware compose file: `..\docker-compose.middleware.yml`
- shared docker assets root: `..\docker`
- shared scripts root: `..\scripts`

## File Structure

### New native module workspace

- Create: `count-redis-module/Cargo.toml`
- Create: `count-redis-module/src/lib.rs`
- Create: `count-redis-module/src/schema.rs`
- Create: `count-redis-module/src/count_int.rs`
- Create: `count-redis-module/src/roaring_state.rs`
- Create: `count-redis-module/src/commands.rs`
- Create: `count-redis-module/src/errors.rs`
- Create: `count-redis-module/tests/module_smoke.rs`

Responsibility:

- keep all Redis-module ABI-facing code isolated from Java services
- encode static schema registry once
- implement `INT5` slot packing, roaring membership, command parsing, and command error contracts

### Docker and runtime wiring

- Modify: `..\docker-compose.yml`
- Modify: `..\docker-compose.middleware.yml`
- Create: `..\docker\count-redis\Dockerfile`
- Create: `..\docker\count-redis\redis-count.conf`
- Create: `..\scripts\build-count-redis-module.ps1`
- Create: `..\scripts\build-count-redis-module.sh`

Responsibility:

- build the module shared library
- boot a dedicated `count-redis` container
- keep current business `redis` service unchanged for everything outside the covered counter system

### Spring Count Redis integration

- Create: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/config/CountRedisProperties.java`
- Create: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/config/CountRedisConfig.java`
- Create: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/support/countredis/CountRedisKeys.java`
- Create: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/support/countredis/CountRedisSchemas.java`
- Create: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/support/countredis/CountRedisCommandExecutor.java`
- Create: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/support/countredis/CountRedisException.java`
- Create: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/support/countredis/ReactionApplyReply.java`
- Modify: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/ReactionCachePort.java`
- Modify: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/counter/port/ObjectCounterPort.java`
- Modify: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/counter/port/UserCounterPort.java`
- Modify: `nexus-domain/src/main/java/cn/nexus/domain/social/adapter/port/IReactionCachePort.java`

Responsibility:

- give counter paths a dedicated Redis connection
- preserve current domain-port signatures
- map module wire shapes into current Java return contracts
- remove bitmap-era operational semantics from the Count Redis-backed implementation

### User counter repair flow

- Create: `nexus-domain/src/main/java/cn/nexus/domain/social/adapter/repository/IUserCounterRepairOutboxRepository.java`
- Create: `nexus-domain/src/main/java/cn/nexus/domain/social/model/valobj/UserCounterRepairOutboxVO.java`
- Create: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/dao/social/IUserCounterRepairOutboxDao.java`
- Create: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/dao/social/po/UserCounterRepairOutboxPO.java`
- Create: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/UserCounterRepairOutboxRepository.java`
- Create: `nexus-infrastructure/src/main/resources/mapper/social/UserCounterRepairOutboxMapper.xml`
- Create: `nexus-trigger/src/main/java/cn/nexus/trigger/job/social/UserCounterRepairJob.java`
- Create: `docs/migrations/20260410_01_user_counter_repair_outbox.sql`
- Modify: `nexus-domain/src/main/java/cn/nexus/domain/social/service/RelationService.java`

Responsibility:

- durably record after-commit Count Redis write failures
- recompute exact `following` and `follower` counts from MySQL truth
- write repaired absolute values through `COUNT.SET`

### Tests and docs

- Modify: `nexus-infrastructure/src/test/java/cn/nexus/infrastructure/adapter/social/port/ReactionCachePortTest.java`
- Create: `nexus-infrastructure/src/test/java/cn/nexus/infrastructure/adapter/counter/port/ObjectCounterPortTest.java`
- Create: `nexus-infrastructure/src/test/java/cn/nexus/infrastructure/adapter/counter/port/UserCounterPortTest.java`
- Modify: `nexus-domain/src/test/java/cn/nexus/domain/social/service/ReactionLikeServiceTest.java`
- Modify: `nexus-domain/src/test/java/cn/nexus/domain/social/service/RelationServiceTest.java`
- Modify: `nexus-trigger/src/test/java/cn/nexus/trigger/mq/consumer/RootReplyCountChangedConsumerTest.java`
- Create: `nexus-trigger/src/test/java/cn/nexus/trigger/job/social/UserCounterRepairJobTest.java`
- Modify: `nexus-app/src/main/resources/application-dev.yml`
- Modify: `nexus-app/src/main/resources/application-docker.yml`

Responsibility:

- prove wire-contract mapping, underflow/overflow handling, fail-fast comment reply semantics, and best-effort relation repair semantics
- expose dedicated Count Redis host/port/module assumptions in app config

## Chunk 1: Native Count Redis Module

### Task 1: Scaffold the native module workspace

**Files:**
- Create: `count-redis-module/Cargo.toml`
- Create: `count-redis-module/src/lib.rs`
- Create: `count-redis-module/src/errors.rs`

- [ ] **Step 1: Write the failing smoke tests in the module crate**

Add real failing tests in `count-redis-module/tests/module_smoke.rs` for:

```rust
#[test]
fn count_get_missing_returns_zero() {
    panic!("implement count get missing behavior");
}

#[test]
fn reaction_apply_returns_current_count_and_delta() {
    panic!("implement reaction apply behavior");
}
```

- [ ] **Step 2: Run the crate tests to verify they fail**

Run: `cargo test --manifest-path count-redis-module/Cargo.toml`
Expected: FAIL because the crate and exported commands do not exist yet

- [ ] **Step 3: Create the module manifest and entrypoint**

Define:

- `redis-module = "..."`
- roaring bitmap dependency
- `cdylib` crate type
- module name registration in `src/lib.rs`

- [ ] **Step 4: Run the crate tests again**

Run: `cargo test --manifest-path count-redis-module/Cargo.toml`
Expected: FAIL later in command implementation, not on missing crate setup

- [ ] **Step 5: Commit**

```bash
git add count-redis-module/Cargo.toml count-redis-module/src/lib.rs count-redis-module/src/errors.rs count-redis-module/tests/module_smoke.rs
git commit -m "feat: scaffold count redis module crate"
```

### Task 2: Implement static schema registry and key-family dispatch

**Files:**
- Create: `count-redis-module/src/schema.rs`
- Modify: `count-redis-module/src/lib.rs`
- Test: `count-redis-module/tests/module_smoke.rs`

- [ ] **Step 1: Write the failing schema tests**

Add tests covering:

```rust
#[test]
fn post_counter_schema_exposes_like_slot_zero() {}

#[test]
fn comment_counter_schema_exposes_like_and_reply_slots() {}

#[test]
fn invalid_field_for_family_returns_error() {}
```

- [ ] **Step 2: Run the module tests to verify they fail**

Run: `cargo test --manifest-path count-redis-module/Cargo.toml schema`
Expected: FAIL with unresolved schema lookup

- [ ] **Step 3: Implement the schema registry**

Support:

- `count:post:{id}` -> `post_counter`
- `count:comment:{id}` -> `comment_counter`
- `count:user:{id}` -> `user_counter`
- fixed slot width `5 bytes`

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cargo test --manifest-path count-redis-module/Cargo.toml schema`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add count-redis-module/src/schema.rs count-redis-module/src/lib.rs count-redis-module/tests/module_smoke.rs
git commit -m "feat: add count redis schema registry"
```

### Task 3: Implement `CountInt` with `INT5` packing and counter commands

**Files:**
- Create: `count-redis-module/src/count_int.rs`
- Create: `count-redis-module/src/commands.rs`
- Modify: `count-redis-module/src/errors.rs`
- Test: `count-redis-module/tests/module_smoke.rs`

- [ ] **Step 1: Write the failing counter-command tests**

Cover:

```rust
#[test]
fn count_incrby_creates_zero_initialized_object() {}

#[test]
fn count_incrby_clamps_underflow_to_zero() {}

#[test]
fn count_incrby_rejects_overflow() {}

#[test]
fn count_getall_returns_schema_ordered_pairs() {}

#[test]
fn count_mgetall_returns_request_order_records() {}
```

- [ ] **Step 2: Run the counter tests to verify they fail**

Run: `cargo test --manifest-path count-redis-module/Cargo.toml count_`
Expected: FAIL because `CountInt` storage and commands are not implemented

- [ ] **Step 3: Implement the minimal `CountInt` type**

Implement:

- fixed-length byte array storage
- slot read/write helpers
- unsigned `INT5` encode/decode
- `COUNT.GET`
- `COUNT.INCRBY`
- `COUNT.GETALL`
- `COUNT.MGETALL`
- operational-only `COUNT.SET`
- operational-only `COUNT.DEL`

- [ ] **Step 4: Run the counter tests to verify they pass**

Run: `cargo test --manifest-path count-redis-module/Cargo.toml count_`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add count-redis-module/src/count_int.rs count-redis-module/src/commands.rs count-redis-module/src/errors.rs count-redis-module/tests/module_smoke.rs
git commit -m "feat: implement compressed count int commands"
```

### Task 4: Implement `RoaringState` and reaction commands

**Files:**
- Create: `count-redis-module/src/roaring_state.rs`
- Modify: `count-redis-module/src/commands.rs`
- Test: `count-redis-module/tests/module_smoke.rs`

- [ ] **Step 1: Write the failing reaction-command tests**

Cover:

```rust
#[test]
fn reaction_apply_adds_missing_member_and_increments_count() {}

#[test]
fn reaction_apply_is_noop_when_state_is_unchanged() {}

#[test]
fn reaction_apply_rejects_corrupt_negative_transition() {}

#[test]
fn reaction_state_returns_false_for_missing_key() {}
```

- [ ] **Step 2: Run the reaction tests to verify they fail**

Run: `cargo test --manifest-path count-redis-module/Cargo.toml reaction_`
Expected: FAIL because roaring-backed state and atomic command behavior are missing

- [ ] **Step 3: Implement `RoaringState`, `REACTION.APPLY`, and `REACTION.STATE`**

Requirements:

- create state and counter objects on first write
- return `[currentCount, delta]`
- perform no mutation on overflow or corrupt state
- preserve single-command atomicity inside Redis

- [ ] **Step 4: Run the reaction tests to verify they pass**

Run: `cargo test --manifest-path count-redis-module/Cargo.toml reaction_`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add count-redis-module/src/roaring_state.rs count-redis-module/src/commands.rs count-redis-module/tests/module_smoke.rs
git commit -m "feat: implement roaring reaction state commands"
```

## Chunk 2: Count Redis Runtime and Spring Integration

### Task 5: Add Docker build and local runtime wiring for dedicated Count Redis

**Files:**
- Create: `..\docker\count-redis\Dockerfile`
- Create: `..\docker\count-redis\redis-count.conf`
- Create: `..\scripts\build-count-redis-module.ps1`
- Create: `..\scripts\build-count-redis-module.sh`
- Modify: `..\docker-compose.yml`
- Modify: `..\docker-compose.middleware.yml`

- [ ] **Step 1: Validate the current compose files before editing**

Run: `docker compose -f ..\docker-compose.yml config`
Expected: PASS with only the current services

- [ ] **Step 2: Add the dedicated Count Redis service and module load config**

Implement:

- module build output path
- `count-redis` service name
- dedicated port mapping, for example `6380:6379`
- separate volume from current `redis`
- `loadmodule /opt/count-redis/libcountredis.so`
- `appendonly yes` plus explicit AOF persistence config in `redis-count.conf`
- Count Redis data dir mounted to a dedicated durable volume
- backend env vars for Count Redis host/port

- [ ] **Step 3: Validate both compose files**

Run: `docker compose -f ..\docker-compose.yml config`
Expected: PASS

Run: `docker compose -f ..\docker-compose.middleware.yml config`
Expected: PASS

- [ ] **Step 4: Verify Count Redis persistence is enabled**

Run:

```bash
docker compose -f ..\docker-compose.yml up -d count-redis
docker exec $(docker ps -q -f name=count-redis) redis-cli CONFIG GET appendonly
```

Expected:

- Count Redis starts successfully with the module loaded
- `appendonly` returns `yes`

- [ ] **Step 5: Commit**

```bash
git add ..\docker\count-redis\Dockerfile ..\docker\count-redis\redis-count.conf ..\scripts\build-count-redis-module.ps1 ..\scripts\build-count-redis-module.sh ..\docker-compose.yml ..\docker-compose.middleware.yml
git commit -m "chore: add dedicated count redis runtime"
```

### Task 6: Add dedicated Count Redis Spring configuration

**Files:**
- Create: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/config/CountRedisProperties.java`
- Create: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/config/CountRedisConfig.java`
- Modify: `nexus-app/src/main/resources/application-dev.yml`
- Modify: `nexus-app/src/main/resources/application-docker.yml`

- [ ] **Step 1: Write the failing configuration test or startup assertion**

Add a small Spring configuration test class:

`nexus-infrastructure/src/test/java/cn/nexus/infrastructure/config/CountRedisConfigTest.java`

Cover:

- Count Redis bean exists
- business Redis bean name remains unchanged
- one known unrelated Redis consumer such as `FeedTimelineRepository` still receives the default business `StringRedisTemplate`

- [ ] **Step 2: Run the config test to verify it fails**

Run: `mvn -pl nexus-infrastructure -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=CountRedisConfigTest test`
Expected: FAIL because Count Redis beans and properties do not exist

- [ ] **Step 3: Implement the dedicated connection factory and template/ops bean**

Requirements:

- no changes to existing default `StringRedisTemplate` consumers
- new qualifier for Count Redis command execution
- explicit host/port/database properties under a separate prefix such as `count.redis.*`

- [ ] **Step 4: Run the config test to verify it passes**

Run: `mvn -pl nexus-infrastructure -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=CountRedisConfigTest test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add nexus-infrastructure/src/main/java/cn/nexus/infrastructure/config/CountRedisProperties.java nexus-infrastructure/src/main/java/cn/nexus/infrastructure/config/CountRedisConfig.java nexus-infrastructure/src/test/java/cn/nexus/infrastructure/config/CountRedisConfigTest.java nexus-app/src/main/resources/application-dev.yml nexus-app/src/main/resources/application-docker.yml
git commit -m "feat: add dedicated count redis spring config"
```

### Task 7: Add Count Redis key/schema helpers and low-level command executor

**Files:**
- Create: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/support/countredis/CountRedisKeys.java`
- Create: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/support/countredis/CountRedisSchemas.java`
- Create: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/support/countredis/CountRedisCommandExecutor.java`
- Create: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/support/countredis/CountRedisException.java`
- Create: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/support/countredis/ReactionApplyReply.java`
- Create: `nexus-infrastructure/src/test/java/cn/nexus/infrastructure/support/countredis/CountRedisCommandExecutorTest.java`

- [ ] **Step 1: Write the failing executor tests**

Cover:

- `REACTION.APPLY` array maps to `currentCount` and `delta`
- `COUNT.MGETALL` alternating array unwraps to requested field values
- command errors surface as typed exceptions

- [ ] **Step 2: Run the executor tests to verify they fail**

Run: `mvn -pl nexus-infrastructure -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=CountRedisCommandExecutorTest test`
Expected: FAIL because the helper layer does not exist

- [ ] **Step 3: Implement the helper layer**

Keep rules explicit:

- code-defined schemas only
- exact key naming from the spec
- no domain-port leakage of multi-field module wire format

- [ ] **Step 4: Run the executor tests to verify they pass**

Run: `mvn -pl nexus-infrastructure -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=CountRedisCommandExecutorTest test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add nexus-infrastructure/src/main/java/cn/nexus/infrastructure/support/countredis nexus-infrastructure/src/test/java/cn/nexus/infrastructure/support/countredis/CountRedisCommandExecutorTest.java
git commit -m "feat: add count redis command executor"
```

## Chunk 3: Port Rewire and Business Semantics

### Task 8: Rewrite `ReactionCachePort` on top of Count Redis commands

**Files:**
- Modify: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/ReactionCachePort.java`
- Modify: `nexus-domain/src/main/java/cn/nexus/domain/social/adapter/port/IReactionCachePort.java`
- Modify: `nexus-infrastructure/src/test/java/cn/nexus/infrastructure/adapter/social/port/ReactionCachePortTest.java`
- Modify: `nexus-domain/src/test/java/cn/nexus/domain/social/service/ReactionLikeServiceTest.java`

- [ ] **Step 1: Extend the failing tests**

Add assertions for:

- `applyAtomic(...)` issues `REACTION.APPLY`
- `getState(...)` issues `REACTION.STATE`
- `batchGetCount(...)` preserves `Map<String, Long>`
- `getWindowMs(...)` returns caller `defaultMs`
- bitmap-era helpers are removed or deprecated according to the interface plan

- [ ] **Step 2: Run the targeted tests to verify they fail**

Run: `mvn -pl nexus-domain,nexus-infrastructure -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=ReactionLikeServiceTest,ReactionCachePortTest test`
Expected: FAIL because the adapter still uses bitmap/count string keys and Redis scripts

- [ ] **Step 3: Rewrite the adapter minimally**

Requirements:

- preserve `ReactionApplyResultVO.currentCount/delta/firstPending`
- keep hotkey/L1 cache behavior only if it still makes sense over Count Redis reads
- route recovery checkpoint methods to Count Redis checkpoint keys
- make `getWindowMs(...)` return `defaultMs`

- [ ] **Step 4: Run the targeted tests to verify they pass**

Run: `mvn -pl nexus-domain,nexus-infrastructure -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=ReactionLikeServiceTest,ReactionCachePortTest test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/port/ReactionCachePort.java nexus-domain/src/main/java/cn/nexus/domain/social/adapter/port/IReactionCachePort.java nexus-infrastructure/src/test/java/cn/nexus/infrastructure/adapter/social/port/ReactionCachePortTest.java nexus-domain/src/test/java/cn/nexus/domain/social/service/ReactionLikeServiceTest.java
git commit -m "feat: back reaction cache port with count redis"
```

### Task 9: Rewrite object counter reads and writes to Count Redis

**Files:**
- Modify: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/counter/port/ObjectCounterPort.java`
- Create: `nexus-infrastructure/src/test/java/cn/nexus/infrastructure/adapter/counter/port/ObjectCounterPortTest.java`
- Modify: `nexus-trigger/src/test/java/cn/nexus/trigger/mq/consumer/RootReplyCountChangedConsumerTest.java`

- [ ] **Step 1: Write the failing object-counter tests**

Cover:

- `POST.like` reads Count Redis `count:post:{id}/like`
- `COMMENT.like` reads Count Redis `count:comment:{id}/like`
- `COMMENT.reply` increments Count Redis and does not rebuild from DB on normal path
- batch reads unwrap requested field from `COUNT.MGETALL`

- [ ] **Step 2: Run the targeted tests to verify they fail**

Run: `mvn -pl nexus-infrastructure,nexus-trigger -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=ObjectCounterPortTest,RootReplyCountChangedConsumerTest test`
Expected: FAIL because the adapter still uses old string keys and rebuild logic

- [ ] **Step 3: Rewrite the adapter and consumer flow**

Requirements:

- route all covered counters through Count Redis
- keep `COMMENT.reply` fail-fast by letting Count Redis failure abort the consumer transaction
- only refresh hot-rank after the counter write succeeds

- [ ] **Step 4: Run the targeted tests to verify they pass**

Run: `mvn -pl nexus-infrastructure,nexus-trigger -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=ObjectCounterPortTest,RootReplyCountChangedConsumerTest test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/counter/port/ObjectCounterPort.java nexus-infrastructure/src/test/java/cn/nexus/infrastructure/adapter/counter/port/ObjectCounterPortTest.java nexus-trigger/src/test/java/cn/nexus/trigger/mq/consumer/RootReplyCountChangedConsumerTest.java
git commit -m "feat: back object counters with count redis"
```

### Task 10: Rewrite user counter adapter to Count Redis absolute and delta operations

**Files:**
- Modify: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/counter/port/UserCounterPort.java`
- Create: `nexus-infrastructure/src/test/java/cn/nexus/infrastructure/adapter/counter/port/UserCounterPortTest.java`

- [ ] **Step 1: Write the failing user-counter tests**

Cover:

- `getCount(...)` reads `count:user:{id}` with requested field
- `increment(...)` uses `COUNT.INCRBY`
- `setCount(...)` uses operational-only `COUNT.SET`
- `evict(...)` is operational-only and maps to `COUNT.DEL` only when the whole user counter object is intended to be dropped

- [ ] **Step 2: Run the targeted tests to verify they fail**

Run: `mvn -pl nexus-infrastructure -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=UserCounterPortTest test`
Expected: FAIL because the adapter still uses expiring string keys and local rebuild logic

- [ ] **Step 3: Rewrite the adapter minimally**

Rules:

- no TTL on Count Redis online truth keys
- only `FOLLOWING` and `FOLLOWER` are supported by the new truth model in v1
- unsupported counters should remain explicit and safe rather than silently guessing

- [ ] **Step 4: Run the targeted tests to verify they pass**

Run: `mvn -pl nexus-infrastructure -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=UserCounterPortTest test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/counter/port/UserCounterPort.java nexus-infrastructure/src/test/java/cn/nexus/infrastructure/adapter/counter/port/UserCounterPortTest.java
git commit -m "feat: back user counters with count redis"
```

## Chunk 4: Durable Repair, Recovery, and Verification

### Task 11: Add durable user counter repair outbox persistence

**Files:**
- Create: `nexus-domain/src/main/java/cn/nexus/domain/social/adapter/repository/IUserCounterRepairOutboxRepository.java`
- Create: `nexus-domain/src/main/java/cn/nexus/domain/social/model/valobj/UserCounterRepairOutboxVO.java`
- Create: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/dao/social/IUserCounterRepairOutboxDao.java`
- Create: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/dao/social/po/UserCounterRepairOutboxPO.java`
- Create: `nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/UserCounterRepairOutboxRepository.java`
- Create: `nexus-infrastructure/src/main/resources/mapper/social/UserCounterRepairOutboxMapper.xml`
- Create: `docs/migrations/20260410_01_user_counter_repair_outbox.sql`

- [ ] **Step 1: Write the failing repository tests**

Create:

`nexus-infrastructure/src/test/java/cn/nexus/infrastructure/adapter/social/repository/UserCounterRepairOutboxRepositoryTest.java`

Cover:

- save insert
- fetch pending
- mark done
- mark fail

- [ ] **Step 2: Run the repository tests to verify they fail**

Run: `mvn -pl nexus-infrastructure -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=UserCounterRepairOutboxRepositoryTest test`
Expected: FAIL because the outbox table and repository do not exist

- [ ] **Step 3: Implement the persistence layer**

Persist:

- `sourceUserId`
- `targetUserId`
- `operation`
- `reason`
- correlation id if present
- status and retry metadata

- [ ] **Step 4: Run the repository tests to verify they pass**

Run: `mvn -pl nexus-infrastructure -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=UserCounterRepairOutboxRepositoryTest test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add nexus-domain/src/main/java/cn/nexus/domain/social/adapter/repository/IUserCounterRepairOutboxRepository.java nexus-domain/src/main/java/cn/nexus/domain/social/model/valobj/UserCounterRepairOutboxVO.java nexus-infrastructure/src/main/java/cn/nexus/infrastructure/dao/social/IUserCounterRepairOutboxDao.java nexus-infrastructure/src/main/java/cn/nexus/infrastructure/dao/social/po/UserCounterRepairOutboxPO.java nexus-infrastructure/src/main/java/cn/nexus/infrastructure/adapter/social/repository/UserCounterRepairOutboxRepository.java nexus-infrastructure/src/main/resources/mapper/social/UserCounterRepairOutboxMapper.xml nexus-infrastructure/src/test/java/cn/nexus/infrastructure/adapter/social/repository/UserCounterRepairOutboxRepositoryTest.java docs/migrations/20260410_01_user_counter_repair_outbox.sql
git commit -m "feat: add user counter repair outbox"
```

### Task 12: Integrate relation after-commit failure capture and repair scheduling

**Files:**
- Modify: `nexus-domain/src/main/java/cn/nexus/domain/social/service/RelationService.java`
- Modify: `nexus-domain/src/test/java/cn/nexus/domain/social/service/RelationServiceTest.java`

- [ ] **Step 1: Extend the failing relation-service tests**

Add tests for:

- follow success still writes relation truth first
- if after-commit Count Redis increment fails, a repair outbox row is saved
- repair trigger does not fire on successful Count Redis write
- block/unfollow only enqueue affected users once per failure path

- [ ] **Step 2: Run the relation-service tests to verify they fail**

Run: `mvn -pl nexus-domain -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=RelationServiceTest test`
Expected: FAIL because durable repair recording is not implemented

- [ ] **Step 3: Implement after-commit failure capture**

Approach:

- wrap `userCounterPort.increment(...)` calls in a small helper
- on failure, persist a `user_counter_repair_outbox` row before returning from the after-commit callback
- do not roll back the already committed relation write

- [ ] **Step 4: Run the relation-service tests to verify they pass**

Run: `mvn -pl nexus-domain -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=RelationServiceTest test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add nexus-domain/src/main/java/cn/nexus/domain/social/service/RelationService.java nexus-domain/src/test/java/cn/nexus/domain/social/service/RelationServiceTest.java
git commit -m "feat: record user counter repair on after-commit failure"
```

### Task 13: Add repair job that recomputes exact user counters from MySQL

**Files:**
- Create: `nexus-trigger/src/main/java/cn/nexus/trigger/job/social/UserCounterRepairJob.java`
- Create: `nexus-trigger/src/test/java/cn/nexus/trigger/job/social/UserCounterRepairJobTest.java`

- [ ] **Step 1: Write the failing repair-job tests**

Cover:

- fetch pending repair rows
- recompute `following` from `countActiveRelationsBySource`
- recompute `follower` from `countFollowerIds`
- write absolute counts through `userCounterPort.setCount(...)`
- mark success or schedule retry

- [ ] **Step 2: Run the repair-job tests to verify they fail**

Run: `mvn -pl nexus-trigger -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=UserCounterRepairJobTest test`
Expected: FAIL because the repair job does not exist

- [ ] **Step 3: Implement the repair job**

Rules:

- only consume `user_counter_repair_outbox`
- allow duplicate execution
- recompute exact counts for all affected users before marking done

- [ ] **Step 4: Run the repair-job tests to verify they pass**

Run: `mvn -pl nexus-trigger -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=UserCounterRepairJobTest test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add nexus-trigger/src/main/java/cn/nexus/trigger/job/social/UserCounterRepairJob.java nexus-trigger/src/test/java/cn/nexus/trigger/job/social/UserCounterRepairJobTest.java
git commit -m "feat: add user counter repair job"
```

### Task 14: Run final verification and update operational docs

**Files:**
- Modify: `docs/superpowers/specs/2026-04-10-count-redis-compressed-counter-design.md` if implementation drift is discovered
- Modify: `docs/superpowers/plans/2026-04-10-count-redis-compressed-counter-implementation-plan.md` only to mark deviations if required

- [ ] **Step 1: Run the focused unit test suites**

Run:

```bash
mvn -pl nexus-domain,nexus-infrastructure,nexus-trigger -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=ReactionLikeServiceTest,RelationServiceTest,ReactionCachePortTest,ObjectCounterPortTest,UserCounterPortTest,RootReplyCountChangedConsumerTest,UserCounterRepairJobTest,CountRedisConfigTest,CountRedisCommandExecutorTest test
```

Expected: PASS

- [ ] **Step 2: Run module tests**

Run:

```bash
cargo test --manifest-path count-redis-module/Cargo.toml
```

Expected: PASS

- [ ] **Step 3: Run a local compose validation pass**

Run:

```bash
docker compose -f ..\docker-compose.yml config
docker compose -f ..\docker-compose.middleware.yml config
```

Expected: PASS

- [ ] **Step 4: Run the required high-value real integration slices**

Run:

```bash
mvn -pl nexus-app -am -Plocal-real-it -Dskip.real.it.tests=false -Dsurefire.failIfNoSpecifiedTests=false -Dtest=ReactionHttpRealIntegrationTest,UserHttpRealIntegrationTest,HighConcurrencyConsistencyAuditIntegrationTest test
```

Expected: PASS, with the dedicated `count-redis` service running and no competing local app consuming the same queues

- [ ] **Step 5: Run one recovery-equivalence validation slice**

Run:

```bash
mvn -pl nexus-trigger,nexus-app -am -Plocal-real-it -Dskip.real.it.tests=false -Dsurefire.failIfNoSpecifiedTests=false -Dtest=ReactionRedisRecoveryRunnerTest,ReactionHttpRealIntegrationTest test
```

Expected:

- recovery runner passes against Count Redis-backed checkpoints
- rebuilt reaction counts match online counts for the tested slice

- [ ] **Step 6: Commit**

```bash
git add .
git commit -m "test: verify count redis compressed counter rollout"
```

## Notes

- Keep all unrelated Redis consumers on the existing default `StringRedisTemplate`; only the covered counter system should switch to Count Redis.
- Preserve current domain-port return contracts unless the plan explicitly says otherwise.
- If the Redis module toolchain or ABI proves unstable on Windows, build the `.so` inside the Linux container image and keep Java tests runnable on Windows with mocked Count Redis command execution.
- Do not dual-write old and new counter keys in this rollout; the current environment does not require historical data preservation.
