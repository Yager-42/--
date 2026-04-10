# Nexus Social Recommend Fusion Plan

This document is archived.

It described an older DB-truth reaction design based on:

- `interaction_reaction`
- `interaction_reaction_count`
- DB fallback for reaction reads
- DB-backed liker list as the primary capability

That is no longer the current Nexus implementation.

Current reaction architecture:

- Redis is the only online truth source for reaction state and count.
- MySQL stores only append-only `interaction_reaction_event_log`.
- RabbitMQ is the asynchronous handoff for event-log persistence.
- Recovery rebuilds Redis from MySQL event log checkpoints.

Authoritative current documents:

- `docs/superpowers/specs/2026-04-08-reaction-redis-truth-eventlog-design.md`
- `docs/superpowers/plans/2026-04-08-reaction-redis-truth-eventlog-implementation-plan.md`

Do not use this archived file as implementation guidance.
