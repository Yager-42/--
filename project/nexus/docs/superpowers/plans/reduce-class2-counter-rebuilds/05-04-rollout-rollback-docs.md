# Task 5.4 Rollout And Rollback Documentation Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Document rollout and rollback notes for the destructive Class 2 incremental projection path.

**Architecture:** This change has no compatibility migration. Rollout requires fresh projection/outbox/rebuild tables and acceptance of eventual Redis counter visibility. Rollback is operational rebuild from business truth, not data-compatible downgrade.

**Tech Stack:** Markdown documentation, OpenSpec validation.

---

## File Structure

- Create: `docs/operations/class2-counter-projection-rollout.md`
- Verify: `docs/nexus_final_mysql_schema.sql`
- Reference: `openspec/changes/reduce-class2-counter-rebuilds/design.md`

## Required Content

- Destructive change statement.
- New tables:
  - `post_counter_projection`
  - `user_counter_delta_outbox`
  - `user_counter_rebuild_request`
- Normal projection no longer calls full rebuild.
- Redis visibility is eventually consistent after delta worker drain.
- `like_received` is preserved during Class 2 repair and not synchronously recomputed.
- Rollout checks and rollback/rebuild steps.

### Task 1: Write Documentation

- [ ] **Step 1: Create doc skeleton**

```markdown
# Class 2 Counter Projection Rollout

## Scope

## Destructive Assumptions

## New Durable Tables

## Rollout Steps

## Health Checks

## Rollback / Recovery
```

- [ ] **Step 2: Add rollout steps**

Include:

```text
1. Deploy schema with new tables.
2. Deploy application code with processor writing delta outbox.
3. Enable delta worker and rebuild worker.
4. Verify pending delta backlog drains.
5. Verify sampled repair requests do not grow without bound.
```

- [ ] **Step 3: Add rollback statement**

State clearly: no data-compatible rollback. If reverting code, recreate projection tables and Redis snapshots from business truth.

### Task 2: Validate Docs And OpenSpec

- [ ] **Step 1: Run OpenSpec validation**

```powershell
openspec validate reduce-class2-counter-rebuilds
```

Expected: change is valid.

- [ ] **Step 2: Check status**

```powershell
openspec status --change reduce-class2-counter-rebuilds
```

Expected: task list displays current completion state.

- [ ] **Step 3: Commit docs**

```powershell
git add docs/operations/class2-counter-projection-rollout.md docs/nexus_final_mysql_schema.sql openspec/changes/reduce-class2-counter-rebuilds
git commit -m "docs: document class2 counter projection rollout"
```

## Ambiguity Review

No architecture choice remains. Rollback is destructive recovery from business truth; no compatibility bridge is promised.
