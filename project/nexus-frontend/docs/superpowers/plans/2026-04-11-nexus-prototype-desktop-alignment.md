# Nexus Prototype Desktop Alignment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild all prototype-covered desktop routes in `project/nexus-frontend` onto one prototype-aligned shell and typography system while preserving existing route paths and backend behavior.

**Architecture:** Introduce a new prototype desktop shell layer with an auth-shell exception, migrate routes in fidelity order starting from `ContentDetail.vue`, and retire the old editorial shell only after every in-scope route stops depending on it. Verification uses a minimal, executable Vitest harness for the new shell primitives plus route-level build checks, source-contract searches, and explicit desktop acceptance checks for each page family.

**Tech Stack:** Vue 3, Vite, TypeScript, Tailwind CSS, Pinia, Vue Router, Vitest, Vue Test Utils, jsdom

**Prototype Reference:** Fidelity checks follow `docs/superpowers/specs/2026-04-11-nexus-prototype-desktop-alignment-design.md` and the route-to-prototype mapping recorded in `docs/superpowers/specs/2026-04-10-nexus-frontend-full-rebuild-design.md`. If the raw exported prototype HTML files are not locally present, do not block implementation; use the approved mapping and previously captured review artifacts for manual comparison.

---

### Task 1: Add A Minimal Test Harness For Shell Primitives

**Files:**
- Modify: `project/nexus-frontend/package.json`
- Modify: `project/nexus-frontend/package-lock.json`
- Create: `project/nexus-frontend/vitest.config.ts`
- Create: `project/nexus-frontend/src/test/setup.ts`
- Create: `project/nexus-frontend/tests/layout/prototype-shell.spec.ts`

- [ ] **Step 1: Add the failing shell-contract spec first**

```ts
import { mount } from '@vue/test-utils'
import { expect, test } from 'vitest'
import PrototypeShell from '@/components/prototype/PrototypeShell.vue'
import PrototypeAuthShell from '@/components/prototype/PrototypeAuthShell.vue'

test('authenticated shell renders a fixed desktop nav container', () => {
  const wrapper = mount(PrototypeShell, {
    slots: { default: '<div>content</div>' }
  })

  expect(wrapper.find('[data-prototype-nav]').exists()).toBe(true)
  expect(wrapper.find('[data-prototype-main]').classes()).toContain('pt-[88px]')
})

test('auth shell does not render authenticated nav chrome', () => {
  const wrapper = mount(PrototypeAuthShell, {
    slots: { default: '<div>auth</div>' }
  })

  expect(wrapper.find('[data-prototype-nav]').exists()).toBe(false)
})
```

- [ ] **Step 2: Add Vitest tooling so the spec can run**

Run:

```bash
cd project/nexus-frontend
npm install -D vitest jsdom @vue/test-utils
```

Expected: install completes successfully and both `package.json` and `package-lock.json` gain the new dev dependencies.

- [ ] **Step 3: Add the test script, config, and minimal setup file**

```json
{
  "scripts": {
    "test": "vitest run"
  }
}
```

```ts
// vitest.config.ts
import path from 'node:path'
import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vitest/config'

export default defineConfig({
  plugins: [vue()],
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts']
  },
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src')
    }
  }
})
```

```ts
// src/test/setup.ts
export {}
```

Constraint note:

1. keep this harness intentionally minimal
2. do not spend this task building router, Pinia, or API mocks for full route views
3. route migrations later in the plan use build checks and source/manual verification instead of brittle full-view mount tests

- [ ] **Step 4: Run the new shell spec to verify RED**

Run:

```bash
cd project/nexus-frontend
npm run test -- tests/layout/prototype-shell.spec.ts
```

Expected: FAIL because `PrototypeShell.vue` and `PrototypeAuthShell.vue` do not exist yet.

- [ ] **Step 5: Commit the test-harness setup**

```bash
cd project/nexus-frontend
git add package.json package-lock.json vitest.config.ts src/test/setup.ts tests/layout/prototype-shell.spec.ts
git commit -m "test: add prototype shell layout harness"
```

### Task 2: Build The New Prototype Desktop Shell Layer

**Files:**
- Create: `project/nexus-frontend/src/components/prototype/PrototypeShell.vue`
- Create: `project/nexus-frontend/src/components/prototype/PrototypeAuthShell.vue`
- Create: `project/nexus-frontend/src/components/prototype/PrototypeNav.vue`
- Create: `project/nexus-frontend/src/components/prototype/PrototypeContainer.vue`
- Create: `project/nexus-frontend/src/components/prototype/PrototypeReadingColumn.vue`
- Create: `project/nexus-frontend/src/components/prototype/PrototypeSectionHeader.vue`
- Modify: `project/nexus-frontend/src/assets/main.css`
- Modify: `project/nexus-frontend/tailwind.config.ts`
- Test: `project/nexus-frontend/tests/layout/prototype-shell.spec.ts`

- [ ] **Step 1: Implement only the minimal shell code needed for the failing shell spec**

```vue
<!-- PrototypeShell.vue -->
<template>
  <div class="min-h-screen bg-prototype-bg text-prototype-ink">
    <PrototypeNav data-prototype-nav />
    <main data-prototype-main class="mx-auto w-full max-w-shell px-8 pt-[88px] pb-20">
      <slot />
    </main>
  </div>
</template>
```

```vue
<!-- PrototypeAuthShell.vue -->
<template>
  <div data-auth-shell class="min-h-screen bg-prototype-bg text-prototype-ink">
    <main class="mx-auto flex min-h-screen w-full max-w-shell items-center justify-center px-8 py-20">
      <slot />
    </main>
  </div>
</template>
```

- [ ] **Step 2: Replace the editorial global tokens with prototype-safe base tokens**

Required changes:

1. remove global radial gradients and the `body::before` noise overlay
2. introduce prototype semantic colors such as `prototype-bg`, `prototype-surface`, `prototype-ink`, `prototype-muted`, `prototype-line`
3. lock the font pair to `Newsreader` for large content headings and `Public Sans` for UI, metadata, and body copy
4. stop using `.page-wrap`, `.page-main`, `.paper-panel`, `.tonal-panel`, and editorial shell helpers as the default route foundation

- [ ] **Step 3: Add only the Tailwind extensions needed by the new shell**

Keep the config small:

1. new colors
2. new font families
3. `max-w-reading`, `max-w-content`, and `max-w-shell`
4. restrained shadow tokens only if needed for inputs or media frames

- [ ] **Step 4: Re-run the shell-contract spec to verify GREEN**

Run:

```bash
cd project/nexus-frontend
npm run test -- tests/layout/prototype-shell.spec.ts
```

Expected: PASS.

- [ ] **Step 5: Run the desktop build**

Run:

```bash
cd project/nexus-frontend
npm run build
```

Expected: PASS with Vite production output and no TypeScript errors.

- [ ] **Step 6: Commit the shell layer**

```bash
cd project/nexus-frontend
git add src/components/prototype src/assets/main.css tailwind.config.ts tests/layout/prototype-shell.spec.ts
git commit -m "feat: add prototype desktop shell layer"
```

### Task 3: Migrate Content Detail To The Reference Prototype Layout

**Files:**
- Modify: `project/nexus-frontend/src/views/ContentDetail.vue`
- Create: `project/nexus-frontend/src/components/content/PrototypeContinuationGrid.vue`
- Create: `project/nexus-frontend/src/components/content/PrototypeCommentComposer.vue`

- [ ] **Step 1: Capture the pre-migration source contract for the detail route**

Run:

```bash
cd project/nexus-frontend
grep -nE "PrototypeShell|PrototypeReadingColumn|data-prototype-detail|data-prototype-comments" src/views/ContentDetail.vue
```

Expected: no matches before the migration. This is the RED checkpoint for the new shell markers.

- [ ] **Step 2: Rebuild `ContentDetail.vue` around the new shell**

Required implementation shape:

1. wrap the route in `PrototypeShell`
2. split the page into heading block, hero-media block, reading column, continuation section, and comments section
3. keep the existing data-loading, like, share, comment, and reply logic
4. move prose, tags, composer, and comment stream into `PrototypeReadingColumn`
5. remove the old heavy centered stage, oversized radii, and editorial panel styling

```vue
<PrototypeShell>
  <article data-prototype-detail class="space-y-12">
    <header class="space-y-4">...</header>
    <section class="max-w-content">hero media</section>
    <PrototypeReadingColumn>
      intro
      body
      tags
    </PrototypeReadingColumn>
    <PrototypeContinuationGrid />
    <PrototypeReadingColumn data-prototype-comments>
      <PrototypeCommentComposer />
      comments
    </PrototypeReadingColumn>
  </article>
</PrototypeShell>
```

- [ ] **Step 3: Re-run the source contract and build**

Run:

```bash
cd project/nexus-frontend
grep -nE "PrototypeShell|PrototypeReadingColumn|data-prototype-detail|data-prototype-comments" src/views/ContentDetail.vue
npm run build
```

Expected:

1. the grep command now shows the new markers
2. the build passes

- [ ] **Step 4: Manually verify the detail route against the spec**

Check on desktop:

1. fixed nav does not cover the heading
2. hero width is visibly wider than body width
3. body text, tags, composer, and comments share one left-aligned reading column
4. the continuation block feels like downstream reading, not a separate dashboard panel
5. the page no longer reads like a floating editorial showcase

- [ ] **Step 5: Commit**

```bash
cd project/nexus-frontend
git add src/views/ContentDetail.vue src/components/content/PrototypeContinuationGrid.vue src/components/content/PrototypeCommentComposer.vue
git commit -m "feat: align content detail to desktop prototype"
```

### Task 4: Migrate Home To The Prototype Gallery/List Shell

**Files:**
- Modify: `project/nexus-frontend/src/views/Home.vue`
- Modify: `project/nexus-frontend/src/components/home/HomeHeroCard.vue`

- [ ] **Step 1: Capture the pre-migration source contract for the home route**

Run:

```bash
cd project/nexus-frontend
grep -nE "PrototypeShell|data-prototype-home|max-w-screen-2xl|backdrop-blur-2xl|fixed inset-x-0 top-0" src/views/Home.vue
```

Expected:

1. no `PrototypeShell` or `data-prototype-home` matches yet
2. at least one bespoke home-shell clue such as `max-w-screen-2xl`, `backdrop-blur-2xl`, or the fixed header markup is still present

- [ ] **Step 2: Rebuild `Home.vue` on the authenticated shell**

Required implementation shape:

1. use `PrototypeShell`
2. keep real feed loading logic and fallback data behavior
3. remove the floating summary card, side editorial aside, and giant asymmetric showcase stage
4. produce a calm featured-entry block followed by prototype-shaped content entry sections
5. keep left-aligned type and restrained surface treatment

- [ ] **Step 3: Re-run the source contract and build**

Run:

```bash
cd project/nexus-frontend
grep -nE "PrototypeShell|data-prototype-home|max-w-screen-2xl|backdrop-blur-2xl|fixed inset-x-0 top-0" src/views/Home.vue
npm run build
```

Expected:

1. `PrototypeShell` and `data-prototype-home` now appear
2. bespoke stage markers such as `max-w-screen-2xl`, `backdrop-blur-2xl`, and the old fixed header markup are gone from the route file
3. the build passes

- [ ] **Step 4: Manually verify the home route on desktop**

Check:

1. top navigation aligns with the new shell instead of the old showcase header
2. the featured entry feels anchored to the content grid rather than floating on a stage
3. list cards read as calm content entry points, not decorative editorial panels
4. heading starts align with the detail-page shell

- [ ] **Step 5: Commit**

```bash
cd project/nexus-frontend
git add src/views/Home.vue src/components/home/HomeHeroCard.vue
git commit -m "feat: align home gallery to desktop prototype"
```

### Task 5: Migrate Login And Register To The Auth Prototype Shell

**Files:**
- Modify: `project/nexus-frontend/src/views/Login.vue`
- Modify: `project/nexus-frontend/src/views/Register.vue`

- [ ] **Step 1: Capture the pre-migration source contract for the auth routes**

Run:

```bash
cd project/nexus-frontend
grep -nE "PrototypeAuthShell|data-auth-shell|data-prototype-nav" src/views/Login.vue src/views/Register.vue
```

Expected: no matches before the migration.

- [ ] **Step 2: Rebuild `Login.vue` and `Register.vue` with the auth-shell exception**

Required implementation shape:

1. use `PrototypeAuthShell`
2. preserve existing auth API behavior and redirects
3. follow prototype-auth composition instead of the authenticated app shell
4. keep desktop-first fidelity for form layout, spacing, and hierarchy

- [ ] **Step 3: Re-run the source contract and build**

Run:

```bash
cd project/nexus-frontend
grep -nE "PrototypeAuthShell|data-auth-shell|data-prototype-nav" src/views/Login.vue src/views/Register.vue
npm run build
```

Expected:

1. `PrototypeAuthShell` and `data-auth-shell` now appear in both files
2. `data-prototype-nav` does not appear in the auth routes
3. the build passes

- [ ] **Step 4: Manually verify both auth pages on desktop**

Check:

1. auth pages do not reuse the fixed authenticated nav
2. forms remain fully functional with existing redirects and query-param behavior
3. the layout matches the prototype's centered or split auth composition
4. the typography and spacing feel like the same product family as the authenticated routes

- [ ] **Step 5: Commit**

```bash
cd project/nexus-frontend
git add src/views/Login.vue src/views/Register.vue
git commit -m "feat: align auth routes to prototype desktop shell"
```

### Task 6: Migrate Search, Notifications, Relation, Publish, Risk, And Profile-Family Routes

**Files:**
- Modify: `project/nexus-frontend/src/views/SearchResults.vue`
- Modify: `project/nexus-frontend/src/views/Notifications.vue`
- Modify: `project/nexus-frontend/src/views/RelationList.vue`
- Modify: `project/nexus-frontend/src/views/Publish.vue`
- Modify: `project/nexus-frontend/src/views/RiskCenter.vue`
- Modify: `project/nexus-frontend/src/views/Profile.vue`
- Modify: `project/nexus-frontend/src/components/profile/ProfileHero.vue`
- Modify: `project/nexus-frontend/src/components/profile/ProfileContentGrid.vue`
- Modify: `project/nexus-frontend/src/components/profile/EditProfilePanel.vue`
- Modify: `project/nexus-frontend/src/components/publish/PublishWorkspace.vue`
- Modify: `project/nexus-frontend/src/components/risk/RiskOverviewCard.vue`
- Modify: `project/nexus-frontend/src/components/risk/AppealFormPanel.vue`

- [ ] **Step 1: Capture the current editorial-shell dependencies across the support routes**

Run:

```bash
cd project/nexus-frontend
grep -nE "TheNavBar|TheDock|page-main|page-wrap|max-w-editorial|paper-panel|tonal-panel" \
  src/views/SearchResults.vue \
  src/views/Notifications.vue \
  src/views/RelationList.vue \
  src/views/Publish.vue \
  src/views/RiskCenter.vue \
  src/views/Profile.vue
```

Expected: at least one match before migration. Save this output so you can prove the cleanup later.

- [ ] **Step 2: Migrate the support routes in this order**

Order:

1. `SearchResults.vue`
2. `Notifications.vue`
3. `RelationList.vue`
4. `Publish.vue`
5. `RiskCenter.vue`
6. `Profile.vue`

Rules for each route:

1. wrap with `PrototypeShell`
2. remove `TheNavBar`, `TheDock`, `page-wrap`, and `page-main`
3. align heading start lines and outer shell width with `ContentDetail.vue`
4. keep route-specific behavior intact
5. for `/profile` and `/user/:userId`, preserve shared implementation while allowing self and other-user differences from route state, not from separate shells

Route-specific composition targets:

1. `/search` follows `_22/code.html` as the primary source, with `_10/code.html` and `_26/code.html` only as fallback references for local adaptation
2. `/notifications` follows `_9/code.html` as the primary source
3. `/relation/:type/:userId` follows `_16/code.html` as the primary source
4. `/publish` follows `_14/code.html` as the primary source
5. `/settings/risk` follows `_17/code.html` as the primary source
6. `/profile` follows `_8/code.html` as the primary source, with `_25/code.html` only for self-profile details
7. `/user/:userId` stays inside `Profile.vue` but must visually satisfy the other-user profile variant from `_8/code.html` with `_16/code.html` as fallback

- [ ] **Step 3: Re-run the support-route source contract and build**

Run:

```bash
cd project/nexus-frontend
grep -nE "PrototypeShell|data-prototype-search|data-prototype-notifications|data-prototype-relation|data-prototype-publish|data-prototype-risk|data-prototype-profile" \
  src/views/SearchResults.vue \
  src/views/Notifications.vue \
  src/views/RelationList.vue \
  src/views/Publish.vue \
  src/views/RiskCenter.vue \
  src/views/Profile.vue
grep -nE "TheNavBar|TheDock|page-main|page-wrap|max-w-editorial|paper-panel|tonal-panel" \
  src/views/SearchResults.vue \
  src/views/Notifications.vue \
  src/views/RelationList.vue \
  src/views/Publish.vue \
  src/views/RiskCenter.vue \
  src/views/Profile.vue
npm run build
```

Expected:

1. each route now contains its prototype-shell marker
2. old-shell markers are gone from the route files
3. the build passes

- [ ] **Step 4: Run the desktop acceptance matrix for each route family**

Check:

1. `/search` vs `_22/code.html`: title, search controls, and result list start on the shared shell line; results do not sit inside a floating editorial panel; filter or empty-state treatment stays visually restrained
2. `/notifications` vs `_9/code.html`: title and list share the shell rhythm; notification items read as content rows instead of standalone cards from another UI family
3. `/relation/:type/:userId` vs `_16/code.html`: relation header, user context, and list alignment match the mapped prototype without nav duplication or dashboard chrome
4. `/publish` vs `_14/code.html`: the editor workspace is framed by the shell, the main composition stays prototype-first, and creation controls do not drift into a tooling dashboard aesthetic
5. `/settings/risk` vs `_17/code.html`: overview and appeal areas align to the shell, hierarchy follows the mapped prototype, and semantic warning states remain functional without becoming decorative
6. `/profile` vs `_8/code.html`: hero, profile metadata, and content grid align to the shared shell while preserving self-profile controls from the current product behavior
7. `/user/:userId` vs `_8/code.html` plus `_16/code.html` fallback: the same `Profile.vue` implementation adapts to other-user state without reintroducing a separate shell or self-only controls

- [ ] **Step 5: Commit**

```bash
cd project/nexus-frontend
git add src/views/SearchResults.vue src/views/Notifications.vue src/views/RelationList.vue src/views/Publish.vue src/views/RiskCenter.vue src/views/Profile.vue src/components/profile src/components/publish/PublishWorkspace.vue src/components/risk
git commit -m "feat: migrate prototype secondary routes to new shell"
```

### Task 7: Retire Old Editorial Shell Usage And Run Full Verification

**Files:**
- Modify: `project/nexus-frontend/src/components/TheNavBar.vue`
- Modify: `project/nexus-frontend/src/components/TheDock.vue`
- Modify: `project/nexus-frontend/src/assets/main.css`
- Optional cleanup: remove unused editorial shell helpers only if no in-scope route imports them

- [ ] **Step 1: Run a repo-wide search for remaining editorial shell usage**

Run:

```bash
cd project/nexus-frontend
grep -R -nE "TheNavBar|TheDock|page-main|page-wrap|max-w-editorial|paper-panel|tonal-panel" src/views src/components src/assets/main.css
```

Expected: the output is now limited to deliberately quarantined legacy files or dead helpers that are about to be removed.

- [ ] **Step 2: Retire or quarantine the old editorial shell**

Do only what the codebase state supports:

1. if `TheNavBar.vue` and `TheDock.vue` are no longer referenced by any in-scope route, either delete them or leave them unused with a follow-up cleanup note
2. remove dead editorial shell helpers from `main.css` if they are no longer referenced
3. do not touch unrelated user changes outside the shell migration

- [ ] **Step 3: Re-run the repo-wide shell search**

Run:

```bash
cd project/nexus-frontend
grep -R -nE "TheNavBar|TheDock|page-main|page-wrap|max-w-editorial|paper-panel|tonal-panel" src/views src/components src/assets/main.css
```

Expected: no matches remain in in-scope route files. If legacy components are intentionally left behind, the only remaining matches should be inside those quarantined files themselves and should be documented in the commit message or follow-up note.

- [ ] **Step 4: Run the full verification suite**

Run:

```bash
cd project/nexus-frontend
npm run test
npm run build
```

Expected: all shell tests PASS and the production build PASSes.

- [ ] **Step 5: Run route-level manual desktop checks**

Check:

1. `/`
2. `/login`
3. `/register`
4. `/content/:postId`
5. `/profile`
6. `/user/:userId`
7. `/relation/:type/:userId`
8. `/notifications`
9. `/search`
10. `/publish`
11. `/settings/risk`

Pass criteria:

1. authenticated routes share one shell
2. auth routes use the auth-shell exception
3. fixed nav never obscures the first content block
4. detail-page body, tags, and comments are visibly narrower than the hero/media block
5. background, shadow, and radius no longer define the UI
6. if local prototype HTML exports are still absent, fidelity was checked against the approved route-to-prototype mapping from the specs instead of blocking completion

- [ ] **Step 6: Commit final cleanup**

```bash
cd project/nexus-frontend
git add src/assets/main.css src/components/TheNavBar.vue src/components/TheDock.vue tests/layout/prototype-shell.spec.ts
git commit -m "refactor: retire editorial shell from prototype routes"
```
