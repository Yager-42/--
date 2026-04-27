## ADDED Requirements

### Requirement: Class 2 user counters SHALL use durable incremental projection for normal events

The system SHALL maintain `USER.following`, `USER.follower`, and `USER.post` snapshots by recording idempotent MySQL-backed delta outbox rows during normal Class 2 projection events and applying those rows asynchronously to Redis snapshots instead of rebuilding all counters for every event.

#### Scenario: Follow projection enqueues increments only on a new follower edge
- **WHEN** a follow projection event creates a follower projection row that did not previously exist
- **THEN** the system SHALL enqueue durable delta rows for the source user's `following` counter by one and the target user's `follower` counter by one in the same MySQL transaction

#### Scenario: Duplicate follow projection does not enqueue counter deltas
- **WHEN** a follow projection event is redelivered after the follower projection row already exists
- **THEN** the system SHALL NOT enqueue new counter delta rows for the source user's `following` counter or the target user's `follower` counter

#### Scenario: Unfollow projection enqueues decrements only on an existing follower edge
- **WHEN** an unfollow projection event removes a follower projection row that previously existed
- **THEN** the system SHALL enqueue durable delta rows for the source user's `following` counter by minus one and the target user's `follower` counter by minus one in the same MySQL transaction

#### Scenario: Missing unfollow edge does not enqueue counter deltas
- **WHEN** an unfollow projection event finds no follower projection row to remove
- **THEN** the system SHALL NOT enqueue new counter delta rows for the source user's `following` counter or the target user's `follower` counter

### Requirement: Post user counters SHALL use durable published-state edge projection

The system SHALL maintain `USER.post` by comparing MySQL-backed `post_counter_projection` published state for each post with the incoming target state, rejecting stale event ids, and enqueueing only real state-edge deltas.

#### Scenario: Post becomes projected as published from a fresh event
- **WHEN** a post projection changes durable projected state from unpublished to published
- **THEN** the system SHALL enqueue a durable delta row for the author's `post` counter by one

#### Scenario: Post becomes projected as unpublished from a fresh event
- **WHEN** a post projection changes durable projected state from published to unpublished
- **THEN** the system SHALL enqueue a durable delta row for the author's `post` counter by minus one

#### Scenario: Repeated post projection state is a no-op
- **WHEN** a post projection event has the same target published state as the durable projected state
- **THEN** the system SHALL NOT enqueue a new `post` counter delta row

#### Scenario: Stale post projection event is rejected
- **WHEN** a post projection event has `relationEventId` less than or equal to the durable `last_event_id` stored for that `post_id`
- **THEN** the system SHALL leave the projected state unchanged and SHALL NOT enqueue a `post` counter delta row

#### Scenario: Post projection state is stored durably
- **WHEN** a post projection changes or confirms a post's projected published state
- **THEN** the system SHALL persist the projected state in MySQL keyed by `post_id`

#### Scenario: Post author is a business invariant
- **WHEN** a post projection row already exists for a `post_id`
- **THEN** the system SHALL treat the stored `author_id` as the authoritative invariant for that post and SHALL NOT implement author migration behavior as part of counter projection

### Requirement: Class 2 counter delta outbox SHALL be durable and safe under unknown Redis apply results

The system SHALL store each effective Class 2 counter slot delta in a MySQL `user_counter_delta_outbox` row before Redis mutation and SHALL apply pending rows to Redis snapshots through a bounded worker. The system SHALL NOT replay a stale `PROCESSING` delta as another Redis increment because the prior Redis side effect is unknown.

#### Scenario: Delta rows are idempotent per source event and counter slot
- **WHEN** the same effective projection event attempts to enqueue the same counter slot delta more than once
- **THEN** the system SHALL store at most one row for `(source_event_id, counter_user_id, counter_type)`

#### Scenario: Delta worker applies Redis snapshot increments
- **WHEN** the delta worker drains a pending row
- **THEN** the system SHALL mark the row `PROCESSING`, apply the delta to the matching Redis user snapshot slot, and mark the row `DONE` after a successful Redis mutation

#### Scenario: Delta worker failures remain retryable
- **WHEN** applying a delta row to Redis fails
- **THEN** the system SHALL mark the row `FAIL`, increment retry metadata, set a future `next_retry_time`, and retry it in a later bounded worker run

#### Scenario: Stale processing delta rows become repair requests instead of replayed increments
- **WHEN** a delta row remains `PROCESSING` beyond the configured stale-processing timeout
- **THEN** the system SHALL mark the delta row as an unknown-result terminal state, SHALL NOT re-apply the delta, and SHALL record a durable coalesced Class 2 rebuild request for the affected user

### Requirement: Class 1 derived user counters SHALL NOT block Class 2 projection or repair

The system SHALL treat `USER.like_received` as a Class 1 display-derived value and SHALL NOT require best-effort `like_received` full recomputation before Class 2 projection or Class 2 repair can complete.

#### Scenario: Class 2 repair preserves readable like-received
- **WHEN** a user Class 2 snapshot repair is requested for `following`, `follower`, and `post`
- **THEN** the system SHALL preserve the existing `like_received` snapshot value when the previous snapshot is readable and SHALL NOT synchronously enumerate the user's posts to recompute it

#### Scenario: Class 2 repair has no readable like-received source
- **WHEN** a user Class 2 snapshot repair is requested and no previous user snapshot can be decoded
- **THEN** the system SHALL use zero for `like_received` and SHALL allow a separate best-effort process to refresh it later
