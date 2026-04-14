## ADDED Requirements

### Requirement: Count Redis SHALL rebuild missing or malformed snapshots from truth sources

The system SHALL detect missing or malformed Count Redis SDS snapshots during reads and SHALL rebuild them from the appropriate truth source using locking, rate limiting, and backoff controls.

#### Scenario: Object snapshot rebuilds from bitmap truth
- **WHEN** an object snapshot for `POST.like` or `COMMENT.like` is missing or malformed during a read
- **THEN** the system SHALL rebuild the snapshot from Count Redis bitmap shards for that object and SHALL clear overlapping aggregation state for rebuilt slots

#### Scenario: User snapshot rebuilds from relation truth
- **WHEN** a user snapshot for follow counters is missing or malformed during a read
- **THEN** the system SHALL rebuild the snapshot from relation truth and SHALL write the repaired snapshot back to Count Redis

### Requirement: Count Redis SHALL retain replayable counter logs outside Redis snapshots

The system SHALL persist replayable counter deltas in MySQL event logs and SHALL maintain replay checkpoints separately from Count Redis snapshots.

#### Scenario: Effective counter change creates replay record
- **WHEN** a supported counter state change is effective
- **THEN** the system SHALL persist or enqueue a replayable event-log record describing the delta, target, actor, and resulting desired state

#### Scenario: Replay job resumes from checkpoint
- **WHEN** a replay runner starts after a previous partial replay
- **THEN** the system SHALL continue from the last stored replay checkpoint instead of replaying already-applied events from the beginning

### Requirement: Count Redis SHALL support Redis-first asynchronous aggregation without Kafka

The system SHALL keep Redis fact writes on the synchronous path and SHALL use the existing non-Kafka messaging stack to fan out aggregation work asynchronously.

#### Scenario: Effective like write publishes asynchronous aggregation work
- **WHEN** a supported like write succeeds in Count Redis
- **THEN** the system SHALL publish asynchronous aggregation work through the existing messaging stack without requiring Kafka to be present
