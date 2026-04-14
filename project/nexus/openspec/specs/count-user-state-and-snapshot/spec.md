# count-user-state-and-snapshot Specification

## Purpose
TBD - created by archiving change align-count-system-with-zhiguang. Update Purpose after archive.
## Requirements
### Requirement: Count Redis SHALL maintain fixed-slot user counter snapshots

The system SHALL store user counters in Count Redis fixed-slot SDS snapshots with slots for `following`, `follower`, `post`, `like_received`, and `favorite_received`.

#### Scenario: User snapshot contains all reserved slots
- **WHEN** the system initializes or rebuilds a user counter snapshot
- **THEN** it SHALL materialize all defined user counter slots, including reserved slots with zero values when no source data exists

### Requirement: Count Redis SHALL rebuild follow counters from relation truth

The system SHALL rebuild `following` and `follower` counters from the relation truth source rather than from legacy cache entries.

#### Scenario: Following snapshot is rebuilt from relation storage
- **WHEN** a user follow counter snapshot is missing, malformed, or explicitly rebuilt
- **THEN** the system SHALL query the relation truth source and SHALL write the rebuilt values back into the Count Redis user snapshot

### Requirement: Count Redis SHALL derive like-received counters from object like deltas

The system SHALL update `like_received` counters in Count Redis based on effective object like state changes attributed to the object owner.

#### Scenario: Post like increases creator like-received
- **WHEN** a post like transitions from absent to present
- **THEN** the system SHALL increment the author's `like_received` snapshot counter in Count Redis

#### Scenario: Comment unlike decreases creator like-received
- **WHEN** a comment like transitions from present to absent
- **THEN** the system SHALL decrement the comment author's `like_received` snapshot counter in Count Redis without allowing the stored value to become negative

