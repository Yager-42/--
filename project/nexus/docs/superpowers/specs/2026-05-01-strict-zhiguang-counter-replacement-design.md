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

If the legacy `/api/v1/interact/reaction` endpoint remains during migration, it
is only a compatibility wrapper for post `LIKE` and `UNLIKE`. It rejects comment
targets and any reaction type outside the strict post-like path. It does not
introduce a separate counter model.

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
10. Feed/detail cache side effects update post `like/fav` display values on a
    best-effort basis.

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

### Post Publish/Delete

1. Content publishing or deletion must detect real published-state transitions.
2. A transition into published increments author `posts`.
3. A transition out of published decrements author `posts`.
4. Duplicate publish/delete events must not produce duplicate deltas.

The edge detection belongs in the content business transition, not in a Nexus
counter-specific `post_counter_projection` table.

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

Database fields/tables that become unused must be removed from final schema and
migrations where practical. If a physical table drop is risky in the local dev
environment, the code removal still happens first and the DDL drop is tracked in
the implementation plan.

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

