## ADDED Requirements

### Requirement: Class 2 processors SHALL NOT use full rebuild as the normal projection path

The system SHALL reserve user-counter full rebuilds for repair, migration, replay recovery, malformed snapshot handling, drift correction, or explicit operator actions. Normal Class 2 event projection SHALL use idempotent edge-based MySQL state transitions and durable counter delta outbox rows.

#### Scenario: Follow projection avoids full rebuild
- **WHEN** a normal follow or unfollow projection event is consumed successfully
- **THEN** the processor SHALL NOT call unguarded `rebuildAllCounters` for the source or target user and SHALL NOT synchronously mutate Redis counters

#### Scenario: Post projection avoids full rebuild
- **WHEN** a normal post publish, unpublish, or delete projection event is consumed successfully
- **THEN** the processor SHALL NOT call unguarded `rebuildAllCounters` for the author and SHALL NOT synchronously mutate Redis counters

### Requirement: User-counter rebuild requests SHALL be MySQL-backed, coalesced, and bounded

The system SHALL provide a MySQL-backed repair request path that coalesces repeated rebuild requests for the same user and rate-limits actual rebuild execution.

#### Scenario: Repeated rebuild requests collapse for one user
- **WHEN** multiple rebuild requests are made for the same user within the configured coalescing window
- **THEN** the system SHALL store one durable repair request row for that user and SHALL execute at most one actual full user-counter rebuild for that user in that window

#### Scenario: Processing rebuild request is recovered after worker crash
- **WHEN** a rebuild request remains `PROCESSING` beyond the configured stale-processing timeout
- **THEN** the system SHALL make that request eligible for a later bounded worker run without requiring manual repair

#### Scenario: Dirty user rebuild worker processes bounded batches
- **WHEN** dirty users are queued for counter repair
- **THEN** the repair worker SHALL process them in bounded batches and SHALL leave unprocessed users queued for a later run

#### Scenario: Redis loss does not delete repair intent
- **WHEN** Redis data is flushed after a user-counter rebuild request is recorded
- **THEN** the system SHALL retain the repair request in MySQL

### Requirement: Redis counter delta application SHALL be replayable after projection commit

The system SHALL separate successful MySQL projection-state mutation from Redis snapshot mutation by storing durable `user_counter_delta_outbox` rows and applying them through a retryable worker.

#### Scenario: Projection success survives Redis outage
- **WHEN** a Class 2 projection state change commits while Redis is unavailable
- **THEN** the system SHALL retain the pending counter delta in MySQL and SHALL allow the worker to apply it after Redis recovers

#### Scenario: Consumer completion follows durable delta enqueue
- **WHEN** a normal Class 2 event is consumed
- **THEN** the consumer SHALL mark the reliable consumer record done only after projection state and required delta outbox rows have committed successfully

#### Scenario: Pending delta backlog is bounded per worker run
- **WHEN** pending counter deltas exist
- **THEN** the delta worker SHALL process only the configured batch size and SHALL leave remaining rows eligible for later runs

#### Scenario: Unknown delta apply result schedules repair
- **WHEN** a counter delta row remains `PROCESSING` beyond the configured stale-processing timeout
- **THEN** the system SHALL NOT replay the delta and SHALL schedule a durable Class 2 rebuild request for the affected user

### Requirement: Read-time rebuild SHALL use short synchronous repair with durable async fallback

The system SHALL keep read-time rebuild behavior guarded by validation, locking, rate limiting, or backoff and SHALL bound user-facing read latency by falling back to a durable async repair request when immediate repair cannot complete quickly.

#### Scenario: Missing user snapshot repairs within the read budget
- **WHEN** a user counter read finds a missing or malformed snapshot
- **THEN** the system SHALL attempt a guarded Class 2 repair within the configured short timeout and SHALL return the repaired counters when repair succeeds inside that budget

#### Scenario: Missing user snapshot repair exceeds the read budget
- **WHEN** a user counter read finds a missing or malformed snapshot and guarded repair cannot complete within the configured short timeout
- **THEN** the system SHALL record a durable async rebuild request and SHALL return a safe fallback without creating unlimited concurrent rebuilds for the same user
