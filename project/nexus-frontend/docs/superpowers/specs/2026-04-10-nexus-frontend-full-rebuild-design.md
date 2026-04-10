# Nexus Frontend Full Rebuild Design v2

**Date:** 2026-04-10  
**Scope:** `project/nexus-frontend`  
**Direction:** Prototype-first rebuild  
**Status:** Replaces the earlier shell-driven rebuild spec written on the same date

## Why This Spec Replaces The Previous One

The prior rebuild spec pushed the frontend toward a self-designed component system derived from the prototype rather than a direct translation of the prototype itself. That strategy is the reason the current UI drifted into an in-between state:

1. it preserved an app-shell-first mindset
2. it centered hand-authored shared layout abstractions over page fidelity
3. it treated the prototype as aesthetic inspiration instead of source markup
4. it kept the old CSS-system mindset alive instead of switching to the prototype's native Tailwind language

This replacement spec changes the execution model completely.

## Goal

Rebuild every existing route in `nexus-frontend` so the shipped UI feels materially identical to the latest `stitch_` HTML prototypes, while preserving:

1. current route paths and route reachability
2. current backend API contracts
3. current auth and domain behavior

The frontend should no longer look like a custom redesign inspired by the prototypes. It should look like the prototypes were translated into Vue and wired to the real backend.

## Non-Negotiable Principles

### 1. Prototype-first

For each route, the primary source of truth is the mapped `stitch_` HTML file, not an abstract design system document and not a new in-house layout framework.

That means:

1. the prototype DOM structure is copied first
2. Vue bindings are layered onto that structure
3. unsupported interactions are degraded honestly
4. only after direct translation do we extract truly repeated pieces

### 2. Tailwind-first

The prototype is authored in Tailwind with inline theme extension. The rebuilt app must adopt a real Tailwind build pipeline and migrate prototype tokens into project-owned config.

That means:

1. do not build the new UI primarily through custom classes in `src/assets/main.css`
2. do not convert most utilities into a parallel hand-written CSS framework
3. use Tailwind theme tokens to mirror prototype colors, radius, blur, shadows, and spacing

### 3. Vue-adaptation only

The implementation job is translation, not reinterpretation.

Allowed changes:

1. replace static content with Vue data bindings
2. replace anchors with router navigation
3. replace static forms with real API submissions
4. extract a small number of repeated fragments when duplication is proven

Disallowed changes:

1. invent a new page composition system before page translation
2. rewrite layouts into generic shells for engineering neatness
3. collapse distinct prototype pages into one reusable template if that harms fidelity

### 4. Delete-first refactor

Legacy UI structure is not a foundation to preserve. It is the thing being replaced.

By default, the following are considered disposable:

- `src/assets/main.css`
- `src/components/layout/AppShell.vue`
- `src/components/layout/PageSection.vue`
- `src/components/TheNavBar.vue`
- `src/components/TheDock.vue`
- legacy button, card, and section utility classes

If any old component survives, it must be because it directly fits the prototype translation. Not because it already exists.

## Existing Product Constraints

### Stable route map

These routes remain:

- `/`
- `/login`
- `/register`
- `/content/:postId`
- `/profile`
- `/user/:userId`
- `/relation/:type/:userId`
- `/notifications`
- `/search`
- `/publish`
- `/settings/risk`

### Stable backend integration

These stay real and unchanged unless already supported:

1. auth APIs
2. feed APIs
3. content detail and comment APIs
4. relation APIs
5. notification APIs
6. risk APIs
7. publish APIs
8. search APIs

The rebuild is visual and structural, not a backend redesign.

## Primary Prototype Mapping

| Route | Primary HTML Source | Secondary HTML Source | Translation Mode |
|---|---|---|---|
| `/` | `_1/code.html` | `_3/code.html`, `_10/code.html` | replica + data injection |
| `/login` | `_7/code.html` | `_19/code.html` | direct replica |
| `/register` | `_23/code.html` | `_7/code.html` | direct replica |
| `/content/:postId` | `_21/code.html` | `_1/code.html` | replica + data injection |
| `/profile` | `_8/code.html` | `_25/code.html` | replica + local adaptation |
| `/user/:userId` | `_8/code.html` | `_16/code.html` | replica + data injection |
| `/relation/:type/:userId` | `_16/code.html` | `_26/code.html` | replica + data injection |
| `/notifications` | `_9/code.html` | `_26/code.html` | direct replica |
| `/search` | `_22/code.html` | `_10/code.html`, `_26/code.html` | replica + data injection |
| `/publish` | `_14/code.html` | `_20/code.html` | replica + local adaptation |
| `/settings/risk` | `_17/code.html` | `_24/code.html`, `_26/code.html` | direct replica |

## Shared Visual System

The shared system exists only to preserve the prototype's tokens across routes. It is not a new product design language invented after the fact.

### Fonts

Use:

1. `Manrope` for all text
2. `Material Symbols Outlined` for iconography

No fallback display-font reinterpretation should be introduced.

### Theme tokens

Tailwind theme values should mirror the prototype directly, including:

- `background: #faf9f4`
- `surface-container-low: #f4f4ee`
- `surface-container: #edefe7`
- `surface-container-high: #e7e9e0`
- `surface-container-highest: #e0e4d9`
- `surface-container-lowest: #ffffff`
- `primary: #615f50`
- `primary-dim: #555344`
- `secondary-container: #e8e1d9`
- `tertiary: #5f623e`
- `on-surface: #2f342d`
- `on-surface-variant: #5c6058`
- `outline-variant: #afb3aa`

The important point is fidelity, not semantic renaming for its own sake.

### Visual rules

These prototype rules are binding:

1. warm paper canvas
2. tonal layering over hard borders
3. floating blur for top shell and mobile dock
4. gradient CTA only on primary action surfaces
5. rounded containers with generous inner padding
6. asymmetric content rhythm on editorial pages

## What May Be Extracted

Only these repeated structures should become shared components before route work is finished:

1. top navigation
2. mobile dock
3. icon wrapper
4. button variants
5. field primitives
6. state blocks

Everything else should remain page-led until duplication is concrete.

Specifically, this rebuild must not begin by introducing a new universal layout abstraction like:

- `AppShell`
- `PageSection`
- generic editorial container system
- generic transactional container system

Those abstractions flatten the prototype pages into sameness and are the main failure mode of the previous spec.

## Page Translation Rules

### Home

Home should feel like the prototype home, not like a feed route with nicer cards.

Requirements:

1. hero-led composition
2. floating metadata or side information block when layout allows
3. asymmetry in supporting content blocks
4. feed data injected into prototype-shaped slots
5. fallback behavior when data volume is low must preserve the prototype composition instead of degrading to equal cards

### Login

Login should be translated almost literally from `_7`.

Requirements:

1. centered, premium, two-panel or one-panel auth composition depending on breakpoint
2. no utilitarian enterprise form feel
3. same field fills, gradients, label treatment, and spacing rhythm as prototype
4. auth redirect reasons may influence copy, but must not change page structure

### Register

Register should follow `_23` first and only adapt where backend payload requires it.

Requirements:

1. phone
2. verification code
3. password
4. nickname in the same page because backend requires it
5. `avatarUrl` submitted as empty string until profile editing

No hidden second-step flow may be invented.

### Content Detail

Translate `_21` structurally.

Requirements:

1. editorial reading layout
2. hero media and metadata placement close to prototype
3. layered comments and reply previews
4. continuation section that uses real available data only

### Profile

Translate `_8` visually and `_25` for self-edit state.

Requirements:

1. profile page remains an exhibition page, not a dashboard
2. `/profile` and `/user/:userId` share the same page skeleton
3. self-edit is an in-page mode, not a new route
4. only backend-supported fields are persistent

### Search

Translate `_22` for results and `_10` for suggestions.

Requirements:

1. top search treatment follows prototype
2. suggestion overlay uses prototype structure
3. no-results state uses `_26`
4. if backend returns posts only, the layout remains honest and does not fabricate people/collection tabs

### Notifications

Translate `_9` directly.

Requirements:

1. calmer item spacing
2. tonal unread treatment
3. empty state from `_26`

### Relation List

Translate `_16`.

Requirements:

1. followers/following split
2. relation action in prototype visual language
3. empty state in prototype visual language

### Publish

Translate `_14` and `_20` but remove fake capabilities.

Allowed:

1. title
2. body
3. hero media upload
4. publish

Not allowed unless backend-backed:

1. persistent draft states
2. fake privacy persistence
3. fake comment toggle persistence

### Risk Center

Translate `_17` and `_24` structurally, use `_26` for unavailable states.

Requirements:

1. overview mode
2. appeal mode when query identifiers exist
3. appeal-unavailable copy when identifiers do not exist

## Accessibility Requirements

Prototype fidelity does not override usability. The rebuilt app must preserve:

1. visible focus rings
2. keyboard reachability
3. semantic labels on fields
4. labels for icon-only buttons
5. contrast-safe text against warm surfaces
6. reduced-motion support

These should be implemented within the prototype structure, not by falling back to generic old UI.

## Performance Rules

The rebuild should stay efficient without compromising fidelity:

1. use Tailwind build output, not runtime CDN
2. preserve media aspect ratios to avoid CLS
3. keep motion lightweight
4. lazy-load heavy images where practical
5. avoid layering multiple styling systems in parallel

## Explicit Rejections Of The Previous Spec

The following ideas are now rejected:

1. creating a new global page shell abstraction before page translation
2. creating a generic page-section abstraction before route reconstruction
3. centering the rebuild on `src/assets/main.css`
4. writing pages as consumers of a newly invented layout system
5. translating the prototypes into a custom CSS framework instead of Tailwind config

## Acceptance Criteria

The rebuild is complete only when all of the following are true:

1. every route visually reads as a Vue-wired version of its mapped prototype
2. the app no longer contains the old shell-driven visual language
3. there is no remaining dependency on the previous layout abstractions unless they were rewritten to be direct prototype translations
4. the project uses Tailwind as the primary styling system for rebuilt pages
5. backend flows still function
6. mobile and desktop match the prototype atmosphere instead of falling back to generic utility layouts
7. the brand copy says `Nexus`, not `ZenGallery`

## Planning Readiness

Implementation planning must now follow this order:

1. Tailwind and prototype token setup
2. direct translation of shared prototype shell elements
3. direct route-by-route page translation
4. only then extract repeated fragments proven by duplication
