## 1. Concurrency Root-Cause Isolation

- [x] 1.1 Instrument and trace `POST.like` add path for same-user concurrent requests to confirm where duplicate effective deltas are produced.
- [x] 1.2 Verify atomicity behavior of bitmap fact transition in `ReactionCachePort.applyAtomic` and document current race window.
- [x] 1.3 Confirm downstream event-log and snapshot aggregation only consume non-zero effective deltas for the target scenario.

## 2. Idempotency and Delta Semantics Fix

- [x] 2.1 Refactor post-like fact transition handling to guarantee transition-derived effective delta (`0->1` only once per user/post) under concurrency.
- [x] 2.2 Ensure duplicate `ADD` after liked state yields `delta=0` end-to-end and does not amplify snapshot aggregation.
- [x] 2.3 Keep API response contract unchanged while preserving Redis-first success semantics and existing async fanout boundaries.

## 3. Verification and Regression Coverage

- [x] 3.1 Add/adjust focused unit tests for concurrent duplicate add semantics around fact transition and delta derivation.
- [x] 3.2 Update real integration assertion path for `postLike_highConcurrencySmoke_shouldRemainConsistent` to validate terminal convergence and non-inflated counts.
- [x] 3.3 Run targeted Maven suites (`nexus-domain`, `nexus-infrastructure`, `nexus-trigger`, and the target real-it case) and record results for OpenSpec apply/verify.
