# Nexus Editorial Social Capability Expansion Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expand the current Nexus Vue frontend to cover the next backend-aligned user capabilities: auth completion, interaction and relation flows, and content production workflows.

**Architecture:** Extend the current Editorial Social shell instead of replacing it. Keep API access thin and typed, add focused view models for composer and relation data, and surface new capabilities inside existing page hierarchy plus one dedicated creation workspace route.

**Tech Stack:** Vue 3, TypeScript, Vite, Vue Router, Pinia, Axios, Tailwind CSS, Vitest, Vue Test Utils

---

## File Structure

- Modify: `src/router/index.ts`
- Modify: `src/services/api/authApi.ts`
- Create: `src/services/api/interactionApi.ts`
- Create: `src/services/api/relationApi.ts`
- Modify: `src/services/api/contentApi.ts`
- Modify: `src/services/api/profileApi.ts`
- Modify: `src/services/mock/devSession.ts`
- Modify: `src/stores/auth.ts`
- Create: `src/stores/composer.ts`
- Modify: `src/types/auth.ts`
- Modify: `src/types/content.ts`
- Modify: `src/types/profile.ts`
- Modify: `src/types/viewModels.ts`
- Create: `src/components/profile/RelationSheet.vue`
- Create: `src/components/post/PostInteractionBar.vue`
- Create: `src/components/comment/CommentComposer.vue`
- Create: `src/components/content/ComposerWorkspace.vue`
- Create: `src/components/content/ComposerHistoryList.vue`
- Modify: `src/components/profile/ProfileHeader.vue`
- Modify: `src/components/feed/FeedCard.vue`
- Modify: `src/components/feed/FeedComposerEntry.vue`
- Modify: `src/components/post/PostDetailCard.vue`
- Modify: `src/views/MeView.vue`
- Modify: `src/views/ProfileView.vue`
- Modify: `src/views/PostDetailView.vue`
- Create: `src/views/ComposerView.vue`
- Create: `src/services/api/authApi.spec.ts`
- Create: `src/services/api/interactionApi.spec.ts`
- Create: `src/services/api/relationApi.spec.ts`
- Create: `src/services/api/contentApi.spec.ts`
- Create: `src/stores/composer.spec.ts`
- Modify: `src/views/ProfileView.spec.ts`
- Modify: `src/views/PostDetailView.spec.ts`
- Create: `src/views/MeView.spec.ts`
- Create: `src/views/ComposerView.spec.ts`

## Chunk 1: Auth Completion and Routing

### Task 1: Add auth completion API coverage and account actions

**Files:**
- Modify: `src/services/api/authApi.ts`
- Modify: `src/stores/auth.ts`
- Modify: `src/types/auth.ts`
- Create: `src/services/api/authApi.spec.ts`
- Create: `src/views/MeView.spec.ts`
- Modify: `src/views/MeView.vue`

- [ ] Write a failing test for `logout` and `changePassword` API wrappers.
- [ ] Run `npm test -- src/services/api/authApi.spec.ts` and confirm it fails for missing exports.
- [ ] Implement typed payloads and wrappers for `/api/v1/auth/logout` and `/api/v1/auth/password/change`.
- [ ] Write a failing `MeView` test that expects password change feedback and logout action wiring.
- [ ] Run `npm test -- src/views/MeView.spec.ts` and confirm the new behavior fails first.
- [ ] Implement account action cards in `MeView` with inline form feedback and local logout cleanup after remote success.
- [ ] Re-run both targeted tests until green.

### Task 2: Add composer route entry

**Files:**
- Modify: `src/router/index.ts`
- Modify: `src/components/feed/FeedComposerEntry.vue`
- Create: `src/views/ComposerView.vue`
- Create: `src/views/ComposerView.spec.ts`

- [ ] Write a failing route/view test for the composer workspace route.
- [ ] Run `npm test -- src/views/ComposerView.spec.ts` and confirm it fails.
- [ ] Add `/compose` route and convert the timeline composer entry into a real navigation trigger.
- [ ] Re-run the targeted test until green.

## Chunk 2: Interaction and Relation Flows

### Task 3: Add reaction, comment, pin, delete, and reply-list API coverage

**Files:**
- Create: `src/services/api/interactionApi.ts`
- Modify: `src/services/api/contentApi.ts`
- Modify: `src/types/content.ts`
- Create: `src/services/api/interactionApi.spec.ts`
- Create: `src/services/api/contentApi.spec.ts`

- [ ] Write failing API tests for reaction submission/state, comment submission, comment pin, comment delete, and reply list loading.
- [ ] Run targeted specs and confirm failures are due to missing functions or incorrect mapping.
- [ ] Implement thin typed API clients and DTO-to-view-model mapping for these capabilities.
- [ ] Re-run targeted API specs until green.

### Task 4: Surface interaction controls in post detail

**Files:**
- Create: `src/components/post/PostInteractionBar.vue`
- Create: `src/components/comment/CommentComposer.vue`
- Modify: `src/components/post/PostDetailCard.vue`
- Modify: `src/views/PostDetailView.vue`
- Modify: `src/views/PostDetailView.spec.ts`

- [ ] Write failing view tests for like state, comment submit, hot comments, reply expansion, and owner moderation actions.
- [ ] Run `npm test -- src/views/PostDetailView.spec.ts` and confirm the new interaction expectations fail.
- [ ] Implement the interaction bar and comment composer with optimistic but API-backed updates.
- [ ] Extend detail view loading to include hot comments and reply list expansion hooks.
- [ ] Re-run the detail view tests until green.

### Task 5: Add relation API coverage and profile relation UI

**Files:**
- Create: `src/services/api/relationApi.ts`
- Modify: `src/services/api/profileApi.ts`
- Modify: `src/types/profile.ts`
- Create: `src/components/profile/RelationSheet.vue`
- Modify: `src/components/profile/ProfileHeader.vue`
- Modify: `src/views/ProfileView.vue`
- Modify: `src/views/ProfileView.spec.ts`
- Create: `src/services/api/relationApi.spec.ts`

- [ ] Write failing API tests for follow, unfollow, followers, following, and relation state batch.
- [ ] Run `npm test -- src/services/api/relationApi.spec.ts` and confirm failure.
- [ ] Implement relation API client and relation list view models.
- [ ] Write failing profile view tests for follow toggle and followers/following sheet rendering.
- [ ] Run `npm test -- src/views/ProfileView.spec.ts` and confirm failure.
- [ ] Implement follow/unfollow controls and relation list sheet in the profile experience.
- [ ] Re-run relation API and profile view tests until green.

## Chunk 3: Content Production Workspace

### Task 6: Add content production API coverage and composer store

**Files:**
- Modify: `src/services/api/contentApi.ts`
- Create: `src/stores/composer.ts`
- Create: `src/stores/composer.spec.ts`
- Modify: `src/types/content.ts`
- Modify: `src/services/mock/devSession.ts`

- [ ] Write failing tests for save draft, sync draft, publish, schedule, cancel schedule, history, rollback, delete, and upload session.
- [ ] Run targeted API and store tests and confirm failures.
- [ ] Implement content production API wrappers and a focused composer store for workflow state.
- [ ] Re-run targeted tests until green.

### Task 7: Build the composer workspace UI

**Files:**
- Create: `src/components/content/ComposerWorkspace.vue`
- Create: `src/components/content/ComposerHistoryList.vue`
- Create: `src/views/ComposerView.vue`
- Create: `src/views/ComposerView.spec.ts`
- Modify: `src/types/viewModels.ts`

- [ ] Write failing composer view tests for draft save, publish, schedule, cancel schedule, history load, rollback, and delete affordances.
- [ ] Run `npm test -- src/views/ComposerView.spec.ts` and confirm failure.
- [ ] Implement the Editorial Social creation workspace with restrained controls, upload session hinting, and history management.
- [ ] Re-run the composer view tests until green.

## Final Verification

- [ ] Run `npm test`.
- [ ] Run `npm run build`.
- [ ] Manually review route map and top-level flows to ensure the new capabilities remain coherent with the existing Editorial Social shell.
