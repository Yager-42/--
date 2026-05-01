## MODIFIED Requirements

### Requirement: Class 2 processors SHALL NOT use full rebuild as the normal projection path

The system SHALL reserve user-counter full rebuilds for repair, migration, replay recovery, malformed snapshot handling, drift correction, or explicit operator actions. Normal Class 2 event projection SHALL use synchronous Redis SDS atomic increments within the existing transaction boundary.

#### Scenario: Follow projection avoids full rebuild
- **WHEN** a normal follow or unfollow projection event is consumed successfully
- **THEN** the processor SHALL NOT call unguarded `rebuildAllCounters` for the source or target user and SHALL instead apply atomic Redis SDS increments for `following` and `follower`

#### Scenario: Post projection avoids full rebuild
- **WHEN** a normal post publish, unpublish, or delete projection event is consumed successfully
- **THEN** the processor SHALL NOT call unguarded `rebuildAllCounters` for the author and SHALL instead apply atomic Redis SDS increments for `post` on real published-state edges

### Requirement: Read-time rebuild SHALL remain guarded by locking and rate limiting

The system SHALL keep read-time rebuild behavior guarded by distributed locking, rate limiting, and backoff as currently implemented, and SHALL limit Class 2 repair scope to `following`, `follower`, and `post` only.

#### Scenario: Missing user snapshot repairs within existing guards
- **WHEN** a user counter read finds a missing or malformed snapshot
- **THEN** the system SHALL attempt a guarded Class 2 repair (excluding `like_received` enumeration) and SHALL return the repaired counters when repair succeeds

#### Scenario: Sampling verification triggers rebuild on drift
- **WHEN** the periodic sampling verification detects a mismatch between Redis snapshot values and MySQL COUNT for `following` or `follower`
- **THEN** the system SHALL trigger a guarded `rebuildAllCounters` to restore consistency from MySQL business truth

#### Scenario: Redis unavailable during normal projection rolls back consistently
- **WHEN** a Redis INCRBY operation fails during normal Class 2 projection
- **THEN** the enclosing Spring transaction SHALL roll back the MySQL projection state change and the failed event SHALL be retried by the outbox publisher
