# post-like-concurrency-consistency Specification

## Purpose
TBD - created by archiving change fix-post-like-concurrency-consistency. Update Purpose after archive.
## Requirements
### Requirement: Concurrent Duplicate Post-Like Adds SHALL Converge to One Effective Like
For the same `(userId, postId, reactionType=LIKE)`, concurrent repeated `ADD` requests SHALL produce at most one effective like in counter state. Final Count Redis object snapshot for `POST.like` SHALL converge to `1` when only one unique liker exists.

#### Scenario: Concurrent duplicate ADD from same user
- **WHEN** a user sends multiple concurrent `ADD LIKE` requests to the same post with distinct request IDs
- **THEN** the system records at most one effective transition (`unliked -> liked`) and the final `POST.like` snapshot count converges to `1`

### Requirement: Effective Delta Derivation SHALL Follow Fact State Transition
`POST.like` aggregation and snapshot updates MUST be derived from actual fact state transitions, not from request intent. Duplicate `ADD` on already-liked state SHALL produce `delta=0` for counting side effects.

#### Scenario: Duplicate ADD after liked state already established
- **WHEN** additional `ADD LIKE` requests arrive after the user is already in liked state for the post
- **THEN** no new positive effective delta is emitted to counting aggregation and the terminal snapshot count remains unchanged

### Requirement: High-Concurrency Real Integration Verification SHALL Assert Final Consistency
Real integration verification for post-like concurrency SHALL assert eventual terminal consistency across object snapshot and effective event-log records for the single-user duplicate-add case.

#### Scenario: Real-it convergence check for single-user duplicate adds
- **WHEN** the high-concurrency post-like integration test runs for one liker and one post
- **THEN** the test verifies eventual convergence to `POST.like=1` and verifies event-log/counter assertions against effective deltas without allowing inflated terminal counts

