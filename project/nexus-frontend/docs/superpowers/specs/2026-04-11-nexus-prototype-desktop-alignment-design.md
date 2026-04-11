# Nexus Prototype Desktop Alignment Design

**Date:** 2026-04-11  
**Scope:** `project/nexus-frontend`  
**Focus:** Desktop-only, prototype-covered routes  
**Status:** Approved design for planning

## Goal

Realign the prototype-covered frontend routes so the shipped desktop experience feels materially consistent with the approved prototype, especially in:

1. page shell
2. navigation weight and placement
3. content width and reading rhythm
4. alignment lines
5. visual restraint

This work is not a backend redesign and not a new product design exploration. It is a desktop prototype-alignment pass over the existing Vue frontend.

## User-Confirmed Constraints

1. Scope includes all pages covered by the prototype, not only the content detail page.
2. Fidelity target is high-fidelity prototype alignment, not "keep current implementation and just soften the visuals".
3. This pass is desktop-only. Mobile may degrade reasonably but is not part of the fidelity target.

## Route Scope

For planning purposes, "prototype-covered routes" means the currently shipped frontend routes that already have approved prototype counterparts in the project workflow.

| Route | In Scope | Notes |
|---|---|---|
| `/` | Yes | Primary gallery/list page |
| `/login` | Yes | Auth page, desktop fidelity required |
| `/register` | Yes | Auth page, desktop fidelity required |
| `/content/:postId` | Yes | Reference page for shell alignment |
| `/profile` | Yes | Self profile page |
| `/user/:userId` | Yes | Other-user profile page |
| `/relation/:type/:userId` | Yes | Secondary relation list page |
| `/notifications` | Yes | Secondary notification page |
| `/search` | Yes | Secondary search results page |
| `/publish` | Yes | Secondary create/publish page |
| `/settings/risk` | Yes | Secondary risk/settings page |

No currently shipped route is excluded from this pass. The implementation plan may still stage work route-by-route, but planning must treat the full route inventory above as the accepted scope.

## Prototype Source Of Truth

Fidelity checks for this pass use the existing project prototype inventory already mapped in the previous frontend rebuild work. The route-to-prototype mapping for this pass is:

| Route | Primary HTML Source | Secondary HTML Source | Notes |
|---|---|---|---|
| `/` | `_1/code.html` | `_3/code.html`, `_10/code.html` | Gallery/list reference |
| `/login` | `_7/code.html` | `_19/code.html` | Auth page |
| `/register` | `_23/code.html` | `_7/code.html` | Auth page |
| `/content/:postId` | `_21/code.html` | `_1/code.html` | Primary reference page |
| `/profile` | `_8/code.html` | `_25/code.html` | Self profile |
| `/user/:userId` | `_8/code.html` | `_16/code.html` | Other-user profile |
| `/relation/:type/:userId` | `_16/code.html` | `_26/code.html` | Relation list |
| `/notifications` | `_9/code.html` | `_26/code.html` | Notification page |
| `/search` | `_22/code.html` | `_10/code.html`, `_26/code.html` | Search results page |
| `/publish` | `_14/code.html` | `_20/code.html` | Publish page |
| `/settings/risk` | `_17/code.html` | `_24/code.html`, `_26/code.html` | Risk/settings page |

Authoritative artifact rule:

1. the approved desktop prototype source for this pass is the existing exported HTML prototype set referenced by `docs/superpowers/specs/2026-04-10-nexus-frontend-full-rebuild-design.md`
2. for any route in this spec, the `Primary HTML Source` listed above is the default fidelity target
3. `Secondary HTML Source` entries are only for fallback structure or local adaptation, not for changing the route's primary desktop composition

For each route, implementation should preserve current real backend behavior while using the mapped prototype HTML as the visual source of truth for desktop shell, width, hierarchy, and composition.

## Problem Statement

The current frontend drifted away from the prototype because it is using a different visual system and layout model:

1. heavy editorial tokens in `src/assets/main.css`
2. wide centered stage layouts (`max-w-screen-2xl`, `max-w-editorial`)
3. floating panel styling, blur, and heavy radii
4. separate page-authored shells coexisting with shared shell components
5. reading content placed inside large showcase canvases rather than inside a stable content-first column

The result is not "slightly off". It is a structurally different UI language.

## Non-Negotiable Principles

### 1. Prototype pages are one desktop family

Prototype-covered pages must share one desktop shell and one alignment system.

They must not continue to mix:

1. old shared editorial shell components
2. route-local hand-authored shells
3. separate container-width systems

### 2. Content-first, not stage-first

Prototype pages are content surfaces, not showcase canvases.

That means:

1. stable left alignment
2. restrained surface treatment
3. narrower reading columns than hero/media columns
4. less decorative chrome

### 3. Visual restraint beats redesign

Allowed work:

1. replacing shell structure
2. replacing spacing, width, typography, and hierarchy
3. reducing or removing decorative effects that conflict with the prototype
4. rebuilding route templates around the prototype's composition

Disallowed work:

1. inventing a new editorial identity
2. preserving decorative elements just because they already exist
3. keeping old shells alive inside prototype routes once migration happens

### 4. Data and route behavior remain real

Keep:

1. route paths
2. router reachability
3. existing API integration
4. existing primary interactions like navigation, fetch, comment submit, reaction submit

This is a shell and page-structure realignment, not a domain rewrite.

## Page Taxonomy

Prototype-covered desktop pages are grouped into three layout classes.

### A. Gallery/List Pages

Examples:

1. `/`
2. prototype-covered entry pages that aggregate content

Rules:

1. hero or featured content may be visually prominent
2. text blocks and metadata remain left-aligned
3. list entries behave as calm content entry points, not floating feature cards
4. the page should feel like a curated entry surface, not a design-system demo

Assigned routes:

1. `/`

### B. Content Detail Pages

Examples:

1. `/content/:postId`

Rules:

1. use a light top navigation
2. use a title/meta block aligned with the page shell
3. allow a wider hero-media width
4. switch body copy, tags, comment input, and comment stream into a narrower reading column
5. continuation and community sections should feel like natural extensions of the reading flow

This class is the reference page for the entire alignment effort.

Assigned routes:

1. `/content/:postId`

### C. Support / Secondary Pages

Examples:

1. `/search`
2. `/notifications`
3. `/profile`
4. `/user/:userId`
5. other prototype-visible continuation routes

Rules:

1. they may be more utilitarian than the detail page
2. they must still share the same desktop shell
3. navigation placement, outer container, and title starting line must remain consistent with the detail page family

Assigned routes:

1. `/search`
2. `/notifications`
3. `/profile`
4. `/user/:userId`
5. `/relation/:type/:userId`
6. `/publish`
7. `/settings/risk`

### D. Auth Pages

Examples:

1. `/login`
2. `/register`

Rules:

1. auth pages are in scope for desktop fidelity
2. auth pages do not use the authenticated global fixed navigation shell
3. auth pages may use their own centered or split auth composition if that is what the mapped prototype requires
4. auth pages still inherit the aligned typography, restraint, and desktop spacing principles of this pass

Assigned routes:

1. `/login`
2. `/register`

## Shared Desktop Shell

Prototype-covered routes must move to one shared shell model.

Exception:

1. auth pages are not forced into the authenticated shell and follow the `Auth Pages` layout class above

### Shell responsibilities

The shell owns:

1. top navigation
2. page-side gutters
3. top offset for fixed navigation
4. outer desktop container width
5. consistent section spacing between major page blocks

### Shell rules

1. top navigation is fixed but visually light
2. no heavy capsule header container
3. no strong glassmorphism or floating app-shell feel
4. shell width aligns navigation, heading blocks, hero media, and section starts
5. shell does not decide reading width for all descendants; inner content layers do that

## Width System

The aligned UI uses layered widths instead of one global centered stage.

### Width layers

1. shell width: aligns nav and main sections
2. media/content width: supports featured image areas and section blocks
3. reading width: narrower column for long-form text, tags, comments, and supporting prose

### Required outcome

The body copy must look intentionally narrower than the hero and high-level section canvas.

If the page still feels like "one large centered panel with content inside it", the design has failed.

## Typography Direction

The current all-`Manrope` editorial system is too uniform and too redesign-heavy for the prototype target.

The typography direction should move toward:

1. a reading-oriented serif for major content titles
2. a clean sans-serif for UI controls, metadata, labels, and long-form body copy

Acceptable directions:

1. `Newsreader` + `Roboto`
2. `Newsreader` + `Public Sans`

Concrete rule:

1. page titles and major content headings use the serif face
2. navigation, controls, labels, metadata, comments, and body paragraphs use the sans-serif face
3. body paragraphs must be optimized for desktop readability rather than decorative editorial contrast

Non-goal:

1. building a luxury magazine font system
2. keeping current typography only because it is already configured

## Visual System Rules

### Background

Use a flat or near-flat light background. Remove:

1. large global radial gradients
2. paper noise overlays
3. atmospheric screen-wide texture

### Surfaces

Prefer:

1. very light cards or no card at all
2. fine dividers
3. whitespace as structure

Avoid:

1. thick blurred containers
2. high-floating panels
3. large-radius showcase surfaces

### Radius

Reduce current large radii. Use medium radii where needed for media and inputs, but do not let radius become a primary visual motif.

### Shadow

Shadows should be weak and nearly invisible. Depth should come mostly from spacing and tonal separation.

### Color and contrast

Follow these requirements:

1. body copy must maintain strong contrast on light surfaces
2. secondary text may step down, but not to low-contrast gray-on-gray
3. semantic colors remain tokenized, but decorative warm-gray tinting should be reduced when it harms fidelity

## Component-Level Interpretation Rules

### Navigation

Keep:

1. brand
2. primary route links
3. notification trigger
4. profile trigger

Change:

1. remove heavy shell styling
2. simplify hover and active states
3. align all route families to one nav structure

### Hero / featured media

Keep:

1. real image data
2. route behavior

Change:

1. reduce decorative framing
2. ensure the image block belongs to the page grid rather than floating above it

### Comments and continuation

Keep:

1. real API-backed interactions
2. reply-loading behavior
3. pinned or sorted logic where already supported

Change:

1. bring these sections into the same reading rhythm as the body
2. avoid a separate product-dashboard panel language

## Migration Strategy

This should be implemented as a controlled shell migration, not as scattered class edits.

### Preserve

Preserve data and behavior layers such as:

1. `src/api/*`
2. `src/store/*`
3. route definitions in `src/router/index.ts`
4. current fetch and submit logic

### Stop using for prototype-covered desktop routes

These are the main sources of current visual drift and should no longer be the desktop foundation for prototype pages:

1. `src/assets/main.css` editorial shell utilities
2. `src/components/TheNavBar.vue`
3. `src/components/TheDock.vue`
4. `page-main`, `page-wrap`, `max-w-editorial`, and related editorial shell patterns

They do not all need immediate deletion, but prototype-covered desktop routes must stop depending on them.

### Introduce

Introduce a new prototype-alignment shell layer with focused responsibilities:

1. `PrototypeShell`
2. `PrototypeContainer`
3. `PrototypeReadingColumn`
4. `PrototypeSectionHeader`
5. optional continuation/comment layout helpers if duplication is concrete

### Migration order

1. `ContentDetail.vue` first
2. `Home.vue` second
3. then prototype-linked secondary pages such as search, notifications, and profile-family routes

The detail page defines the alignment truth. Other pages inherit from that shell logic.

## Validation Criteria

The implementation is acceptable only if these statements are true.

### Structural validation

1. fixed navigation no longer obscures the first meaningful content block
2. heading start lines, hero start lines, and section start lines visually align
3. body copy is visibly narrower than hero/media content
4. comments and continuation sections feel like downstream content blocks, not separate dashboards

### Visual validation

1. the first impression is "prototype-like content page", not "custom editorial redesign"
2. global background effects no longer dominate the page
3. decorative blur, shadow, and oversized radius are no longer the defining visual language

### Cross-route validation

1. navigating from home to detail preserves shell logic
2. navigating from detail to search or notifications does not jump into a different desktop alignment system
3. prototype-covered pages feel like one family

## Out of Scope

1. mobile high-fidelity restoration
2. backend contract changes
3. redesigning unsupported product flows beyond prototype-covered desktop routes
4. building a new reusable design system for future unknown pages

## Planning Implications

The implementation plan should be written around shell migration and route conversion, not around generic token cleanup.

The plan must:

1. identify exactly which files define the old shell and which define the new shell
2. migrate one route family at a time
3. verify layout fidelity with route-specific checks
4. avoid mixing old and new shell systems inside one page

## Final Decision

Proceed with a desktop-only prototype alignment pass that:

1. replaces the current prototype-route shell model
2. centers fidelity on structure, width, alignment, and hierarchy
3. preserves real route and backend behavior
4. uses the content detail page as the primary layout reference
