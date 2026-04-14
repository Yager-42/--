## Context

The current post-like path uses Redis bitmap fact writes plus Count Redis snapshot aggregation. Under concurrent repeated `ADD LIKE` from the same user to the same post, real integration verification shows inflated final snapshot values instead of idempotent convergence to `1`. The issue is visible in `ReactionHttpRealIntegrationTest#postLike_highConcurrencySmoke_shouldRemainConsistent`, while most unit/module tests remain green, indicating a race/integration semantic gap rather than a basic connectivity failure.

Constraints:

- Keep existing API contracts unchanged.
- Preserve Redis-first success semantics (event-log persistence is best-effort, not synchronous success criterion).
- Maintain current architecture layers (`nexus-domain`, `nexus-infrastructure`, `nexus-trigger`) without introducing new middleware dependencies.

## Goals / Non-Goals

**Goals:**

- Enforce idempotent final count behavior for concurrent repeated post-like add operations by the same user.
- Ensure effective deltas are applied exactly once in the counting chain for duplicate-like races.
- Make integration verification deterministic enough to catch semantic regressions in real-it runs.

**Non-Goals:**

- No redesign of the full count system or replay model.
- No migration of additional counters outside `POST.like` concurrency scope.
- No change to public endpoint payload contracts.

## Decisions

### 1) Treat bitmap fact transition as the only source of effective delta

All downstream count mutations for `POST.like` MUST be derived from actual fact state transition (`0 -> 1` for add, `1 -> 0` for remove), not from request intent. Duplicate `ADD` requests that observe already-liked state MUST produce `delta = 0` end-to-end.

Why this over request-driven delta:

- It aligns with idempotent business semantics.
- It removes ambiguity under concurrent retries and duplicate submissions.
- It keeps write-path correctness local to the fact store transition.

### 2) Ensure atomicity boundary at fact mutation + delta derivation

Fact mutation and effective delta derivation MUST remain in one atomic operation boundary per target user/target pair. Any aggregation/snapshot side effect must consume only the derived effective delta.

Why this over loosely-coupled read-modify-write:

- Concurrent `GETBIT`/`SETBIT` without robust atomic behavior can amplify deltas.
- Explicit atomic boundary minimizes race-induced double counting.

### 3) Keep asynchronous fanout, but make duplicate-like effects non-amplifying

Asynchronous fanout through existing MQ/event-log stays unchanged, but duplicate-like traffic with `delta = 0` MUST be non-amplifying in downstream counter effects. Verification should focus on final snapshot/event-log consistency for effective deltas.

Why this over making all side effects synchronous:

- Synchronous fanout would raise latency and alter accepted success contracts.
- The issue is correctness under concurrency, not async architecture choice itself.

### 4) Clarify test expectations around eventual consistency window

Real integration assertions should validate eventual convergence of snapshot/event-log to effective unique-like count. The test should continue to tolerate processing delay but not tolerate inflated terminal counts.

Why this over relaxing count assertions:

- Relaxing expected terminal value would hide data correctness bugs.
- Convergence-to-correctness is the intended contract for supported counters.

## Risks / Trade-offs

- [Atomic update complexity in Redis operations] -> Mitigation: constrain changes to minimal adapter boundary and add focused tests around transition-derived deltas.
- [Eventual consistency timing noise in real-it] -> Mitigation: assert terminal convergence with bounded await and explicit failure diagnostics.
- [Regression risk in related side effects] -> Mitigation: retain existing domain and trigger targeted suites and add/adjust only scope-relevant concurrency checks.

## Migration Plan

1. Add/adjust specs for post-like concurrency consistency semantics.
2. Implement minimal code-path corrections for transition-derived delta and side-effect gating.
3. Run targeted module tests (`domain`, `infrastructure`, `trigger`) plus the real-it concurrency case.
4. If real-it still fails, keep cleanup/destructive operations blocked and iterate on diagnostics.
5. Once convergence is stable, proceed with normal change verification and archive flow.

## Open Questions

- Whether final stabilization requires stricter atomic primitive usage in the Redis adapter or only assertion/consumer-side correction is still to be confirmed during implementation.
- Whether event-log count assertion should always be exactly one for this scenario or tied strictly to non-zero effective deltas in all future variants needs explicit spec wording.
