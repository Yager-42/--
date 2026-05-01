# Strict Zhiguang Counter Replacement Design

## Status

Approved design for implementation planning.

This spec supersedes the earlier hybrid/Class-1/Class-2 replacement direction in
`2026-04-23-replace-nexus-count-system-with-zhiguang-design.md`.

## Decision

Nexus will replace its current counter subsystem with a strict `zhiguang_be`
counter design. If Nexus currently has a counter capability that `zhiguang_be`
does not have, it is removed from the counter system. If `zhiguang_be` has a
counter capability that Nexus does not have, Nexus adds the missing business
capability.

The immediate missing capability is post favorite:

- `fav`
- `unfav`
- post favorite count
- current-user favorite state
- user `favsReceived`

## Design Interpretation Rules

These rules are binding for implementation planning and code review.

1. **Strict zhiguang wins.** When existing Nexus behavior conflicts with
   `zhiguang_be` counter behavior, implement the `zhiguang_be` behavior.
2. **No hidden compatibility layer.** A deleted counter capability must not
   survive behind an adapter, deprecated service, fallback branch, feature flag,
   scheduled job, or unused-but-wired consumer.
3. **No generic counter framework.** Expose concrete post `like/fav` and user
   five-slot counter operations. Do not rebuild a generic reaction/counter
   platform.
4. **Redis bitmap is object-interaction truth.** MySQL must not become the truth
   source for post `like/fav` membership.
5. **MySQL business tables are only user-counter rebuild facts.** Relation and
   content tables are permitted only for rebuilding `followings`, `followers`,
   and `posts`; they must not become object-counter truth.
6. **Missing capability means build it.** `fav/unfav`, `favoriteCount`, `faved`,
   and `favsReceived` are required.
7. **Extra capability means remove it.** `COMMENT.like`, `COMMENT.reply`,
   generic reactions, search-index count propagation, and Nexus-specific repair
   mechanics must be removed from the active counter system.
8. **A reserved SDS slot is not an active capability.** Reserved `read`,
   `comment`, and `repost` object slots exist only for schema compatibility and
   must not be wired to business behavior.
9. **Breaking old counter APIs is expected.** API compatibility is lower
   priority than eliminating counter-system ambiguity.
10. **Tests encode the boundary.** Every removed capability must have a
    regression, contract, or static reference test proving it is not active.

## Goals

- Replicate the `zhiguang_be` counter model in Nexus rather than evolving the
  current Nexus hybrid model.
- Keep only post object counters `like` and `fav`.
- Keep only user counters `followings`, `followers`, `posts`, `likesReceived`,
  and `favsReceived`.
- Use Redis bitmap truth for post `like/fav` membership.
- Use Kafka `counter-events`, Redis `agg:*`, and Redis SDS `cnt:*` for post
  counter snapshots.
- Use Redis SDS `ucnt:{userId}` for user counters.
- Rebuild malformed or missing object snapshots from bitmap truth.
- Rebuild malformed or missing user snapshots from business facts and object
  counters.
- Delete Nexus-only counter families and recovery/projection concepts that are
  not present in `zhiguang_be`.

## Non-Goals

- Preserve `COMMENT.like` as a counter-system capability.
- Preserve `COMMENT.reply` or comment reply-count persistence as a counter
  capability.
- Preserve Count Redis module semantics.
- Preserve RabbitMQ object-counter aggregation.
- Preserve Nexus reaction-count tables, reaction event-log replay, gap-log,
  checkpoint, or repair-outbox semantics.
- Preserve search-index count-field propagation.
- Backfill old counter values or dual-write old and new counter structures.

## Final Counter Scope

### Object Counters

Active object counter family:

- `POST.like`
- `POST.fav`

Removed object counter families:

- `COMMENT.like`
- `COMMENT.reply`
- any generic reaction counter beyond post `like/fav`

The removed object counters must not be readable, writable, aggregated,
rebuilt, indexed, or propagated by the counter system.

### User Counters

Active user counter family:

- `USER.followings`
- `USER.followers`
- `USER.posts`
- `USER.likesReceived`
- `USER.favsReceived`

Removed user counter concepts:

- Nexus-specific repair outbox state
- Nexus-specific post projection state for counter repair
- any user counter not in the five-slot zhiguang layout

### Capability Matrix

| Capability | Final status | Truth source | Snapshot | Required behavior |
| --- | --- | --- | --- | --- |
| `POST.like` | Active | Redis bitmap | `cnt:v1:post:{postId}` slot 1 | Full zhiguang flow |
| `POST.fav` | Active | Redis bitmap | `cnt:v1:post:{postId}` slot 2 | Full zhiguang flow |
| current-user post liked | Active | Redis bitmap | none | `GETBIT` only |
| current-user post faved | Active | Redis bitmap | none | `GETBIT` only |
| `USER.followings` | Active | relation business fact | `ucnt:{userId}` slot 1 | Lua increment plus rebuild |
| `USER.followers` | Active | follower/relation fact | `ucnt:{userId}` slot 2 | Lua increment plus rebuild |
| `USER.posts` | Active | content publish fact | `ucnt:{userId}` slot 3 | Lua increment plus rebuild |
| `USER.likesReceived` | Active | sum of owned post likes | `ucnt:{userId}` slot 4 | local event plus rebuild |
| `USER.favsReceived` | Active | sum of owned post favs | `ucnt:{userId}` slot 5 | local event plus rebuild |
| `COMMENT.like` | Removed | none | none | reject/remove |
| `COMMENT.reply` | Removed | none | none | reject/remove |
| search count fields | Removed | none | none | remove propagation |
| reaction event-log replay | Removed | none | none | remove code path |
| Count Redis module counters | Removed | none | none | remove runtime dependency |

## API Contract

Nexus introduces or standardizes the zhiguang-style action endpoints:

- `POST /api/v1/action/like`
- `POST /api/v1/action/unlike`
- `POST /api/v1/action/fav`
- `POST /api/v1/action/unfav`

The request body carries the post target. The only accepted target type is
`post`.

Nexus exposes the object counter read endpoint:

- `GET /api/v1/counter/post/{postId}?metrics=like,fav`

API behavior rules:

- `like` and `fav` return whether the state changed plus the current user state.
- `unlike` and `unfav` return whether the state changed plus the current user
  state.
- Repeating the same operation succeeds with `changed=false`; it is not an error
  and must not emit a delta.
- Unsupported target types return a parameter error before touching Redis,
  Kafka, local events, or side effects.
- Unsupported metrics are ignored or rejected consistently by the controller;
  they must never allocate SDS slots or create new key families.
- The action layer must not expose a generic reaction type model for new counter
  behavior.

Post feed and detail APIs assemble:

- `likeCount`
- `favoriteCount`
- `liked`
- `faved`

User profile/relation counter APIs assemble:

- `followings`
- `followers`
- `posts`
- `likesReceived`
- `favsReceived`

The legacy `/api/v1/interact/reaction` counter endpoint is removed. Do not keep
it as a compatibility wrapper, disabled route, or unsupported-operation stub.
The replacement entry points are the zhiguang-style `/api/v1/action/*` endpoints
only.

### Comment API After Counter Removal

Comment creation, deletion, listing, reply notification, mention notification,
and content storage remain business capabilities. Comment counters do not.
`COMMENT_LIKED` notification is removed together with comment-like counting.

Rules:

- Comment APIs must not call the counter service for `like` or `reply` counts.
- Comment DTOs must not present `like_count` or `reply_count` as authoritative
  counter-system values.
- Comment DTO fields representing `like_count`, `reply_count`, `liked`, or any
  other comment counter are removed from public API responses touched by this
  replacement.
- No new code may increment `interaction_comment.like_count` or
  `interaction_comment.reply_count` as part of this counter replacement.

## Redis Contract

### Bitmap Fact Keys

Post like/fav state is stored in Redis bitmaps:

```text
bm:{metric}:post:{postId}:{chunk}
```

Examples:

```text
bm:like:post:123:0
bm:fav:post:123:0
```

Rules:

- `metric` is `like` or `fav`.
- `chunk` and bit offset are derived from `userId`.
- Bitmap state is the source of truth for whether a user liked or favorited a
  post.
- Duplicate like/fav requests do not produce duplicate deltas.
- Bitmap shard discovery for rebuild uses the zhiguang bitmap key family and
  scans `bm:{metric}:post:{postId}:*` with Redis `SCAN`, not `KEYS`.
- Rebuild must never depend on MySQL reaction rows.

### Aggregation Keys

Object counter deltas are accumulated in Redis Hash buckets:

```text
agg:v1:post:{postId}
```

Rules:

- Hash field is the zhiguang object SDS slot index.
- Hash value is accumulated delta.
- Kafka consumers write deltas here.
- A scheduled flusher folds these deltas into the SDS snapshot.
- The strict key format is the single zhiguang key `agg:v1:post:{postId}`.
- Nexus sharded aggregation buckets are removed.
- The goal is behavioral and operational parity with `zhiguang_be`, not
  hot-object optimization.

### Object Snapshot Keys

Post object counter snapshots use fixed-length SDS binary strings:

```text
cnt:v1:post:{postId}
```

V1 layout:

- slot `0`: `read` reserved
- slot `1`: `like`
- slot `2`: `fav`
- slot `3`: `comment` reserved, inactive
- slot `4`: `repost` reserved, inactive

Each slot is a 4-byte big-endian non-negative integer.

### User Snapshot Keys

User counters use fixed-length SDS binary strings:

```text
ucnt:{userId}
```

V1 layout:

- slot `1`: `followings`
- slot `2`: `followers`
- slot `3`: `posts`
- slot `4`: `likesReceived`
- slot `5`: `favsReceived`

Each slot is a 4-byte big-endian non-negative integer. The public naming follows
zhiguang wording even if current Nexus DTOs have different names.

### Relation Cache Keys

Relation list cache keys stay aligned with zhiguang:

```text
uf:flws:{userId}
uf:fans:{userId}
```

These ZSets are cache/projection aids, not counter truth.

## Write Flows

### Post Like/Fav

1. The action controller resolves the authenticated user.
2. The object counter service validates target type `post`.
3. The service computes bitmap chunk and bit offset from `userId`.
4. A Redis Lua script atomically applies `GETBIT/SETBIT` semantics.
5. If state does not change, the request returns `changed=false` and emits no
   counter event.
6. If state changes, the service emits a Kafka `CounterEvent` to
   `counter-events` and publishes a local Spring `CounterEvent`.
7. The Kafka consumer increments `agg:v1:post:{postId}` for the changed slot.
8. The scheduled flusher folds `agg` deltas into `cnt:v1:post:{postId}`.
9. The local listener looks up the post author and increments
   `likesReceived` or `favsReceived` in `ucnt:{authorId}`.
10. Feed/detail cache side effects update post `like/fav` display values as a
    non-authoritative cache optimization.

Failure rules:

- If Redis bitmap toggle fails, the operation fails and emits no event.
- If Kafka publish fails after bitmap state changes, bitmap remains truth. The
  implementation must not write MySQL reaction rows as compensation.
- Local Spring-event side effects are non-authoritative. Failure to update
  `likesReceived`, `favsReceived`, or feed cache must not roll back bitmap truth.
- `cnt:*` can lag `bm:*`; user-facing count freshness is eventual.

### Follow/Unfollow

1. The relation service persists the source relation business fact.
2. The relation event path updates the follower projection and relation ZSet
   caches.
3. Effective follow edges increment:
   - source user `followings`
   - target user `followers`
4. Effective unfollow edges decrement the same slots.

This follows zhiguang's user counter model while using Nexus relation tables as
the business fact source.

Boundary rules:

- Follow/follower counters are not object counters and never use bitmap truth.
- Relation list caches are not counter truth.
- The processor uses MySQL operations to detect effective follow/unfollow edges
  and must not reintroduce `user_counter_repair_outbox`.
- Normal path must update `ucnt` through the user counter service, not by direct
  Redis string manipulation in relation code.

### Post Publish/Delete

1. Content publishing or deletion must detect real published-state transitions.
2. A transition into published increments author `posts`.
3. A transition out of published decrements author `posts`.
4. Duplicate publish/delete events must not produce duplicate deltas.

The edge detection belongs in the content business transition, not in a Nexus
counter-specific `post_counter_projection` table.

Post edge-detection rules:

- A post contributes to `USER.posts` only while it is in the published state used
  by feed/detail visibility.
- The code that changes publish state must know the previous state and the new
  state in the same business operation.
- `posts +1` is allowed only for a real non-published to published transition.
- `posts -1` is allowed only for a real published to non-published transition.
- Retrying the same publish/delete request must not apply a second delta.
- Replacing `post_counter_projection` with another counter-specific projection
  table is forbidden. If state-edge detection needs storage, use the content
  table's own status/version/update result.

## Read And Rebuild Flows

### Object Counter Reads

Normal read:

1. Read `cnt:v1:post:{postId}`.
2. Decode requested `like/fav` slots.
3. Return decoded values.

Malformed or missing single-object read:

1. Acquire rebuild guard and lock.
2. Scan known bitmap shards for requested metrics.
3. Run `BITCOUNT` and sum the shard values.
4. Write a new SDS snapshot.
5. Clear overlapping aggregation fields to avoid double application.
6. Return rebuilt values.

Batch reads do not rebuild. Missing or malformed snapshots return zero values to
avoid feed-triggered rebuild storms.

Object rebuild rules:

- Rebuild is allowed only from Redis bitmap shards.
- Rebuild must cover exactly requested active metrics: `like`, `fav`, or both.
- Rebuild must write all fixed SDS slots, with inactive slots set to zero.
- Rebuild must clear only overlapping active aggregation fields for the rebuilt
  object.
- Rebuild must not create or consult MySQL reaction tables.

### User Counter Reads

Normal read:

1. Read `ucnt:{userId}`.
2. Decode five slots.
3. Return public counters.

Malformed or missing read:

1. Rebuild all five user counters.
2. Re-read and return decoded values.
3. If rebuild fails, return zeros rather than failing the API.

User rebuild sources:

- `followings`: count active source-side relation facts.
- `followers`: count active follower facts.
- `posts`: count published posts by author.
- `likesReceived`: list the author's published posts, read each post's `like`
  counter, and sum.
- `favsReceived`: list the author's published posts, read each post's `fav`
  counter, and sum.

Sampled verification compares relation slots with MySQL relation facts and
triggers rebuild on mismatch. This mirrors zhiguang's self-healing behavior.

User rebuild rules:

- Rebuild writes all five user slots in one SDS payload.
- `followings` must come from active outbound follow facts.
- `followers` must come from active inbound follower facts.
- `posts` must come from published content owned by the user.
- `likesReceived` and `favsReceived` must be recomputed from owned published
  posts by reading object `like/fav` counters.
- Rebuild must not preserve stale `likesReceived` or `favsReceived` merely
  because they existed in the old `ucnt` snapshot.
- A failed child object-counter read contributes zero for that post and must not
  fail the whole user profile API.

## Deletion And Migration Scope

The implementation removes these from the active code path:

- comment like counters
- comment reply counters
- comment `like_count/reply_count` updates by the counter system
- RabbitMQ primary object counter aggregation
- Count Redis module command assumptions
- `post_counter_projection`
- `user_counter_repair_outbox`
- reaction count tables and reaction event-log replay semantics
- search-index count-field propagation

Deletion rules:

- Final schema must not create removed counter tables.
- Migrations must include explicit drops for removed counter tables or fields
  when they exist in current schema.
- If a physical drop cannot be executed in the local test environment, the
  implementation plan must still contain the DDL task. Leaving a removed table
  in final schema is not acceptable.
- Java code, mapper XML, tests, scheduled jobs, message consumers, and config
  must not reference removed counter capabilities.
- Build and runtime config must not load the Count Redis module for the counter
  system.
- The `count-redis-module` source directory is removed from this project.
- Search documents and mappings must not contain count fields populated by the
  counter system. Existing historical index fields, if any, are outside this
  replacement and must not receive updates.

## Forbidden Implementation Patterns

The implementation must not:

- add a generic `ReactionType` counter path for future extensibility;
- keep `COMMENT.like` as a hidden branch in `ReactionLikeService`;
- map `COMMENT.reply` to the reserved object `comment` SDS slot;
- write object counter truth to MySQL;
- rebuild object counters from MySQL or logs;
- keep RabbitMQ as the primary object counter aggregation path;
- keep Count Redis module commands as the counter storage API;
- preserve old reaction event-log replay as a fallback;
- use `post_counter_projection` or an equivalent counter-only table for post
  count edge detection;
- preserve stale `like_received` or `favorite_received` during user rebuild;
- silently dual-write old and new counter structures;
- keep search-index count propagation connected to counter events;
- treat reserved SDS slots as product features.

## Naming And Mapping Rules

Use zhiguang names at the counter boundary:

- object metric names: `like`, `fav`
- user public names: `followings`, `followers`, `posts`, `likesReceived`,
  `favsReceived`
- Redis keys: `bm:*`, `agg:v1:*`, `cnt:v1:*`, `ucnt:*`

Existing Nexus names appear only at translation boundaries:

- `favoriteCount` maps to counter metric `fav`.
- `faved` maps to bitmap metric `fav`.
- Existing DTO names such as `likedPosts` must be renamed or explicitly mapped
  to `likesReceived`; they must not define a separate counter.

No implementation task should introduce a third synonym for the same counter.

## Consistency And Availability

Object `like/fav` consistency matches zhiguang:

- bitmap is the write-time truth
- SDS snapshot is eventually consistent
- Kafka or flush delay may temporarily lag display counts
- duplicate operations do not double count
- missing/malformed SDS can be rebuilt from bitmap truth
- rebuild is guarded to avoid hot-key storms

User counter consistency matches zhiguang at the semantic level:

- `ucnt` is the display snapshot
- normal updates use Redis Lua atomic increments/decrements
- relation and content business facts remain the rebuild source
- received like/fav counters derive from post object counters
- read-path rebuild and sampled verification restore corrupted snapshots

The main implementation risk is `posts`: after deleting `post_counter_projection`,
the content business layer must guarantee idempotent published-state edge
detection. That requirement is part of this design.

## Definition Of Done

Implementation is not complete until all of these are true:

- There is one object counter path for post `like/fav`: bitmap, Kafka event,
  `agg`, SDS.
- There is one user counter path for the five zhiguang user slots: `ucnt` plus
  rebuild.
- Post favorite exists end to end: action API, bitmap state, counter snapshot,
  feed/detail response, current-user state, and `favsReceived`.
- Comment counters are not active anywhere in the counter system.
- Count Redis module is not part of runtime counter storage.
- RabbitMQ is not part of object counter aggregation.
- Search index count propagation is removed.
- User rebuild recomputes `likesReceived/favsReceived` from object counters.
- Post count increments/decrements only on real publish-state edges.
- The final schema no longer creates removed counter tables.
- Tests cover active capabilities and removed capabilities.

## Testing Strategy

Object counter tests:

- `like/unlike/fav/unfav` are idempotent.
- Bitmap keys match `bm:{metric}:post:{postId}:{chunk}`.
- Changed state emits Kafka and local events.
- No-op state emits no event.
- Single read rebuilds missing/malformed SDS from bitmaps.
- Batch read does not rebuild and returns zero for malformed snapshots.

Aggregation tests:

- Kafka `counter-events` increments `agg:v1:post:{postId}`.
- Scheduled flush writes `cnt:v1:post:{postId}`.
- Failed flush leaves deltas for retry.
- Rebuild clears overlapping agg fields.

User counter tests:

- follow/unfollow changes update `followings/followers`.
- publish/delete changes update `posts` only on real state edges.
- post like/fav local events update `likesReceived/favsReceived`.
- malformed `ucnt` rebuilds all five slots from relation/content facts and
  object counters.

Removal tests:

- no active code references `COMMENT.reply` as a counter.
- no active code uses `COMMENT.like` as an object counter.
- no active code writes search-index count fields.
- old reaction count/replay components are absent or unreachable.

Integration tests:

- post like/unlike through HTTP.
- post fav/unfav through HTTP.
- feed/detail display `likeCount/favoriteCount/liked/faved`.
- user profile display `followings/followers/posts/likesReceived/favsReceived`.
- duplicate action requests do not double count.

Static/reference tests:

- no references to removed RabbitMQ object counter consumers;
- no references to Count Redis module commands in Java counter code;
- no references to `post_counter_projection` in active code;
- no references to `user_counter_repair_outbox` in active code;
- no search-index update path consumes counter events;
- no controller accepts comment targets for counter actions.
