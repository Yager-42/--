## MODIFIED Requirements

### Requirement: Class 2 user counters SHALL use synchronous Redis atomic increments for normal events

The system SHALL maintain `USER.following`, `USER.follower`, and `USER.post` snapshots by calling existing Redis SDS atomic increment methods during normal Class 2 projection events, within the same transaction boundary as MySQL projection writes.

#### Scenario: Follow projection increments only on a new follower edge
- **WHEN** a follow projection event creates a follower projection row that did not previously exist (`saveFollowerIfAbsent` returns true)
- **THEN** the system SHALL atomically increment the source user's `following` counter by one and the target user's `follower` counter by one via Redis SDS Lua script

#### Scenario: Duplicate follow projection does not increment counters
- **WHEN** a follow projection event is redelivered after the follower projection row already exists (`saveFollowerIfAbsent` returns false)
- **THEN** the system SHALL NOT increment the source user's `following` counter or the target user's `follower` counter

#### Scenario: Unfollow projection decrements only on an existing follower edge
- **WHEN** an unfollow projection event removes a follower projection row that previously existed (`deleteFollowerIfPresent` returns true)
- **THEN** the system SHALL atomically decrement the source user's `following` counter by one and the target user's `follower` counter by one via Redis SDS Lua script

#### Scenario: Missing unfollow edge does not decrement counters
- **WHEN** an unfollow projection event finds no follower projection row to remove (`deleteFollowerIfPresent` returns false)
- **THEN** the system SHALL NOT decrement the source user's `following` counter or the target user's `follower` counter

#### Scenario: Redis failure rolls back MySQL projection
- **WHEN** the Redis atomic increment fails with a connection exception during normal projection
- **THEN** the enclosing Spring transaction SHALL roll back the MySQL projection state change, leaving both systems consistent

### Requirement: Post user counters SHALL use durable published-state edge projection

The system SHALL maintain `USER.post` by comparing MySQL-backed `post_counter_projection` published state for each post with the incoming target state, rejecting stale event ids, and applying synchronous Redis atomic increments only for real state-edge transitions.

#### Scenario: Post becomes projected as published from a fresh event
- **WHEN** a post projection changes durable projected state from unpublished to published
- **THEN** the system SHALL atomically increment the author's `post` counter by one via Redis SDS Lua script

#### Scenario: Post becomes projected as unpublished from a fresh event
- **WHEN** a post projection changes durable projected state from published to unpublished
- **THEN** the system SHALL atomically decrement the author's `post` counter by one via Redis SDS Lua script

#### Scenario: Repeated post projection state is a no-op
- **WHEN** a post projection event has the same target published state as the durable projected state
- **THEN** the system SHALL NOT increment or decrement the author's `post` counter

#### Scenario: Stale post projection event is rejected
- **WHEN** a post projection event has `relationEventId` less than or equal to the durable `last_event_id` stored for that `post_id`
- **THEN** the system SHALL leave the projected state unchanged and SHALL NOT modify the author's `post` counter

#### Scenario: Post projection state is stored durably
- **WHEN** a post projection changes or confirms a post's projected published state
- **THEN** the system SHALL persist the projected state in MySQL keyed by `post_id`

#### Scenario: Post author is a business invariant
- **WHEN** a post projection row already exists for a `post_id`
- **THEN** the system SHALL treat the stored `author_id` as the authoritative invariant for that post and SHALL NOT implement author migration behavior as part of counter projection

### Requirement: Class 1 derived user counters SHALL NOT block Class 2 projection or repair

The system SHALL treat `USER.like_received` as a Class 1 display-derived value and SHALL NOT require best-effort `like_received` full recomputation before Class 2 projection or Class 2 repair can complete.

#### Scenario: Class 2 repair preserves readable like-received
- **WHEN** a user Class 2 snapshot repair is requested for `following`, `follower`, and `post`
- **THEN** the system SHALL preserve the existing `like_received` snapshot value when the previous snapshot is readable and SHALL NOT synchronously enumerate the user's posts to recompute it

#### Scenario: Class 2 repair has no readable like-received source
- **WHEN** a user Class 2 snapshot repair is requested and no previous user snapshot can be decoded
- **THEN** the system SHALL use zero for `like_received` and SHALL allow a separate best-effort process to refresh it later
