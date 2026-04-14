# Frontend API Alignment Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Align all currently connected `nexus-frontend` pages with `project/nexus/docs/frontend-api.md` using frontend-only API adapters and minimal consumer updates.

**Architecture:** Keep the existing Axios response-shell gate in `src/utils/http.ts`, then move all backend-contract handling into `src/api/*` via raw DTO types plus page-facing ViewModels. Views, components, and stores consume only normalized ViewModels and explicit pagination metadata, never raw backend DTOs.

**Tech Stack:** Vue 3, Pinia, Vue Router, TypeScript, Axios, Vite, vue-tsc

---

## File Structure Map

### Existing files to modify

- `src/api/types.ts`
  - Own shared API shell types, pagination metadata, and small reusable frontend-facing interfaces.
- `src/api/auth.ts`
  - Own auth request/response contracts and token mapping.
- `src/api/feed.ts`
  - Own feed raw DTOs, media parsing, and feed card ViewModel mapping.
- `src/api/user.ts`
  - Own profile raw DTOs, profile page mapping, and update payload alignment.
- `src/api/notification.ts`
  - Own notification raw DTOs, derived unread state, and notification row ViewModel mapping.
- `src/api/relation.ts`
  - Own relation raw DTOs, relation state enrichment, and relation row ViewModel mapping.
- `src/api/content.ts`
  - Own content detail raw DTOs and publish/draft payload mapping.
- `src/api/interact.ts`
  - Own reaction/comment raw DTOs, root comment mapping, and reply list mapping.
- `src/api/search.ts`
  - Own search raw DTOs, query param alignment, and search card ViewModel mapping.
- `src/store/feed.ts`
  - Own feed list pagination state using normalized feed list contract.
- `src/components/FeedContainer.vue`
  - Consume feed ViewModel names only.
- `src/components/PostCard.vue`
  - Only modify if normalized feed/search card input no longer matches the existing card interface.
- `src/components/NotificationItem.vue`
  - Consume normalized notification row fields and relation-safe click behavior.
- `src/components/CommentItem.vue`
  - Consume normalized comment display fields only.
- `src/components/FollowButton.vue`
  - Respect `FOLLOWING | NOT_FOLLOWING | UNKNOWN` state and disable on unknown.
- `src/views/Login.vue`
  - Consume aligned auth token response.
- `src/views/Profile.vue`
  - Consume normalized profile page ViewModel.
- `src/views/Notifications.vue`
  - Consume normalized notification list contract.
- `src/views/RelationList.vue`
  - Consume normalized relation list contract.
- `src/views/ContentDetail.vue`
  - Consume normalized detail/comment/reply contracts.
- `src/views/SearchResults.vue`
  - Consume normalized search card ViewModel.
- `src/views/Publish.vue`
  - Send documented publish payload and validate local auth prerequisites.

### No new runtime abstraction

- Do not add a global response rewrite layer.
- Do not add a new global store for API mapping.
- Keep mapping helpers inside the relevant API module unless a helper becomes shared by two or more modules and is purely local to `src/api`.

## Verification Baseline

- Primary automated verification command: `npm run build`
- Manual smoke target: login, feed, content detail, notifications, profile, relation list, search, publish request shape

## Chunk 1: Feed, Profile, Notification, Relation, and Search Read-side Contracts

### Task 1: Strengthen shared API contracts and auth mapping

**Files:**
- Modify: `src/api/types.ts`
- Modify: `src/api/auth.ts`
- Modify: `src/views/Login.vue`

- [ ] **Step 1: Read the spec and current auth usage**

Read:
- `docs/superpowers/specs/2026-03-24-frontend-api-alignment-design.md`
- `src/api/types.ts`
- `src/api/auth.ts`
- `src/views/Login.vue`

Expected: confirm only `token` and `userId` need to flow into `authStore`.

- [ ] **Step 2: Define shared frontend-facing API types**

Add exact TypeScript types for:
- raw auth token DTO
- normalized pagination metadata types used by read-side modules
- normalized relation state enum

Expected: `src/api/types.ts` exposes reusable types without introducing backend-specific leakage into views.

- [ ] **Step 3: Align auth API contract**

Update `src/api/auth.ts` to:
- type password login payload explicitly
- type register payload explicitly
- type SMS payload explicitly
- return normalized auth response aligned with documented `AuthTokenResponseDTO`

Expected: `loginWithPassword()` returns data with `token` and `userId` typed as consumed by the auth store.

- [ ] **Step 4: Align login view consumption**

Update `src/views/Login.vue` to consume the aligned auth response without `any`.

Expected: login flow still writes token and userId exactly once through `authStore.setToken`.

- [ ] **Step 5: Run build to catch contract drift**

Run: `npm run build`

Expected: build may still fail in non-auth modules, but auth-related type errors should be eliminated or move to the next unaligned consumer.

- [ ] **Step 6: Commit**

Run:
```bash
git add src/api/types.ts src/api/auth.ts src/views/Login.vue
git commit -m "refactor: align auth api contracts"
```

### Task 2: Align feed API, media parsing, and feed consumer contracts

**Files:**
- Modify: `src/api/feed.ts`
- Modify: `src/store/feed.ts`
- Modify: `src/components/FeedContainer.vue`
- Modify if needed: `src/components/PostCard.vue`

- [ ] **Step 1: Define raw feed DTO and normalized feed ViewModel**

Implement in `src/api/feed.ts`:
- documented raw feed item fields
- feed list container fields
- normalized feed card ViewModel

Expected: file clearly separates raw DTO from UI-facing shape.

- [ ] **Step 2: Implement defensive media parsing**

Add a local helper in `src/api/feed.ts` to parse `mediaInfo` into `string[]`.

Rules:
- JSON array of objects with `url`
- JSON array of strings
- object with `urls`
- raw string URL
- invalid input => `[]`

Expected: no mapper throws when `mediaInfo` is malformed.

- [ ] **Step 3: Implement feed list mapper**

Map:
- `authorNickname -> authorName`
- `text -> contentBody`
- `summary || truncated text -> contentTitle`
- `publishTime -> createTime`
- `likeCount -> reactionCount`
- `liked -> isLiked`
- default `commentCount = 0`

Normalize list pagination:
- `nextCursor || null`
- drop duplicate `postId` on append

Expected: `fetchTimeline()` returns normalized list data only.

- [ ] **Step 4: Update feed store to use normalized pagination contract**

Update `src/store/feed.ts` so:
- `posts` stores normalized feed items
- `nextCursor` uses `null` when absent
- empty page plus null cursor ends pagination
- append path de-duplicates by `postId`

Expected: feed pagination logic no longer depends on raw backend field names.

- [ ] **Step 5: Update feed container bindings**

Update `src/components/FeedContainer.vue` to consume only normalized names already produced by the API layer/store.

Expected: no template code references backend raw DTO fields.

- [ ] **Step 6: Verify PostCard input compatibility**

Read `src/components/PostCard.vue`.

Expected:
- if the existing card input shape still matches the normalized feed/search card data, leave it unchanged
- if a mismatch exists, update it in this task before running build

- [ ] **Step 7: Run build to catch remaining feed type errors**

Run: `npm run build`

Expected: feed-related type errors disappear; failures move to the next unaligned read-side modules.

- [ ] **Step 8: Commit**

Run:
```bash
git add src/api/feed.ts src/store/feed.ts src/components/FeedContainer.vue
git add src/components/PostCard.vue
git commit -m "refactor: align feed api mapping"
```

### Task 3: Align profile and notification read-side modules

**Files:**
- Modify: `src/api/user.ts`
- Modify: `src/api/notification.ts`
- Modify: `src/views/Profile.vue`
- Modify: `src/views/Notifications.vue`
- Modify: `src/components/NotificationItem.vue`

- [ ] **Step 1: Align profile API to page-level endpoint**

Update `src/api/user.ts` to:
- keep lightweight profile DTOs if still needed
- add `GET /user/profile/page` raw DTOs
- map to a normalized profile page ViewModel

Normalize:
- `bio = ''`
- `stats.likeCount = 0`
- `stats.followCount/followerCount` from relation block
- `riskStatus` from page response

Expected: profile page can stop stitching `profile + risk` manually.

- [ ] **Step 2: Update profile view**

Update `src/views/Profile.vue` to consume normalized profile page data only.

Expected: remove direct assumptions about `bio`, `stats`, and separate risk call shape.

- [ ] **Step 3: Align notification API**

Update `src/api/notification.ts` to:
- use documented request params `userId` and `cursor`
- define raw notification DTO
- map `bizType` into UI notification type
- derive `hasUnread` and UI-only `isRead`
- normalize list pagination with duplicate filtering by `notificationId`

Expected: views no longer guess sender profile fields from the backend contract.

- [ ] **Step 4: Update notifications view and row component**

Update:
- `src/views/Notifications.vue`
- `src/components/NotificationItem.vue`

Rules:
- render placeholder avatar
- use mapped sender label/content
- use `hasUnread` or derived `isRead`
- click routing uses normalized `targetId`

Expected: notification UI works without requiring undocumented sender fields.

- [ ] **Step 5: Run build to validate profile and notification modules**

Run: `npm run build`

Expected: profile/notification-related type errors are resolved; remaining failures move to relation/search/detail/comments/write-side modules.

- [ ] **Step 6: Commit**

Run:
```bash
git add src/api/user.ts src/api/notification.ts src/views/Profile.vue src/views/Notifications.vue src/components/NotificationItem.vue
git commit -m "refactor: align profile and notification contracts"
```

### Task 4: Align relation and search read-side modules

**Files:**
- Modify: `src/api/relation.ts`
- Modify: `src/api/search.ts`
- Modify: `src/components/FollowButton.vue`
- Modify: `src/views/RelationList.vue`
- Modify: `src/views/SearchResults.vue`

- [ ] **Step 1: Align relation API**

Update `src/api/relation.ts` to:
- use documented relation list contracts
- request `state/batch` for current rows
- store `relationState` as `FOLLOWING | NOT_FOLLOWING | UNKNOWN`
- log enrichment failure before degrading rows to `UNKNOWN`
- normalize `nextCursor` to `null`
- de-duplicate appended rows by `userId`

Expected: rows do not fake “not following” when enrichment fails, and diagnostics remain visible.

- [ ] **Step 2: Update follow button and relation list**

Update:
- `src/components/FollowButton.vue`
- `src/views/RelationList.vue`

Rules:
- disable follow action on `UNKNOWN`
- show empty bio fallback
- consume only normalized relation row fields

Expected: relation page renders safely and truthfully.

- [ ] **Step 3: Align search API and result card mapping**

Update `src/api/search.ts` to:
- use documented params `q`, `size`, `tags`, `after`
- define raw `SearchItemDTO`
- map to normalized search card ViewModel
- normalize `nextAfter` and `hasMore`
- de-duplicate appended rows by `id`

Expected: search API owns all query-param and pagination alignment.

- [ ] **Step 4: Update search results view**

Update `src/views/SearchResults.vue` to consume the mapped card data.

Expected: search results stop expecting feed-style field names.

- [ ] **Step 5: Run build to validate relation and search modules**

Run: `npm run build`

Expected: relation/search-related type errors are resolved; remaining failures should be isolated to detail/comments/write-side if any remain.

- [ ] **Step 6: Commit**

Run:
```bash
git add src/api/relation.ts src/api/search.ts src/components/FollowButton.vue src/views/RelationList.vue src/views/SearchResults.vue
git commit -m "refactor: align relation and search contracts"
```

## Chunk 2: Content Detail and Comments

### Task 5: Align content detail API and detail page

**Files:**
- Modify: `src/api/content.ts`
- Modify: `src/views/ContentDetail.vue`

- [ ] **Step 1: Define raw content detail DTO and normalized detail ViewModel**

Update `src/api/content.ts` to type documented detail fields and map them to the current detail page needs.

Expected: detail API module owns all `mediaInfo` parsing and detail field normalization.

- [ ] **Step 2: Update content detail view**

Update `src/views/ContentDetail.vue` to consume normalized detail fields only.

Rules:
- `authorNickname` and `authorAvatarUrl` must come through mapper output
- hero image derives from parsed media list
- `likeCount` initializes local optimistic state

Expected: detail page no longer handles raw backend parsing directly.

- [ ] **Step 3: Run build for detail page validation**

Run: `npm run build`

Expected: detail page type errors move from content fields to comment mapping if not yet aligned.

- [ ] **Step 4: Commit**

Run:
```bash
git add src/api/content.ts src/views/ContentDetail.vue
git commit -m "refactor: align content detail contracts"
```

### Task 6: Align root comments, replies, pinned comment rendering, and comment submission

**Files:**
- Modify: `src/api/interact.ts`
- Modify: `src/components/CommentItem.vue`
- Modify: `src/views/ContentDetail.vue`

- [ ] **Step 1: Define raw comment DTOs and normalized comment ViewModels**

Add documented raw types for:
- root comment rows
- pinned comment row
- reply rows
- comment list container
- reply list container
- comment create response

Add normalized display shape used by the current UI.

Expected: a single comment display shape exists for both root comments and replies.

- [ ] **Step 2: Align comment list and reply list fetchers**

Update `src/api/interact.ts`:
- `fetchComments(postId)` sends documented params including default `limit=20` and `preloadReplyLimit=2`
- add reply-list fetcher for `GET /comment/reply/list`
- de-duplicate pinned item by `commentId`
- normalize `nextCursor || null`

Expected: comments API exposes a stable list contract with pinned + items + cursor.

- [ ] **Step 3: Define pinned comment rendering strategy**

Update `src/views/ContentDetail.vue` to render:
- pinned comment in a dedicated pinned area when present
- root comment list separately
- de-duplicated root list if backend duplicates pinned in `items`

Expected: pinned comment is never silently lost or mixed back into the list by accident.

- [ ] **Step 4: Wire reply list fetcher into UI interaction**

Update `src/views/ContentDetail.vue` to own:
- reply expand/collapse state
- first reply fetch on expand
- `nextCursor` storage per root comment
- incremental reply loading per root comment
- normalized reply ViewModel consumption

Expected: reply-list alignment is visible in UI and not dead code in `src/api/interact.ts`.

- [ ] **Step 5: Align comment create request**

Update `postComment()` to send documented payload:
- `postId`
- `parentId?`
- `content`
- omit `commentId` by default

Expected: request shape matches the spec exactly.

- [ ] **Step 6: Update comment display component**

Update `src/components/CommentItem.vue` to consume normalized comment display fields only.

Expected: component no longer assumes a tiny ad hoc object shape different from the API layer.

- [ ] **Step 7: Update detail page comment flow**

Update `src/views/ContentDetail.vue` so:
- initial load uses normalized comment list
- optimistic insert uses normalized local row shape
- on create failure, UI rolls back
- if optimistic item cannot be trusted, trigger comment list reload

Expected: comment UI is consistent with the mapped API contract.

- [ ] **Step 8: Run build for comment alignment**

Run: `npm run build`

Expected: comment/detail type errors are resolved.

- [ ] **Step 9: Commit**

Run:
```bash
git add src/api/interact.ts src/components/CommentItem.vue src/views/ContentDetail.vue
git commit -m "refactor: align comment contracts"
```

## Chunk 3: Write-side Payload Alignment and Final Verification

### Task 7: Align publish and draft payloads with auth prerequisites

**Files:**
- Modify: `src/api/content.ts`
- Modify: `src/views/Publish.vue`
- Modify: `src/store/auth.ts` (only if a helper is needed to read current user id safely)

- [ ] **Step 1: Type documented publish and draft payloads exactly**

Update `src/api/content.ts` to align:
- publish payload includes optional `postId`, required `userId`, `title`, `text`, `mediaInfo`, `visibility`, optional `location`, optional `postTypes`
- draft payload includes `draftId`, `userId`, `title`, `contentText`, `mediaIds`

Expected: write-side API methods do not use loose `any`.

- [ ] **Step 2: Add API/call precondition for missing userId**

In the write flow, fail fast when local auth state has no `userId`.

Expected: publish/draft actions do not send malformed payloads.

- [ ] **Step 3: Update publish view payload assembly**

Update `src/views/Publish.vue` to:
- source `userId` from auth state
- send documented field names
- keep current image preview UX untouched

Expected: publish request shape matches `frontend-api.md`.

- [ ] **Step 4: Verify current draft consumer status**

Inspect current frontend consumers for `saveDraft`.

Expected:
- current codebase has no active draft UI consumer beyond the API module; keep the API method aligned, record that absence in the execution notes, and do not invent a new draft feature

- [ ] **Step 5: Add UI-visible fast-fail behavior**

Update publish/draft call sites so:
- request is not sent when local auth has no `userId`
- user sees a visible failure message
- local loading state resets cleanly

Expected: malformed write-side requests are blocked in the frontend.

- [ ] **Step 6: Run build for write-side verification**

Run: `npm run build`

Expected: no type errors remain in publish/content modules.

- [ ] **Step 7: Commit**

Run:
```bash
git add src/api/content.ts src/views/Publish.vue
git add src/store/auth.ts
git commit -m "refactor: align write-side payloads"
```

### Task 8: Final smoke verification and cleanup pass

**Files:**
- Verify only: `src/api/*`, `src/views/*`, `src/components/*`, `src/store/*`

- [ ] **Step 1: Search for raw DTO leakage outside `src/api`**

Run:
```bash
rg "authorNickname|authorAvatarUrl|mediaInfo|bizType|unreadCount|followingUserIds|blockedUserIds" src/views src/components src/store
```

Expected:
- raw backend-only names appear only in `src/api/*`

- [ ] **Step 2: Search for unsafe fallback typing in touched layers**

Run:
```bash
rg ": any|as any" src/api/auth.ts src/api/feed.ts src/api/user.ts src/api/notification.ts src/api/relation.ts src/api/search.ts src/api/content.ts src/api/interact.ts src/api/types.ts src/views/Login.vue src/views/Profile.vue src/views/Notifications.vue src/views/RelationList.vue src/views/SearchResults.vue src/views/ContentDetail.vue src/views/Publish.vue src/components/FeedContainer.vue src/components/PostCard.vue src/components/NotificationItem.vue src/components/CommentItem.vue src/components/FollowButton.vue src/store/feed.ts src/store/auth.ts
```

Expected:
- no remaining `any` fallback typing exists in touched API/view/component/store files unless explicitly justified and documented during execution

- [ ] **Step 3: Run explicit type-check**

Run: `npx vue-tsc --noEmit`

Expected: command exits 0 and no frontend type errors remain.

- [ ] **Step 4: Run final production build**

Run: `npm run build`

Expected: command exits 0 and Vite build artifacts are generated successfully.

- [ ] **Step 5: Manual smoke test against seeded backend**

Run app locally:
```bash
npm run dev
```

Manually verify:
- login with `13900000001 / Nexus123!`
- timeline renders seeded posts
- content detail renders body/image/comments
- pinned comment renders separately when present
- reply expansion fetches replies and can continue with `nextCursor` if available
- notifications render and read actions submit
- profile and relation pages render without undefined field crashes
- search result cards render
- publish action sends documented payload shape
- if a draft consumer exists, draft save sends documented payload shape
- if local auth lacks `userId`, publish/draft fast-fail in UI and no request is sent

Expected: high-traffic screens work without relying on undocumented fields.

- [ ] **Step 6: Commit final integration pass**

Run:
```bash
git add src
git commit -m "refactor: align frontend api consumers"
```

---

## Notes for Execution

- Treat `docs/superpowers/specs/2026-03-24-frontend-api-alignment-design.md` as the single source of truth.
- If current local uncommitted changes overlap with planned files, read them carefully and integrate instead of reverting.
- If runtime verification proves `commentId` is required for create-comment requests, stop and update the spec/plan before widening the implementation.
