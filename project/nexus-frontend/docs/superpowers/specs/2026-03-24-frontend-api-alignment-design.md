# Nexus Frontend API Alignment Design

**Date:** 2026-03-24
**Scope:** `project/nexus-frontend`
**Constraint:** only modify frontend code; backend and `project/nexus/docs/frontend-api.md` are treated as fixed contracts.

## Goal

Bring every currently connected frontend page in `nexus-frontend` into alignment with `project/nexus/docs/frontend-api.md` so the app can run against the current backend without relying on imaginary fields.

## Problem Summary

The frontend currently mixes two different data models:

1. documented backend DTOs from `frontend-api.md`
2. page-specific display fields invented in the frontend

That leak exists across `feed`, `user`, `notification`, `relation`, `content`, `comment`, and `search`. As a result, pages read fields the backend never promised, and valid backend responses still fail to render.

## Design Principles

1. Keep backend untouched.
2. Keep page structure mostly untouched.
3. Centralize DTO-to-ViewModel mapping in `src/api`.
4. Prefer graceful degradation over fake business meaning.
5. Preserve existing routes, auth flow, and page interaction patterns.

## Chosen Approach

Introduce an explicit two-layer contract inside the frontend API modules:

- `Raw DTO`: exact shape documented in `frontend-api.md`
- `ViewModel`: stable shape consumed by pages and components

Each API module will expose mapped data only. Raw DTOs must stop at `src/api`. Views, stores, and components consume only ViewModels plus explicit pagination metadata.

This avoids spreading one-off field translations across pages and keeps backend contract handling in one place.

The implementation will be phased, but it remains one frontend-alignment feature:

1. read-side alignment: feed, content detail, profile, notifications, relations, search
2. comment alignment: root comments, pinned comment, reply list contract, create comment
3. write-side alignment: publish and draft payloads

## Rejected Alternatives

### 1. Patch each page directly

Fast for one screen, bad for the codebase. Shared field mapping would be duplicated in multiple views and components.

### 2. Rewrite responses globally in `http.ts`

Too implicit. URL-based mutation in the interceptor would hide data shape changes and make debugging much harder.

## Affected Files

### API layer

- `src/api/types.ts`
- `src/api/auth.ts`
- `src/api/feed.ts`
- `src/api/user.ts`
- `src/api/notification.ts`
- `src/api/relation.ts`
- `src/api/content.ts`
- `src/api/interact.ts`
- `src/api/search.ts`

### Consumers

- `src/store/feed.ts`
- `src/components/FeedContainer.vue`
- `src/components/PostCard.vue`
- `src/components/NotificationItem.vue`
- `src/components/CommentItem.vue`
- `src/components/FollowButton.vue`
- `src/views/Login.vue`
- `src/views/Profile.vue`
- `src/views/Notifications.vue`
- `src/views/RelationList.vue`
- `src/views/ContentDetail.vue`
- `src/views/SearchResults.vue`
- `src/views/Publish.vue`

## Data Model Changes

### Auth

Keep documented `AuthTokenResponseDTO` semantics:

- use `token`
- use `userId`
- ignore `tokenName` and `tokenPrefix` for now because request auth already uses `Bearer ${token}`

### Feed

Documented raw fields:

- `authorNickname`
- `text`
- `summary`
- `mediaInfo`
- `publishTime`
- `likeCount`
- `liked`

Mapped ViewModel fields:

- `authorName <- authorNickname`
- `contentBody <- text`
- `contentTitle <- summary || truncated text`
- `mediaUrls <- parsed mediaInfo`
- `createTime <- publishTime`
- `reactionCount <- likeCount`
- `isLiked <- liked`
- `commentCount <- 0` because the documented DTO does not provide it

List contract:

- `items`: append-only timeline list
- `nextCursor`: nullable string cursor
- empty page with empty `nextCursor`: terminate pagination
- duplicate `postId`: keep first occurrence and drop appended duplicate rows

### User/Profile

Profile page should stop stitching multiple incompatible assumptions together and use:

- `GET /api/v1/user/profile/page`

Mapped ViewModel:

- `avatar <- profile.avatarUrl`
- `nickname <- profile.nickname`
- `bio <- ''`
- `stats.followCount <- relation.followCount`
- `stats.followerCount <- relation.followerCount`
- `stats.likeCount <- 0`
- `riskStatus <- risk.status`

`GET /api/v1/user/profile` and `GET /api/v1/user/me/profile` remain useful for lightweight profile reads and updates, but the page-level screen should consume the page-level API.

### Notifications

Documented DTO does not contain sender profile fields. The frontend must not invent them.

Mapped ViewModel:

- `type <- normalized from bizType`
- `senderId <- String(lastActorUserId || '')`
- `senderName <- title || '系统通知'`
- `senderAvatar <- placeholder`
- `content <- content`
- `targetId <- String(targetId || postId || rootCommentId || '')`
- `hasUnread <- unreadCount > 0`
- `isRead <- unreadCount === 0` as a UI-only derived state from the unread counter, not as an authoritative business field

List contract:

- `notifications`: append-only list
- `nextCursor`: nullable string cursor
- empty page with empty `nextCursor`: terminate pagination
- duplicate `notificationId`: keep first occurrence and drop appended duplicate rows

### Relations

Documented `RelationUserDTO` contains:

- `userId`
- `nickname`
- `avatar`
- `followTime`

The frontend should:

- map `bio` to empty string
- fetch relation state through `POST /api/v1/relation/state/batch`
- derive `isFollowing` from `followingUserIds`
- store `relationState` as `FOLLOWING | NOT_FOLLOWING | UNKNOWN` for button rendering

List contract:

- `items`: append-only relation list
- `nextCursor`: nullable string cursor
- empty page with empty `nextCursor`: terminate pagination
- duplicate `userId`: keep first occurrence and drop appended duplicate rows

### Content Detail

Use documented fields directly:

- `authorNickname`
- `authorAvatarUrl`
- `title`
- `content`
- `mediaInfo`
- `likeCount`

### Comments

`GET /api/v1/comment/list` returns `pinned`, `items`, `nextCursor`.

Root comment ViewModel:

- `authorName <- nickname`
- `content <- content`
- `createTime <- createTime`
- `commentId <- commentId`
- `replyCount <- replyCount`
- `likeCount <- likeCount`

Comment list contract:

- `pinned`: optional single pinned root comment
- `items`: root comment list excluding the pinned row if the backend already duplicates it in the list; frontend de-duplicates by `commentId`
- `nextCursor`: nullable string cursor
- request defaults: `limit=20`, `preloadReplyLimit=2`

Reply list contract for `GET /api/v1/comment/reply/list`:

- use when a root comment expands replies
- request params: `rootId`, optional `cursor`, optional `limit`
- map reply rows into the same simple display shape
- `nextCursor` controls incremental reply loading

The request for posting comments should stay aligned with documented `CommentRequestDTO`:

- `postId`
- optional `parentId`
- `content`
- optional `commentId`

Default rule: frontend omits `commentId`. Only if runtime verification proves the backend rejects omitted `commentId`, add a frontend-generated identifier in a follow-up implementation step.

### Search

Documented result items expose:

- `id`
- `title`
- `description`
- `coverImage`
- `authorNickname`
- `authorAvatar`
- `likeCount`
- `liked`

The frontend should map those fields into the card input shape instead of expecting feed-style fields.

Search card ViewModel:

- `postId <- id`
- `contentTitle <- title`
- `contentBody <- description`
- `coverUrl <- coverImage`
- `authorName <- authorNickname`
- `authorAvatar <- authorAvatar`
- `reactionCount <- likeCount`
- `isLiked <- liked`

List contract:

- `items`: append-only search result list
- `nextAfter`: backend pagination token
- `hasMore`: authoritative pagination stop signal
- duplicate `id`: keep first occurrence and drop appended duplicate rows

### Publish

Align publish payload with documented `PublishContentRequestDTO`:

- optional `postId`
- `userId` sourced from auth store
- `title`
- `text`
- `mediaInfo`
- `visibility`
- optional `location`
- optional `postTypes`

Align draft payload with documented `SaveDraftRequestDTO`:

- `draftId`
- `userId` sourced from auth store
- `title`
- `contentText`
- `mediaIds`

If authenticated `userId` is missing locally, publish and draft actions must fail fast in the frontend before issuing the request.

## Module Alignment Matrix

| Module | Endpoint | Request Contract | Response Container | ViewModel / Consumer Contract | Pagination |
|---|---|---|---|---|---|
| Auth | `POST /auth/login/password` | `phone`, `password` | token DTO | token + userId for auth store | none |
| Feed | `GET /feed/timeline` | `userId?`, `cursor?`, `limit?`, `feedType?` | `{ items, nextCursor }` | feed card ViewModel list | cursor |
| Profile | `GET /user/profile/page` | `targetUserId?` | `{ profile, relation, risk }` | profile page ViewModel | none |
| Notification | `GET /notification/list` | `userId`, `cursor?` | `{ notifications, nextCursor }` | notification row ViewModel list | cursor |
| Relation | `GET /relation/followers`, `GET /relation/following` | `userId`, `cursor?`, `limit?` | `{ items, nextCursor }` | relation row ViewModel list | cursor |
| Relation State | `POST /relation/state/batch` | `targetUserIds` | `{ followingUserIds, blockedUserIds }` | enrich relation rows | none |
| Content Detail | `GET /content/{postId}` | path param | content detail DTO | detail page ViewModel | none |
| Comments | `GET /comment/list` | `postId`, `cursor?`, `limit?`, `preloadReplyLimit?` | `{ pinned, items, nextCursor }` | root comment list | cursor |
| Comment Replies | `GET /comment/reply/list` | `rootId`, `cursor?`, `limit?` | `{ items, nextCursor }` | reply list per root comment | cursor |
| Comment Create | `POST /interact/comment` | `postId`, `parentId?`, `content`, `commentId?` | comment response DTO | optimistic insert + reload fallback | none |
| Search | `GET /search` | `q`, `size?`, `tags?`, `after?` | `{ items, nextAfter, hasMore }` | search card ViewModel list | nextAfter + hasMore |
| Publish | `POST /content/publish` | documented publish DTO | publish response DTO | submit action | none |
| Draft | `PUT /content/draft` | documented draft DTO | draft response DTO | save action | none |

## Error Handling Contract

The existing `http.ts` interceptor remains the common response-shell gate for `{ code, info, data }`.

API modules must additionally handle mapper-level failures explicitly:

1. if required `data` is absent, reject with a descriptive error
2. if `mediaInfo` cannot be parsed, fall back to an empty media array instead of throwing
3. if optional fields are missing, use documented-safe defaults only
4. if pagination token is absent, normalize to `null`
5. if follow-state enrichment fails, render relation rows with `relationState = 'UNKNOWN'`, disable follow-toggle actions for that row, and log the failure
6. if comment optimistic insert cannot be reconciled with backend response, reload the comment list

## UI Behavior Changes

These are intentional degradations caused by the documented contract being smaller than the existing frontend assumptions:

1. Notification list will not show true sender nickname/avatar unless the contract later adds them.
2. Profile page will not show real bio or total like count because the documented page response does not include them.
3. Relation list will not show bio because the documented relation DTO does not include it.
4. Feed cards will use summary or truncated text instead of a dedicated backend title when title is absent.
5. Notification read style is derived from `unreadCount`, because the documented contract does not provide a separate `isRead` field.

These are acceptable because they keep screens usable without fabricating data.

## Validation Plan

### Functional smoke checks

1. Login with `13900000001 / Nexus123!`
2. Open home timeline and confirm seeded posts render
3. Open content detail and confirm body, image, and comments render
4. Open notifications and confirm seeded notifications render
5. Open my profile and another user profile
6. Open follower/following list and verify follow button state renders
7. Run a search and confirm result cards render
8. Publish flow sends documented payload shape

### Technical checks

1. Type-check the frontend
2. Build the frontend
3. Manually verify no page still references removed imaginary fields

## Risks

1. Existing local uncommitted frontend changes may overlap with API/view files and require careful merge-by-reading.
2. Some backend endpoints may accept extra fields or ignore missing fields differently than documented. In that case, the document remains source of truth for frontend shape, and failures should be surfaced explicitly.
3. `mediaInfo` shape may vary between endpoints, so media parsing must be defensive.

## Implementation Boundaries

Do:

- add typed raw DTOs and ViewModels
- add small mapping helpers in API modules
- make minimal view/component updates where necessary

Do not:

- modify backend code
- modify `frontend-api.md`
- add a new global state abstraction
- redesign the UI
