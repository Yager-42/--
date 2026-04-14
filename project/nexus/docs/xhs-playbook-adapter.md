# Nexus XHS Playbook Adapter

This document is archived.

It captured an earlier adaptation plan that assumed the reaction subsystem used:

- `LikeUnlikePersistConsumer`
- `PostLikeCountAggregateConsumer`
- `PostLikeCount2DbConsumer`
- `interaction_reaction`
- `interaction_reaction_count`

That model is obsolete in the current Nexus codebase.

Current reaction architecture:

- Redis is the only online truth source.
- MySQL stores only append-only `interaction_reaction_event_log`.
- RabbitMQ is used only for asynchronous handoff and side effects.
- Final reaction count is not stored in MySQL.

Authoritative current documents:

- `docs/superpowers/specs/2026-04-08-reaction-redis-truth-eventlog-design.md`
- `docs/superpowers/plans/2026-04-08-reaction-redis-truth-eventlog-implementation-plan.md`

Do not follow this archived file for implementation.
