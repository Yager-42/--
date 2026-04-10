# Nexus Frontend Full Rebuild Implementation Plan v2

> **Execution model:** prototype-first, Tailwind-first, delete-first.  
> **This plan replaces the earlier shell-driven plan written on the same date.**

**Goal:** Rebuild the existing Nexus frontend by translating the mapped `stitch_` HTML prototypes directly into Vue route views and a minimal shared layer, while keeping current routes and backend contracts stable.

**Architecture:** adopt a real Tailwind pipeline, port prototype theme tokens into project config, translate prototype HTML into Vue SFCs, and keep shared abstraction intentionally small. The project should not introduce a new generic page-shell framework before route translation.

**Tech Stack:** Vue 3, Vite, TypeScript, Vue Router, Pinia, Axios-based API modules, Tailwind CSS, PostCSS, Autoprefixer, Google Fonts `Manrope`, Material Symbols.

---

## File Strategy

### Styling Foundation

- Modify: `package.json`
  Responsibility: add Tailwind build dependencies and scripts if required.
- Create: `tailwind.config.ts`
  Responsibility: mirror prototype color, radius, blur, font, shadow, and spacing tokens.
- Create: `postcss.config.js`
  Responsibility: Tailwind + Autoprefixer integration.
- Modify: `src/assets/main.css`
  Responsibility: reduce to Tailwind entry file plus only a few prototype-specific custom utilities that are impractical as utilities.

### Shell Elements Only

- Modify or replace: `src/components/TheNavBar.vue`
  Responsibility: direct translation of the prototype floating top bar.
- Modify or replace: `src/components/TheDock.vue`
  Responsibility: direct translation of the prototype mobile dock.
- Modify or replace: `src/components/SearchInput.vue`
  Responsibility: shell and route search input treatment aligned to the prototype.

### Allowed Shared Primitives

- Create: `src/components/primitives/ZenButton.vue`
- Create: `src/components/primitives/ZenField.vue`
- Create: `src/components/primitives/ZenIcon.vue`
- Create: `src/components/state/ZenStateBlock.vue`

These should stay small. They exist to reduce obvious duplication after direct prototype translation has begun.

### Route Views To Rewrite

- Modify: `src/views/Home.vue`
- Modify: `src/views/Login.vue`
- Modify: `src/views/Register.vue`
- Modify: `src/views/ContentDetail.vue`
- Modify: `src/views/Profile.vue`
- Modify: `src/views/RelationList.vue`
- Modify: `src/views/Notifications.vue`
- Modify: `src/views/SearchResults.vue`
- Modify: `src/views/Publish.vue`
- Modify: `src/views/RiskCenter.vue`

### Components Expected To Be Deleted Or Rendered Obsolete

- `src/components/layout/AppShell.vue`
- `src/components/layout/PageSection.vue`
- old shell-oriented sections and utility classes that only existed to support the previous rebuild direction

### Verification Surface

- Verify via command: `npm run build`
- Verify visually via command: `npm run dev -- --host 127.0.0.1 --port 4173`

---

## Chunk 1: Tailwind and Prototype Theme Adoption

### Task 1: Install and Configure Tailwind

**Files:**
- Modify: `package.json`
- Create: `tailwind.config.ts`
- Create: `postcss.config.js`
- Modify: `src/assets/main.css`

- [ ] **Step 1: Add Tailwind build dependencies**

Add:
- `tailwindcss`
- `postcss`
- `autoprefixer`

- [ ] **Step 2: Create Tailwind config that mirrors prototype tokens directly**

Include:
- prototype color names
- `Manrope` font family
- Material Symbols-safe sizing assumptions
- prototype radius scale
- blur and shadow values needed by shared shell/UI blocks

- [ ] **Step 3: Convert `src/assets/main.css` into a Tailwind entry**

Keep only:
- `@tailwind base`
- `@tailwind components`
- `@tailwind utilities`
- tiny prototype-specific helpers like `ghost-border`, `editorial-shadow`, `zen-gradient-cta` if they are still cleaner as custom classes

- [ ] **Step 4: Remove legacy theme assumptions from `main.css`**

Do not preserve the previous token system unless a still-untranslated route depends on it temporarily.

- [ ] **Step 5: Run the verification gate**

Run: `npm run build`
Expected: Vite build succeeds with Tailwind integrated.

## Chunk 2: Shared Shell Translation

### Task 2: Translate Prototype Navigation First

**Files:**
- Modify: `src/components/TheNavBar.vue`
- Modify: `src/components/TheDock.vue`
- Modify: `src/components/SearchInput.vue`

- [ ] **Step 1: Replace `TheNavBar.vue` with a direct translation of the mapped prototype nav**

Requirements:
- floating translucent top bar
- `Nexus` branding
- search affordance
- notification and profile entry
- router-aware active states

- [ ] **Step 2: Replace `TheDock.vue` with a direct translation of the mapped prototype mobile dock**

Requirements:
- home
- search
- publish
- notifications
- profile

- [ ] **Step 3: Rebuild `SearchInput.vue` to match prototype input treatment**

Requirements:
- compact shell version
- route page version
- clear button
- keyboard-safe behavior

- [ ] **Step 4: Run the verification gate**

Run: `npm run build`
Expected: shared shell compiles and routes still mount.

### Task 3: Create Minimal Shared Primitives

**Files:**
- Create: `src/components/primitives/ZenButton.vue`
- Create: `src/components/primitives/ZenField.vue`
- Create: `src/components/primitives/ZenIcon.vue`
- Create: `src/components/state/ZenStateBlock.vue`

- [ ] **Step 1: Build `ZenIcon.vue` around Material Symbols**

Requirements:
- size control
- fill and weight control
- accessible label support for icon-only buttons

- [ ] **Step 2: Build `ZenButton.vue` only for the button shapes repeatedly present in prototypes**

Variants:
- primary gradient
- secondary tonal
- text

- [ ] **Step 3: Build `ZenField.vue` only for prototype field treatment**

Support:
- input
- textarea
- select
- password-with-toggle slot

- [ ] **Step 4: Build `ZenStateBlock.vue` from `_26`**

Support:
- no results
- no notifications
- no relations
- restricted/login required
- generic request failure

- [ ] **Step 5: Run the verification gate**

Run: `npm run build`
Expected: primitives compile and can be consumed by route rewrites.

---

## Chunk 3: Direct Prototype Route Translation

### Task 4: Rewrite Login and Register First

**Files:**
- Modify: `src/views/Login.vue`
- Modify: `src/views/Register.vue`

- [ ] **Step 1: Rewrite `Login.vue` by translating `_7/code.html` into Vue**

Requirements:
- preserve prototype structure as much as possible
- wire to existing login API
- keep route redirect behavior

- [ ] **Step 2: Rewrite `Register.vue` by translating `_23/code.html` into Vue**

Requirements:
- preserve prototype structure
- inject required nickname field without changing the page's overall composition
- submit `avatarUrl: ''`

- [ ] **Step 3: Verify auth routes visually and via build**

Run: `npm run build`

### Task 5: Rewrite Notifications and RiskCenter as Direct Replica Pages

**Files:**
- Modify: `src/views/Notifications.vue`
- Modify: `src/views/RiskCenter.vue`

- [ ] **Step 1: Rewrite `Notifications.vue` from `_9` with `_26` state fallback**

- [ ] **Step 2: Rewrite `RiskCenter.vue` from `_17/_24` with `_26` unavailable state**

- [ ] **Step 3: Keep real API constraints explicit**

Requirements:
- overview mode from current risk API
- appeal mode only when query identifiers exist

- [ ] **Step 4: Run the verification gate**

Run: `npm run build`

### Task 6: Rewrite Home, Search, Content Detail, and Relation List as Replica + Data Injection Pages

**Files:**
- Modify: `src/views/Home.vue`
- Modify: `src/views/SearchResults.vue`
- Modify: `src/views/ContentDetail.vue`
- Modify: `src/views/RelationList.vue`

- [ ] **Step 1: Rewrite `Home.vue` from `_1` first, then inject real feed data**

Requirements:
- hero-led layout
- asymmetric supporting rhythm
- no fallback to uniform card grid as the main composition

- [ ] **Step 2: Rewrite `SearchResults.vue` from `_22` with `_10` suggestion behavior**

Requirements:
- honest posts-only fallback if backend is posts-only
- `_26` no-results state

- [ ] **Step 3: Rewrite `ContentDetail.vue` from `_21`**

Requirements:
- direct narrative layout
- comments adapted to existing APIs
- continuation block only from real available data

- [ ] **Step 4: Rewrite `RelationList.vue` from `_16`**

Requirements:
- segmented mode
- relation action
- empty state

- [ ] **Step 5: Run the verification gate**

Run: `npm run build`

### Task 7: Rewrite Profile and Publish as Replica + Local Adaptation Pages

**Files:**
- Modify: `src/views/Profile.vue`
- Modify: `src/views/Publish.vue`

- [ ] **Step 1: Rewrite `Profile.vue` from `_8` and use `_25` for self-edit mode**

Requirements:
- `/profile` and `/user/:userId` share the same visual skeleton
- self-edit remains inline
- only backend-supported fields persist

- [ ] **Step 2: Rewrite `Publish.vue` from `_14/_20`**

Requirements:
- keep only real publish capabilities
- remove fake draft/privacy/persistence affordances if unsupported

- [ ] **Step 3: Run the verification gate**

Run: `npm run build`

---

## Chunk 4: Cleanup and Consistency

### Task 8: Delete The Old Shell-Driven System

**Files:**
- Delete or stop referencing: `src/components/layout/AppShell.vue`
- Delete or stop referencing: `src/components/layout/PageSection.vue`
- Modify: route views and components still importing obsolete layout abstractions

- [ ] **Step 1: Search for remaining usage of obsolete shell abstractions**

Run: `rg -n "AppShell|PageSection" src`

- [ ] **Step 2: Remove or replace obsolete imports and files**

The goal is zero dependency on the previous shell-driven approach.

- [ ] **Step 3: Remove dead legacy CSS selectors**

Run: `rg -n "primary-btn|secondary-btn|surface-card|page-shell|page-content" src`

- [ ] **Step 4: Run the verification gate**

Run: `npm run build`

### Task 9: Final Responsive and Accessibility Pass

**Files:**
- Verify only: rebuilt routes and shared primitives

- [ ] **Step 1: Start the local dev server**

Run: `npm run dev -- --host 127.0.0.1 --port 4173`

- [ ] **Step 2: Check desktop and mobile on every route**

Required checks:
- no horizontal overflow
- dock does not cover content
- hero/media blocks preserve aspect ratio
- forms remain tappable and readable

- [ ] **Step 3: Check accessibility basics**

Required checks:
- focus visibility
- labels on fields
- icon-only controls have labels
- contrast remains readable on warm surfaces
- reduced motion still respected

- [ ] **Step 4: Check brand invariants**

Required checks:
- `Nexus` branding only
- no `ZenGallery` copy left in shipped UI
- no old shell-driven visuals left

- [ ] **Step 5: Record remaining defects before implementation is considered complete**

Only clearly bounded follow-ups may remain.
