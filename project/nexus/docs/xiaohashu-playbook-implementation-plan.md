# Nexus Xiaohashu Playbook Implementation Plan

This document is archived.

It described a staged implementation plan that included obsolete reaction assumptions such as:

- delayed reaction sync workers
- DB truth for reaction facts
- DB truth for reaction counts
- playbook mappings that no longer match the Nexus codebase

Those assumptions are no longer valid.

Current reaction architecture:

- Redis is the only online truth source for reaction state and count.
- MySQL stores only append-only `interaction_reaction_event_log`.
- RabbitMQ is the asynchronous handoff for event-log persistence.
- Recovery replays event-log rows back into Redis using checkpoints.

Authoritative current documents:

- `docs/superpowers/specs/2026-04-08-reaction-redis-truth-eventlog-design.md`
- `docs/superpowers/plans/2026-04-08-reaction-redis-truth-eventlog-implementation-plan.md`

Do not use this archived file as an execution plan.
