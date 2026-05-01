# Nexus Editorial Social Design

## Summary

This document defines the first frontend version for `nexus-frontend` as an authenticated social product, not a marketing site. The scope is constrained to the backend capabilities already available in the Nexus services:

- Auth: register, password login, refresh, logout, me
- Feed: timeline, profile feed
- Search: search and suggest
- Profile: public profile, aggregated profile page, self profile and privacy settings
- Interaction: reaction, comment entry, notifications
- Content detail: post detail and comments

The selected direction is `Editorial Social`: content-first, readable, calm, and trustworthy, with restrained brand accents.

## Goals

- Deliver a usable logged-in social frontend shell instead of a static showcase
- Validate the main backend integration path end to end
- Establish a Vue frontend architecture that can support later expansion
- Create a visual language that feels intentional and product-grade, without over-styling the first version

## Non-Goals

- Public marketing website or landing page
- Admin console and risk management UI
- Full publishing studio for rich media creation
- Full notification management workflows beyond list and read actions
- Complex analytics, dashboards, or recommendation experiments

## Product Scope

The first version includes:

- Login and registration
- Feed timeline home
- Search page
- Public user profile page
- Self profile and privacy access
- Notifications list
- Post detail page
- Basic reaction and comment entry points

The first version excludes:

- Marketing homepage
- Creator dashboard
- Advanced media editing
- Full moderation interface

## Backend Capability Alignment

The frontend must be shaped around real APIs, not speculative product ideas.

Primary backend endpoints in scope:

- Auth: `/api/v1/auth/register`, `/api/v1/auth/login/password`, `/api/v1/auth/refresh`, `/api/v1/auth/logout`, `/api/v1/auth/me`
- Feed: `/api/v1/feed/timeline`, `/api/v1/feed/profile/{targetId}`
- Search: `/api/v1/search`, `/api/v1/search/suggest`
- Profile: `/api/v1/user/profile`, `/api/v1/user/profile/page`, `/api/v1/user/me/profile`, `/api/v1/user/me/privacy`
- Interaction: `/api/v1/interact/reaction`, `/api/v1/interact/comment`, `/api/v1/notification/list`, `/api/v1/notification/read`, `/api/v1/notification/read/all`
- Content: `/api/v1/content/{postId}`
- Comments: `/api/v1/comment/hot`, `/api/v1/comment/list`, `/api/v1/comment/reply/list`

All `/api/v1/**` requests should be treated as authenticated unless explicitly documented otherwise. Frontend success must be determined by response payload `code === "0000"`, not only HTTP status.

## Information Architecture

The application should be a logged-in single-page app shell.

Primary routes:

- `/login`
- `/`
- `/search`
- `/profile/:id`
- `/me`
- `/notifications`
- `/post/:id`

Navigation hierarchy:

- Primary navigation: Home, Search, Notifications, Me
- Secondary actions: Publish entry placeholder, edit profile, privacy settings, logout
- No top-level admin or marketing navigation in this version

Responsive layout:

- Mobile: top bar plus bottom navigation
- Tablet and desktop: left navigation, center content column, right auxiliary column

## Page Design

### Login

Purpose:

- Establish the authentication entry point
- Support password login and registration without overcomplicating the flow

Structure:

- Brand/value statement area
- Login/register switch
- Compact form card
- Inline validation and submission feedback

### Home Timeline

Purpose:

- Make the feed the center of the product

Structure:

- Compact top search trigger
- Optional lightweight feed filter row
- Main feed card stream
- Auxiliary rail for suggestions, trending searches, and quick profile shortcuts

### Search

Purpose:

- Provide search-first discovery without turning the page into a noisy portal

Structure:

- Fixed search field
- Result type tabs
- Post results using compact feed cards
- User results using relationship-aware profile rows
- Explicit empty state

### Public Profile

Purpose:

- Show identity, relationship state, and authored content clearly

Structure:

- Profile header with avatar, nickname, bio, stats, follow controls
- User content stream below
- If self-view, visible edit and privacy entry points

### Me

Purpose:

- Give the current user a focused place for self information and account-adjacent actions

Structure:

- Self profile summary
- Edit profile entry
- Privacy settings entry
- Logout action

### Notifications

Purpose:

- Surface social feedback in a clear action-oriented list

Structure:

- Chronological grouped list
- Distinct unread treatment
- Single read and bulk read actions

### Post Detail

Purpose:

- Expand a feed item into a detail page with comments and interaction continuity

Structure:

- Full post card
- Persistent interaction row
- Hot comments and comment thread list
- Comment input anchored accessibly

## Visual Direction

Selected visual style: `Editorial Social`

Core attributes:

- Content-first
- Calm and trustworthy
- Modern editorial rhythm
- Restrained motion
- Strong readability

Rejected visual extremes:

- Overly vibrant discovery portal
- Pure flat internal-tool aesthetic
- Marketing-heavy landing page composition

## Design Tokens

### Color System

Recommended semantic token direction:

- Background: cool off-white or mist gray
- Surface: white
- Text primary: deep slate
- Text secondary: muted slate
- Primary action: trustworthy blue
- Secondary accent: low-saturation coral for hot states, unread markers, and highlights
- Border: subtle neutral line
- Destructive: semantic red
- Success: semantic green

Rules:

- Components must consume semantic tokens, not raw colors
- Color cannot be the only signal for state
- Contrast must meet accessibility requirements in both light and dark modes if dark mode is later added

### Typography

Direction:

- Modern sans-serif heading style with personality but strong legibility
- Stable readable body font for dense content
- Tight, limited type scale
- Tabular figures for counters, timestamps, and metrics where layout stability matters

Rules:

- Avoid decorative typography
- Preserve strong reading rhythm on feed cards and detail pages
- Do not use undersized body copy on mobile

### Shape and Elevation

Direction:

- Medium radius cards
- Light borders over heavy shadows
- Clear separation through spacing and structure, not visual noise

Rules:

- Do not use oversized soft shadows
- Do not over-round controls
- Keep primary and secondary buttons visually distinct

## Motion

Motion should feel responsive and restrained.

Rules:

- Use `transform` and `opacity` only for routine UI motion
- Typical motion duration: 150ms to 220ms
- Use skeletons for loading states
- Respect `prefers-reduced-motion`
- Avoid layout-shifting transitions

Examples:

- Feed card hover or press: light lift or slight scale
- Tab and filter state change: subtle fade or underline transition
- Route changes: simple continuity transition, not dramatic page choreography

## Responsive Rules

- 375px: single content column
- 768px: optional auxiliary area can appear
- 1024px and above: three-column shell with dominant center column
- No horizontal scrolling
- Long text must wrap cleanly
- Fixed navigation and action areas must not overlap core reading space

## UX Rules

- Content body and author identity are always first-level hierarchy
- Stats, metadata, and secondary actions are second-level hierarchy
- Discovery and auxiliary modules are third-level hierarchy
- Search must remain reachable from every primary page
- Error handling should be local and actionable
- Empty states must explain what happened and what to do next

## Frontend Architecture

### App Layers

- `router`: route definitions, auth guards, title management, scroll restoration
- `views`: page containers only
- `components`: reusable interface blocks
- `stores`: Pinia shared state
- `services/http`: axios instance, auth header injection, response normalization, refresh flow
- `services/api`: domain API modules
- `types`: frontend DTOs and view models

### Shared State

Global shared state should include:

- Current authenticated user
- Access token and refresh token
- Notification unread indicators
- Lightweight global UI state such as pending auth bootstrap

Shared state should not include every page payload by default.

### HTTP Contract Handling

The HTTP layer must:

- Attach `Authorization: Bearer <token>` to authenticated calls
- Parse `{ code, info, data }`
- Treat non-`0000` codes as application failures
- Map failures to meaningful frontend error states
- Handle refresh and request replay centrally

### Auth Strategy

Startup flow:

1. Read saved token and refresh token
2. Attempt `GET /api/v1/auth/me`
3. If invalid, attempt refresh
4. If refresh succeeds, retry bootstrap
5. If refresh fails, clear session and redirect to `/login`

Refresh flow:

- Trigger from centralized HTTP handling
- Avoid duplicate concurrent refresh storms
- Replay failed requests only after refresh success

## Data Binding Boundaries

- Feed cards should consume a frontend view model, not raw controller DTOs
- Profile header and profile feed should load independently
- Reactions and follow actions should use optimistic updates with rollback on failure
- Search query should sync to URL for shareable state
- Comments and detail content should degrade gracefully if one subrequest fails

## Error States

Error behavior should be scoped appropriately:

- Page-level failure: recoverable panel with retry
- List-level failure: preserve loaded content and show inline retry near the failing section
- Form-level failure: inline field message and focused recovery
- Global failure: only for auth/session or severe cross-page events

## Testing Strategy

This project should follow TDD during implementation.

Initial test targets:

- Login/register form validation and submission behavior
- HTTP response normalization for `{ code, info, data }`
- Token refresh and request replay logic
- Route guard behavior for authenticated pages
- Feed card rendering and interaction emission

Recommended tools already available in the project:

- Vitest
- Vue Test Utils

## Implementation Sequence

Suggested delivery order:

1. App shell, router, auth bootstrap, shared HTTP layer
2. Login/register page
3. Timeline home page
4. Search page
5. Public profile page and self page
6. Notifications page
7. Post detail page
8. Basic optimistic interaction polish

## Risks

- The frontend API documentation is partially garbled in encoding, so implementation should verify DTO shapes against source Java classes where necessary
- The backend response contract uses application codes, so naive axios success handling will be incorrect
- The refresh flow is custom and must be validated carefully before building the rest of the app on top of it
- `nexus-frontend` currently has no source structure, so app bootstrapping must be created from scratch

## Acceptance Criteria

This design is successful if:

- The first version is clearly an authenticated social application, not a marketing page
- The UI expresses `Editorial Social` consistently across login, feed, search, profile, notifications, and detail
- The frontend architecture supports safe API integration and token refresh
- The visual system is readable, accessible, and responsive
- The implementation scope remains aligned with existing backend endpoints
