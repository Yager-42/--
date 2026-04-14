# count-object-state-and-snapshot Specification

## Purpose
TBD - created by archiving change align-count-system-with-zhiguang. Update Purpose after archive.
## Requirements
### Requirement: Count Redis SHALL store supported object counters in zhiguang-style fact and snapshot structures

The system SHALL store `POST.like` and `COMMENT.like` state in Count Redis bitmap shards and SHALL store object counter snapshots in fixed-slot SDS values. The system SHALL support `COMMENT.reply` as a Count Redis snapshot metric without a per-user bitmap fact layer.

#### Scenario: Like state change updates fact state and counter aggregation
- **WHEN** a user changes the like state for a post or comment and the state transition is effective
- **THEN** the system SHALL update the Count Redis bitmap fact for that user/object pair and SHALL record a corresponding counter delta for later snapshot aggregation

#### Scenario: Reply delta updates snapshot metric without bitmap state
- **WHEN** the system receives a root reply count change event for a comment
- **THEN** the system SHALL update the Count Redis reply aggregation path for that comment without creating or reading a per-user reply bitmap

### Requirement: Count Redis SHALL expose object counter reads from SDS snapshots

The system SHALL serve object counter reads from Count Redis SDS snapshots for supported object metrics and SHALL support batched reads for multiple objects in a single operation.

#### Scenario: Single object read returns snapshot counter
- **WHEN** a caller requests `LIKE` or `REPLY` count for a supported object target
- **THEN** the system SHALL return the value from the corresponding Count Redis snapshot slot

#### Scenario: Batch object read returns per-target counters
- **WHEN** a caller requests counters for multiple supported object targets
- **THEN** the system SHALL return a result keyed by target identity with each requested metric populated from Count Redis snapshots

### Requirement: Count Redis SHALL define an internal schema for object counter slots

The system SHALL use a code-defined schema for object counter slot positions and SHALL NOT depend on a runtime schema lookup in Redis or an external metadata service.

#### Scenario: Object schema lookup uses code-defined slots
- **WHEN** the counter adapter resolves which slot to read or write for an object metric
- **THEN** it SHALL use the compiled application schema mapping rather than fetching schema metadata from Redis or another service

