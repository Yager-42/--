# Nexus Frontend Capability Backfill Design

**Date:** 2026-04-12
**Project:** `project/nexus-frontend`
**Scope:** Desktop-only Nexus frontend capability backfill aligned to the current HTML prototype set, excluding SMS login.

---

## Goal

Bring the current Nexus frontend up to parity with the backend capabilities that already have real business implementation, while preserving the existing desktop prototype structure and avoiding unnecessary new top-level routes.

This design intentionally excludes SMS login. All other approved capability gaps should be backfilled by attaching them to existing pages and components.

## Non-Goals

- No mobile-specific redesign
- No new primary navigation destinations
- No admin console features
- No wallet, poll, or group capabilities that are currently backend placeholders
- No conversion of the product into a settings-heavy dashboard

## Product Constraints

- Keep the current desktop prototype visual language
- Prefer embedding new capabilities into existing pages
- Use drawer, panel, modal, or inline sections for advanced operations instead of adding standalone pages
- Preserve current mock-friendly development flow where backend data may be absent
- Do not reintroduce SMS login

## Architecture Summary

The frontend should backfill backend capabilities through page-local enhancements rather than route expansion. Existing routes remain the canonical information architecture:

- `/profile` and `/user/:userId` remain the profile surface
- `/content/:postId` remains the content operations surface
- `/publish` remains the creation and editing workspace
- `/search` remains the discovery surface
- `/notifications` remains the inbox surface

New backend capabilities should be integrated by:

1. Extending API modules with missing endpoint wrappers
2. Replacing frontend-derived placeholder data with backend-backed data where the backend already supports it
3. Using modal/drawer/panel UI patterns to expose advanced actions without fragmenting navigation
4. Extending UI mock mode so each new capability remains demonstrable without a live backend

## Capability Scope

### Included Capabilities

- Profile post feed via `GET /api/v1/feed/profile/{targetId}`
- Profile privacy read/write via `GET/POST /api/v1/user/me/privacy`
- Profile block user via `POST /api/v1/relation/block`
- Content detail hot comments via `GET /api/v1/comment/hot`
- Content detail comment deletion via `DELETE /api/v1/comment/{commentId}`
- Content detail pin comment via `POST /api/v1/interact/comment/pin`
- Content detail reaction state bootstrap via `GET /api/v1/interact/reaction/state`
- Content deletion via `DELETE /api/v1/content/{postId}`
- Search suggestions via `GET /api/v1/search/suggest`
- Single notification read via `POST /api/v1/notification/read`
- Publish draft sync via `PATCH /api/v1/content/draft/{draftId}`
- Publish attempt status via `GET /api/v1/content/publish/attempt/{attemptId}`
- Publish scheduling via `POST/PATCH /api/v1/content/schedule`, `POST /api/v1/content/schedule/cancel`, `GET /api/v1/content/schedule/{taskId}`
- Publish history via `GET /api/v1/content/{postId}/history`
- Publish rollback via `POST /api/v1/content/{postId}/rollback`
- Account bootstrap via `GET /api/v1/auth/me`
- Account logout via `POST /api/v1/auth/logout`
- Account password change via `POST /api/v1/auth/password/change`
- Use of `GET /api/v1/user/me/profile` and `GET /api/v1/user/profile` where a lightweight profile read is more appropriate than the page aggregate response

### Explicitly Excluded

- SMS login
- Risk scan/decision standalone UI
- Admin and internal endpoints
- Wallet features
- Poll features
- Group features

## Page Design

### 1. Profile Surface

**Pages:** `Profile.vue` for both `/profile` and `/user/:userId`

#### Current Problem

The current profile page relies on frontend-generated gallery content (`profileMoments`) instead of the backend’s real author feed capabilities. Privacy settings and block user actions also exist in the backend but are not exposed.

#### Approved Design

- Replace the synthetic gallery with a backend-backed author feed using `GET /api/v1/feed/profile/{targetId}`
- Keep the existing prototype identity header and stat presentation
- Preserve edit profile behavior for the owner
- Add a compact privacy control for the owner using `GET/POST /api/v1/user/me/privacy`
- Add a more-actions entry for blocking another user using `POST /api/v1/relation/block`
- Use `GET /api/v1/user/me/profile` or `GET /api/v1/user/profile` for lightweight profile hydration where the full aggregate response is unnecessary

#### UI Pattern

- Identity block remains unchanged structurally
- Privacy is shown as a compact side panel or inline settings card only on `/profile`
- Block is exposed via a prototype-consistent menu button only on `/user/:userId`
- The lower gallery becomes a real uniform feed grid using the same card rhythm already established on the home page

#### Behavioral Rules

- Owner view: edit profile + privacy controls visible, block action hidden
- Visitor view: edit and privacy hidden, follow and block visible
- If feed data is empty, show a prototype-consistent empty archive state rather than fallback fake moments

### 2. Content Detail Surface

**Page:** `ContentDetail.vue`

#### Current Problem

The page supports detail, comments, and reactions, but it does not expose the backend’s hot comment, delete comment, pin comment, delete post, or reaction-state bootstrap capabilities.

#### Approved Design

- Keep the current narrative layout and media-first reading flow
- Add a hot/pinned comment region above the normal comment list via `GET /api/v1/comment/hot`
- Continue using `GET /api/v1/comment/list` and `GET /api/v1/comment/reply/list` for the full thread
- Initialize the current reaction state through `GET /api/v1/interact/reaction/state`
- Add delete actions for comments the user can remove via `DELETE /api/v1/comment/{commentId}`
- Add pin action for first-level comments when the viewer is the post author via `POST /api/v1/interact/comment/pin`
- Add post delete action for the author via `DELETE /api/v1/content/{postId}`

#### UI Pattern

- Hot/pinned comment appears as a separate quiet block before the comment list
- Comment row action controls stay secondary and appear in the same visual layer as timestamps/meta
- Post delete lives in a contextual actions menu near the content header, not as a primary button
- Destructive actions use a custom confirmation layer matching prototype styling

#### Behavioral Rules

- Hot comment block is omitted when no hot comment exists
- Delete comment is shown only when allowed
- Pin comment is shown only for top-level comments and only for the post owner
- Deleting the post redirects to the home page after success

### 3. Publish Surface

**Page:** `Publish.vue`

#### Current Problem

The current publish page only exposes upload, save draft, and publish. The backend already supports draft sync, publish attempt auditing, scheduling, history lookup, rollback, and delete-ready post flows, but the frontend does not surface them.

#### Approved Design

- Keep `/publish` as the single authoring workspace
- Keep the existing `PUT /api/v1/content/draft` flow as the baseline draft creation/save behavior
- Add autosave sync using `PATCH /api/v1/content/draft/{draftId}` once a draft already exists
- Display publish attempt status using `GET /api/v1/content/publish/attempt/{attemptId}`
- Add schedule controls using:
  - `POST /api/v1/content/schedule`
  - `PATCH /api/v1/content/schedule`
  - `POST /api/v1/content/schedule/cancel`
  - `GET /api/v1/content/schedule/{taskId}`
- Add version history drawer using `GET /api/v1/content/{postId}/history`
- Add rollback action within that drawer using `POST /api/v1/content/{postId}/rollback`

#### UI Pattern

- Keep the current main editor and right rail
- Schedule capability is integrated into the existing settings region
- Attempt status is shown as a persistent but lightweight status strip
- Version history uses a drawer or side panel, not a new route
- Rollback is a secondary action inside history items

#### Behavioral Rules

- First save uses `PUT /content/draft`; later background sync uses `PATCH /content/draft/{draftId}`
- Attempt polling begins only after publish returns an `attemptId`
- Scheduling and immediate publish are mutually exclusive choices in the UI
- Rollback refreshes editor content and any publish-status metadata

### 4. Search Surface

**Pages/Components:** `Home.vue`, `SearchResults.vue`, `SearchInput.vue`

#### Current Problem

The backend supports search suggestions, and the frontend already has a suggestion-capable component, but it is not wired into the active pages.

#### Approved Design

- Replace the plain search controls in the home page and search page with `SearchInput.vue`
- Use `GET /api/v1/search/suggest` to power live suggestions
- Keep the current search results information architecture:
  - featured result
  - related curators
  - curated collections
  - gallery results

#### UI Pattern

- Search suggestion panel remains floating and quiet, aligned with prototype spacing
- No separate “search suggestion page”
- Search results layout remains as currently aligned to the prototype

### 5. Notifications Surface

**Page/Components:** `Notifications.vue`, `NotificationItem.vue`

#### Current Problem

The list page supports “mark all as read”, but the item-level read capability is only partially embodied.

#### Approved Design

- Keep the current inbox page structure
- Ensure opening a notification marks it read using `POST /api/v1/notification/read`
- Keep `POST /api/v1/notification/read/all` for batch clearing
- Strengthen card-level unread styling without deviating from the prototype tone

#### Behavioral Rules

- Item-level read fires before or during navigation to the target
- Unread marker should disappear optimistically after success
- Batch “mark all” still remains available

### 6. Account Actions

**Surfaces:** Global navigation user area and lightweight account panels opened from the existing header user menu

#### Current Problem

Session and account-management endpoints exist but are not represented as real product behavior.

#### Approved Design

- Use `GET /api/v1/auth/me` to hydrate current account identity where needed
- Use `POST /api/v1/auth/logout` instead of local-only logout
- Expose `POST /api/v1/auth/password/change` in a settings-style modal or panel

#### UI Pattern

- No new “account center” route
- Keep actions attached to the existing user menu or lightweight overlay
- Password change is modal/panel-based and secondary to the core content flows

## API Mapping by Surface

| Surface | API |
|---|---|
| Profile | `GET /api/v1/feed/profile/{targetId}` |
| Profile | `GET /api/v1/user/me/profile` |
| Profile | `GET /api/v1/user/profile` |
| Profile | `GET /api/v1/user/me/privacy` |
| Profile | `POST /api/v1/user/me/privacy` |
| Profile | `POST /api/v1/relation/block` |
| Content Detail | `GET /api/v1/comment/hot` |
| Content Detail | `DELETE /api/v1/comment/{commentId}` |
| Content Detail | `POST /api/v1/interact/comment/pin` |
| Content Detail | `GET /api/v1/interact/reaction/state` |
| Content Detail | `DELETE /api/v1/content/{postId}` |
| Publish | `PATCH /api/v1/content/draft/{draftId}` |
| Publish | `GET /api/v1/content/publish/attempt/{attemptId}` |
| Publish | `POST /api/v1/content/schedule` |
| Publish | `PATCH /api/v1/content/schedule` |
| Publish | `POST /api/v1/content/schedule/cancel` |
| Publish | `GET /api/v1/content/schedule/{taskId}` |
| Publish | `GET /api/v1/content/{postId}/history` |
| Publish | `POST /api/v1/content/{postId}/rollback` |
| Search | `GET /api/v1/search/suggest` |
| Notifications | `POST /api/v1/notification/read` |
| Notifications | `POST /api/v1/notification/read/all` |
| Account | `GET /api/v1/auth/me` |
| Account | `POST /api/v1/auth/logout` |
| Account | `POST /api/v1/auth/password/change` |

## Mock and Data Strategy

- Every newly attached capability must be reflected in UI mock mode
- Mock responses should preserve the same interaction states expected from the real backend:
  - hot comment present/absent
  - comment delete success
  - pin state change
  - profile privacy toggle
  - block success
  - publish attempt transitions
  - schedule creation/update/cancel
  - version history list
  - rollback success
  - search suggestions
  - notification single-read state change
  - auth me / logout / password change responses

## Error Handling

- Destructive operations must show explicit failure messaging and leave current UI state recoverable
- Publish attempt polling failures should degrade to a retryable status state rather than break the editor
- Privacy, block, and password actions should show local inline error feedback near the initiating control
- Search suggestions should fail quietly without blocking normal search submission

## Testing Strategy

- Add API-level tests where mapping or normalization logic changes
- Extend layout/prototype tests only where structural output changes
- Add targeted UI mock tests for:
  - profile feed replacement
  - privacy toggle
  - block action
  - hot comment display
  - delete and pin comment actions
  - publish attempt status flow
  - schedule controls
  - version history + rollback
  - wired search suggestions
  - single notification read
  - auth menu actions

## Implementation Order

1. Profile surface: profile feed, privacy, block
2. Content detail: hot comments, comment delete, comment pin, delete post, reaction bootstrap
3. Publish surface: draft sync, attempt status, schedule, history, rollback
4. Search and notifications: search suggestions, single notification read
5. Account actions: auth me, logout, password change

## Success Criteria

- The frontend exposes all approved real backend capabilities without adding new primary routes
- The desktop UI remains aligned to the current prototype hierarchy
- Existing placeholder frontend behaviors are replaced where the backend already supports the feature
- UI mock mode can demonstrate all newly attached capabilities
- No SMS login work is introduced
