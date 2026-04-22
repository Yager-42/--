# Nexus Editorial Social Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the first usable Vue frontend for Nexus as an authenticated Editorial Social application covering login, timeline, search, profile, notifications, and post detail against existing backend APIs.

**Architecture:** Create the app from scratch around a small but durable Vue 3 structure: shared HTTP client, centralized auth/session handling, Pinia for cross-page state, route-guarded shell layout, and page-specific feature modules. Keep the implementation content-first and API-aligned, with semantic tokens and reusable card-level components instead of page-local ad hoc markup.

**Tech Stack:** Vue 3, TypeScript, Vite, Vue Router, Pinia, Axios, Tailwind CSS, Vitest, Vue Test Utils

---

## File Structure

Create or modify the following files and keep responsibilities narrow:

- Create: `src/main.ts`
- Create: `src/App.vue`
- Create: `src/env.d.ts`
- Create: `src/test/setup.ts`
- Create: `src/styles/tokens.css`
- Create: `src/styles/main.css`
- Create: `src/router/index.ts`
- Create: `src/router/guards.ts`
- Create: `src/services/http/client.ts`
- Create: `src/services/http/errors.ts`
- Create: `src/services/http/session.ts`
- Create: `src/services/api/authApi.ts`
- Create: `src/services/api/feedApi.ts`
- Create: `src/services/api/searchApi.ts`
- Create: `src/services/api/profileApi.ts`
- Create: `src/services/api/notificationApi.ts`
- Create: `src/services/api/contentApi.ts`
- Create: `src/stores/auth.ts`
- Create: `src/stores/ui.ts`
- Create: `src/types/api.ts`
- Create: `src/types/auth.ts`
- Create: `src/types/feed.ts`
- Create: `src/types/profile.ts`
- Create: `src/types/search.ts`
- Create: `src/types/notification.ts`
- Create: `src/types/content.ts`
- Create: `src/types/viewModels.ts`
- Create: `src/utils/mappers/feed.ts`
- Create: `src/utils/mappers/profile.ts`
- Create: `src/layouts/AuthLayout.vue`
- Create: `src/layouts/AppShell.vue`
- Create: `src/components/nav/PrimarySidebar.vue`
- Create: `src/components/nav/MobileBottomNav.vue`
- Create: `src/components/common/AppLogo.vue`
- Create: `src/components/common/AppSearchTrigger.vue`
- Create: `src/components/common/StatusMessage.vue`
- Create: `src/components/common/EmptyState.vue`
- Create: `src/components/common/LoadingSkeleton.vue`
- Create: `src/components/feed/FeedCard.vue`
- Create: `src/components/feed/FeedComposerEntry.vue`
- Create: `src/components/feed/TimelineList.vue`
- Create: `src/components/search/SearchResultTabs.vue`
- Create: `src/components/search/UserResultRow.vue`
- Create: `src/components/profile/ProfileHeader.vue`
- Create: `src/components/notifications/NotificationList.vue`
- Create: `src/components/post/PostDetailCard.vue`
- Create: `src/components/comment/CommentList.vue`
- Create: `src/views/LoginView.vue`
- Create: `src/views/TimelineView.vue`
- Create: `src/views/SearchView.vue`
- Create: `src/views/ProfileView.vue`
- Create: `src/views/MeView.vue`
- Create: `src/views/NotificationsView.vue`
- Create: `src/views/PostDetailView.vue`
- Create: `src/router/router.spec.ts`
- Create: `src/services/http/client.spec.ts`
- Create: `src/stores/auth.spec.ts`
- Create: `src/views/LoginView.spec.ts`
- Create: `src/views/TimelineView.spec.ts`
- Create: `src/views/SearchView.spec.ts`
- Create: `src/views/ProfileView.spec.ts`
- Create: `src/views/NotificationsView.spec.ts`
- Create: `src/views/PostDetailView.spec.ts`
- Modify: `index.html`
- Modify: `tailwind.config.ts`

Notes:

- Keep `services/api/*` thin and typed.
- Keep view models in mapper files so UI does not consume raw backend DTOs directly.
- Do not add extra dependencies unless a task proves they are necessary.
- Do not build publish studio or admin flows in this plan.

## Chunk 1: Platform Foundation

### Task 1: Create the source tree, test bootstrap, and design tokens

**Files:**
- Create: `src/main.ts`
- Create: `src/App.vue`
- Create: `src/env.d.ts`
- Create: `src/test/setup.ts`
- Create: `src/styles/tokens.css`
- Create: `src/styles/main.css`
- Modify: `index.html`
- Modify: `tailwind.config.ts`
- Test: `src/router/router.spec.ts`

- [ ] **Step 1: Write the failing router bootstrap test**

```ts
import { describe, expect, it } from 'vitest'
import { createAppRouter } from '@/router'

describe('createAppRouter', () => {
  it('registers the editorial social route map', () => {
    const router = createAppRouter()
    const routeNames = router.getRoutes().map((route) => route.name)

    expect(routeNames).toEqual(
      expect.arrayContaining([
        'login',
        'timeline',
        'search',
        'profile',
        'me',
        'notifications',
        'post-detail'
      ])
    )
  })
})
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `npm test -- src/router/router.spec.ts`

Expected: FAIL because `@/router` does not exist yet.

- [ ] **Step 3: Create the minimal app bootstrap and style files**

```ts
// src/main.ts
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import { createAppRouter } from './router'
import './styles/main.css'

const app = createApp(App)
app.use(createPinia())
app.use(createAppRouter())
app.mount('#app')
```

```vue
<!-- src/App.vue -->
<template>
  <RouterView />
</template>
```

```css
/* src/styles/tokens.css */
:root {
  --nx-bg: #f6f7f8;
  --nx-surface: #ffffff;
  --nx-surface-muted: #eef2f6;
  --nx-text: #17202a;
  --nx-text-muted: #5b6673;
  --nx-primary: #2563eb;
  --nx-accent: #f26f63;
  --nx-border: #dbe2ea;
  --nx-danger: #dc2626;
  --nx-success: #16a34a;
  --nx-radius-card: 18px;
  --nx-radius-pill: 999px;
  --nx-shadow-card: 0 24px 48px -32px rgba(23, 32, 42, 0.18);
}
```

```css
/* src/styles/main.css */
@import './tokens.css';

@tailwind base;
@tailwind components;
@tailwind utilities;

html,
body,
#app {
  min-height: 100%;
}

body {
  margin: 0;
  font-family: 'Public Sans', 'PingFang SC', 'Microsoft YaHei', sans-serif;
  background: var(--nx-bg);
  color: var(--nx-text);
}
```

- [ ] **Step 4: Add editorial token coverage in Tailwind and preload fonts in `index.html`**

Add semantic colors, typography aliases, and safe background defaults in `tailwind.config.ts`. Update `index.html` to load the chosen typefaces and set `meta viewport`.

- [ ] **Step 5: Create minimal router factory to satisfy the test**

```ts
// src/router/index.ts
import { createMemoryHistory, createRouter, createWebHistory } from 'vue-router'

export function createAppRouter() {
  const history = import.meta.env.MODE === 'test' ? createMemoryHistory() : createWebHistory()

  return createRouter({
    history,
    routes: [
      { path: '/login', name: 'login', component: { template: '<div />' } },
      { path: '/', name: 'timeline', component: { template: '<div />' } },
      { path: '/search', name: 'search', component: { template: '<div />' } },
      { path: '/profile/:id', name: 'profile', component: { template: '<div />' } },
      { path: '/me', name: 'me', component: { template: '<div />' } },
      { path: '/notifications', name: 'notifications', component: { template: '<div />' } },
      { path: '/post/:id', name: 'post-detail', component: { template: '<div />' } }
    ]
  })
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `npm test -- src/router/router.spec.ts`

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add index.html tailwind.config.ts src/main.ts src/App.vue src/env.d.ts src/test/setup.ts src/styles/tokens.css src/styles/main.css src/router/index.ts src/router/router.spec.ts
git commit -m "feat: bootstrap editorial social frontend shell"
```

### Task 2: Build the shared HTTP client and session primitives

**Files:**
- Create: `src/services/http/client.ts`
- Create: `src/services/http/errors.ts`
- Create: `src/services/http/session.ts`
- Create: `src/types/api.ts`
- Create: `src/types/auth.ts`
- Test: `src/services/http/client.spec.ts`

- [ ] **Step 1: Write the failing HTTP normalization test**

```ts
import { describe, expect, it } from 'vitest'
import { normalizeApiResponse } from '@/services/http/client'

describe('normalizeApiResponse', () => {
  it('returns data for code 0000 and throws for business errors', () => {
    expect(normalizeApiResponse({ code: '0000', info: 'ok', data: { userId: 1 } })).toEqual({ userId: 1 })

    expect(() =>
      normalizeApiResponse({ code: '0404', info: 'not found', data: null })
    ).toThrow('not found')
  })
})
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `npm test -- src/services/http/client.spec.ts`

Expected: FAIL because the HTTP client module does not exist.

- [ ] **Step 3: Implement minimal response types and normalization**

```ts
// src/types/api.ts
export type ApiEnvelope<T> = {
  code: string
  info: string
  data: T
}
```

```ts
// src/services/http/errors.ts
export class ApiError extends Error {
  constructor(
    message: string,
    public readonly code: string
  ) {
    super(message)
  }
}
```

```ts
// src/services/http/client.ts
import axios from 'axios'
import type { ApiEnvelope } from '@/types/api'
import { ApiError } from './errors'

export function normalizeApiResponse<T>(payload: ApiEnvelope<T>): T {
  if (payload.code !== '0000') {
    throw new ApiError(payload.info || payload.code, payload.code)
  }
  return payload.data
}

export const http = axios.create({
  baseURL: '/',
  timeout: 10000
})
```

- [ ] **Step 4: Add session storage primitives**

```ts
// src/services/http/session.ts
const ACCESS_TOKEN_KEY = 'nexus.accessToken'
const REFRESH_TOKEN_KEY = 'nexus.refreshToken'

export function readSessionTokens() {
  return {
    accessToken: localStorage.getItem(ACCESS_TOKEN_KEY) ?? '',
    refreshToken: localStorage.getItem(REFRESH_TOKEN_KEY) ?? ''
  }
}

export function writeSessionTokens(accessToken: string, refreshToken: string) {
  localStorage.setItem(ACCESS_TOKEN_KEY, accessToken)
  localStorage.setItem(REFRESH_TOKEN_KEY, refreshToken)
}

export function clearSessionTokens() {
  localStorage.removeItem(ACCESS_TOKEN_KEY)
  localStorage.removeItem(REFRESH_TOKEN_KEY)
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `npm test -- src/services/http/client.spec.ts`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/services/http/client.ts src/services/http/errors.ts src/services/http/session.ts src/types/api.ts src/types/auth.ts src/services/http/client.spec.ts
git commit -m "feat: add shared nexus http primitives"
```

## Chunk 2: Authentication and Route Guarding

### Task 3: Implement auth store, auth API, and route guards

**Files:**
- Create: `src/services/api/authApi.ts`
- Create: `src/stores/auth.ts`
- Create: `src/router/guards.ts`
- Modify: `src/router/index.ts`
- Test: `src/stores/auth.spec.ts`

- [ ] **Step 1: Write the failing auth session test**

```ts
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from '@/stores/auth'

describe('auth store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
  })

  it('persists session tokens after login success', async () => {
    const store = useAuthStore()
    await store.completeLogin({
      userId: 7,
      tokenName: 'Authorization',
      tokenPrefix: 'Bearer',
      token: 'token-1',
      refreshToken: 'refresh-1'
    })

    expect(store.isAuthenticated).toBe(true)
    expect(localStorage.getItem('nexus.accessToken')).toBe('Bearer token-1')
    expect(localStorage.getItem('nexus.refreshToken')).toBe('refresh-1')
  })
})
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `npm test -- src/stores/auth.spec.ts`

Expected: FAIL because `useAuthStore` does not exist.

- [ ] **Step 3: Implement auth API helpers and auth store**

```ts
// src/services/api/authApi.ts
import { http, normalizeApiResponse } from '@/services/http/client'
import type { ApiEnvelope } from '@/types/api'
import type { AuthLoginPayload, AuthMe, AuthRegisterPayload, AuthTokenResponse } from '@/types/auth'

export async function loginByPassword(payload: AuthLoginPayload) {
  const response = await http.post<ApiEnvelope<AuthTokenResponse>>('/api/v1/auth/login/password', payload)
  return normalizeApiResponse(response.data)
}

export async function registerAccount(payload: AuthRegisterPayload) {
  const response = await http.post('/api/v1/auth/register', payload)
  return normalizeApiResponse(response.data)
}

export async function fetchMe() {
  const response = await http.get<ApiEnvelope<AuthMe>>('/api/v1/auth/me')
  return normalizeApiResponse(response.data)
}
```

```ts
// src/stores/auth.ts
import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { clearSessionTokens, readSessionTokens, writeSessionTokens } from '@/services/http/session'

export const useAuthStore = defineStore('auth', () => {
  const currentUser = ref<null | { userId: number; nickname?: string; avatarUrl?: string }>(null)
  const accessToken = ref(readSessionTokens().accessToken)
  const refreshToken = ref(readSessionTokens().refreshToken)
  const isAuthenticated = computed(() => Boolean(accessToken.value))

  async function completeLogin(payload: {
    userId: number
    tokenName: string
    tokenPrefix: string
    token: string
    refreshToken: string
  }) {
    accessToken.value = `${payload.tokenPrefix} ${payload.token}`.trim()
    refreshToken.value = payload.refreshToken
    currentUser.value = { userId: payload.userId }
    writeSessionTokens(accessToken.value, refreshToken.value)
  }

  function logoutLocally() {
    currentUser.value = null
    accessToken.value = ''
    refreshToken.value = ''
    clearSessionTokens()
  }

  return { currentUser, accessToken, refreshToken, isAuthenticated, completeLogin, logoutLocally }
})
```

- [ ] **Step 4: Add a minimal auth guard**

Guard all routes except `login`. Redirect unauthenticated users to `/login`. Redirect authenticated users away from `/login` to `/`.

- [ ] **Step 5: Run the auth store test to verify it passes**

Run: `npm test -- src/stores/auth.spec.ts`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/services/api/authApi.ts src/stores/auth.ts src/router/guards.ts src/router/index.ts src/stores/auth.spec.ts src/types/auth.ts
git commit -m "feat: add auth store and route guard foundation"
```

### Task 4: Build the login and registration page

**Files:**
- Create: `src/layouts/AuthLayout.vue`
- Create: `src/components/common/AppLogo.vue`
- Create: `src/components/common/StatusMessage.vue`
- Create: `src/views/LoginView.vue`
- Modify: `src/router/index.ts`
- Test: `src/views/LoginView.spec.ts`

- [ ] **Step 1: Write the failing login form behavior test**

```ts
import { mount } from '@vue/test-utils'
import LoginView from '@/views/LoginView.vue'

test('submits password login with phone and password', async () => {
  const wrapper = mount(LoginView)

  await wrapper.get('[data-test=phone-input]').setValue('13800138000')
  await wrapper.get('[data-test=password-input]').setValue('secret123')
  await wrapper.get('form').trigger('submit.prevent')

  expect(wrapper.emitted('login-submitted')).toBeTruthy()
})
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `npm test -- src/views/LoginView.spec.ts`

Expected: FAIL because `LoginView.vue` does not exist or does not emit/submit as expected.

- [ ] **Step 3: Implement the auth layout and page**

Requirements:

- Content-first, calm editorial treatment
- Login/register tab switch
- Inline field errors
- Primary button loading state
- Keyboard and focus accessibility

Minimal form surface:

```vue
<form @submit.prevent="handleLogin">
  <label for="phone">手机号</label>
  <input id="phone" data-test="phone-input" v-model="loginForm.phone" autocomplete="username" />

  <label for="password">密码</label>
  <input id="password" data-test="password-input" v-model="loginForm.password" type="password" autocomplete="current-password" />

  <button data-test="login-submit" type="submit">登录</button>
</form>
```

- [ ] **Step 4: Wire the page to the auth store and success redirect**

On successful login:

- call `completeLogin`
- fetch `/api/v1/auth/me`
- route to `/`

On failure:

- show inline field or form-level message

- [ ] **Step 5: Run the test to verify it passes**

Run: `npm test -- src/views/LoginView.spec.ts`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/layouts/AuthLayout.vue src/components/common/AppLogo.vue src/components/common/StatusMessage.vue src/views/LoginView.vue src/views/LoginView.spec.ts src/router/index.ts
git commit -m "feat: add editorial social login entry"
```

## Chunk 3: Shell and Timeline

### Task 5: Build the authenticated shell layout and shared navigation

**Files:**
- Create: `src/layouts/AppShell.vue`
- Create: `src/components/nav/PrimarySidebar.vue`
- Create: `src/components/nav/MobileBottomNav.vue`
- Create: `src/components/common/AppSearchTrigger.vue`
- Modify: `src/router/index.ts`
- Test: `src/router/router.spec.ts`

- [ ] **Step 1: Extend the failing router test with shell expectations**

Add assertions that authenticated routes render through the shell layout and that navigation items are `timeline`, `search`, `notifications`, and `me`.

- [ ] **Step 2: Run the test to verify it fails**

Run: `npm test -- src/router/router.spec.ts`

Expected: FAIL because the route metadata or shell layout is not present yet.

- [ ] **Step 3: Implement `AppShell.vue` with responsive layout**

Requirements:

- Desktop: left nav, main content, right rail slot
- Mobile: top bar plus bottom nav
- Search trigger always reachable
- No horizontal overflow

- [ ] **Step 4: Update route definitions to use the shell**

Use nested routes so authenticated views live under `AppShell`.

- [ ] **Step 5: Run the test to verify it passes**

Run: `npm test -- src/router/router.spec.ts`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/layouts/AppShell.vue src/components/nav/PrimarySidebar.vue src/components/nav/MobileBottomNav.vue src/components/common/AppSearchTrigger.vue src/router/index.ts src/router/router.spec.ts
git commit -m "feat: add editorial social app shell"
```

### Task 6: Implement the timeline page and feed card system

**Files:**
- Create: `src/services/api/feedApi.ts`
- Create: `src/types/feed.ts`
- Create: `src/types/viewModels.ts`
- Create: `src/utils/mappers/feed.ts`
- Create: `src/components/feed/FeedCard.vue`
- Create: `src/components/feed/TimelineList.vue`
- Create: `src/components/feed/FeedComposerEntry.vue`
- Create: `src/components/common/EmptyState.vue`
- Create: `src/components/common/LoadingSkeleton.vue`
- Create: `src/views/TimelineView.vue`
- Test: `src/views/TimelineView.spec.ts`

- [ ] **Step 1: Write the failing timeline rendering test**

```ts
import { mount } from '@vue/test-utils'
import TimelineView from '@/views/TimelineView.vue'

test('renders feed cards from mapped timeline items', async () => {
  const wrapper = mount(TimelineView, {
    props: {
      initialItems: [
        {
          id: '101',
          authorName: 'Nexus User',
          summary: 'Hello editorial social',
          likeCountLabel: '12'
        }
      ]
    }
  })

  expect(wrapper.text()).toContain('Nexus User')
  expect(wrapper.text()).toContain('Hello editorial social')
})
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `npm test -- src/views/TimelineView.spec.ts`

Expected: FAIL because `TimelineView.vue` does not exist.

- [ ] **Step 3: Implement typed feed API and mapper**

Map backend `FeedTimelineResponseDTO` items into a focused card view model:

- `id`
- `authorId`
- `authorName`
- `authorAvatar`
- `summary`
- `body`
- `publishTimeLabel`
- `likeCountLabel`
- `liked`
- `followed`

- [ ] **Step 4: Build feed UI components**

Requirements:

- Feed card supports author, body, summary, media placeholder, interaction row
- Timeline view supports loading, empty, and error states
- Composer entry is a lightweight placeholder only, not full publish studio

- [ ] **Step 5: Run the timeline test to verify it passes**

Run: `npm test -- src/views/TimelineView.spec.ts`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/services/api/feedApi.ts src/types/feed.ts src/types/viewModels.ts src/utils/mappers/feed.ts src/components/feed/FeedCard.vue src/components/feed/TimelineList.vue src/components/feed/FeedComposerEntry.vue src/components/common/EmptyState.vue src/components/common/LoadingSkeleton.vue src/views/TimelineView.vue src/views/TimelineView.spec.ts
git commit -m "feat: add timeline and feed card experience"
```

## Chunk 4: Search and Profile

### Task 7: Implement search with result tabs and URL-synced query state

**Files:**
- Create: `src/services/api/searchApi.ts`
- Create: `src/types/search.ts`
- Create: `src/components/search/SearchResultTabs.vue`
- Create: `src/components/search/UserResultRow.vue`
- Create: `src/views/SearchView.vue`
- Test: `src/views/SearchView.spec.ts`

- [ ] **Step 1: Write the failing search routing test**

```ts
import { mount } from '@vue/test-utils'
import SearchView from '@/views/SearchView.vue'

test('syncs the search input with the route query', async () => {
  const wrapper = mount(SearchView, {
    global: {
      mocks: {
        $route: { query: { q: 'nexus' } }
      }
    }
  })

  expect((wrapper.get('[data-test=search-input]').element as HTMLInputElement).value).toBe('nexus')
})
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `npm test -- src/views/SearchView.spec.ts`

Expected: FAIL because `SearchView.vue` does not exist.

- [ ] **Step 3: Implement typed search API helpers**

Include:

- `searchContent(query, type)`
- `suggestKeywords(keyword)`

Normalize results into:

- compact feed-like content result cards
- user rows with avatar, nickname, bio, follow state

- [ ] **Step 4: Build the search page**

Requirements:

- search input pinned at the top
- tabs for content and users
- debounce suggestions, but route query remains the source of truth for the result page
- explicit empty state

- [ ] **Step 5: Run the search test to verify it passes**

Run: `npm test -- src/views/SearchView.spec.ts`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/services/api/searchApi.ts src/types/search.ts src/components/search/SearchResultTabs.vue src/components/search/UserResultRow.vue src/views/SearchView.vue src/views/SearchView.spec.ts
git commit -m "feat: add search discovery page"
```

### Task 8: Implement public profile and self page

**Files:**
- Create: `src/services/api/profileApi.ts`
- Create: `src/types/profile.ts`
- Create: `src/utils/mappers/profile.ts`
- Create: `src/components/profile/ProfileHeader.vue`
- Create: `src/views/ProfileView.vue`
- Create: `src/views/MeView.vue`
- Test: `src/views/ProfileView.spec.ts`

- [ ] **Step 1: Write the failing profile header test**

```ts
import { mount } from '@vue/test-utils'
import ProfileView from '@/views/ProfileView.vue'

test('renders profile identity and stats', () => {
  const wrapper = mount(ProfileView, {
    props: {
      profile: {
        nickname: 'Editor',
        bio: 'Writes about distributed systems',
        followerCountLabel: '128'
      }
    }
  })

  expect(wrapper.text()).toContain('Editor')
  expect(wrapper.text()).toContain('Writes about distributed systems')
  expect(wrapper.text()).toContain('128')
})
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `npm test -- src/views/ProfileView.spec.ts`

Expected: FAIL because profile view files are missing.

- [ ] **Step 3: Implement profile API helpers**

Include:

- public profile fetch
- aggregated profile page fetch
- self profile fetch
- self privacy fetch

- [ ] **Step 4: Build profile header, public profile, and self page**

Requirements:

- Profile header with avatar, nickname, bio, stats, follow button
- Public profile page with profile feed
- Self page with edit profile and privacy entry actions
- Shared feed card experience reused under the profile

- [ ] **Step 5: Run the profile test to verify it passes**

Run: `npm test -- src/views/ProfileView.spec.ts`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/services/api/profileApi.ts src/types/profile.ts src/utils/mappers/profile.ts src/components/profile/ProfileHeader.vue src/views/ProfileView.vue src/views/MeView.vue src/views/ProfileView.spec.ts
git commit -m "feat: add user profile pages"
```

## Chunk 5: Notifications and Post Detail

### Task 9: Implement notifications list and unread actions

**Files:**
- Create: `src/services/api/notificationApi.ts`
- Create: `src/types/notification.ts`
- Create: `src/views/NotificationsView.vue`
- Create: `src/components/notifications/NotificationList.vue`
- Create: `src/stores/ui.ts`
- Test: `src/views/NotificationsView.spec.ts`

- [ ] **Step 1: Write the failing notifications test**

```ts
import { mount } from '@vue/test-utils'
import NotificationsView from '@/views/NotificationsView.vue'

test('renders unread notification rows distinctly', () => {
  const wrapper = mount(NotificationsView, {
    props: {
      notifications: [
        { id: '1', actorName: 'Alice', actionText: 'liked your post', unread: true }
      ]
    }
  })

  expect(wrapper.text()).toContain('Alice')
  expect(wrapper.find('[data-test=notification-unread]').exists()).toBe(true)
})
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `npm test -- src/views/NotificationsView.spec.ts`

Expected: FAIL because `NotificationsView.vue` does not exist.

- [ ] **Step 3: Implement notification API helpers and UI state**

Include:

- fetch list
- mark single read
- mark all read
- lightweight unread indicator state for nav badge support

- [ ] **Step 4: Build the notifications page**

Requirements:

- grouped chronological list
- clear unread treatment
- “mark all read” action
- graceful empty state

- [ ] **Step 5: Run the notifications test to verify it passes**

Run: `npm test -- src/views/NotificationsView.spec.ts`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/services/api/notificationApi.ts src/types/notification.ts src/views/NotificationsView.vue src/components/notifications/NotificationList.vue src/stores/ui.ts src/views/NotificationsView.spec.ts
git commit -m "feat: add notifications workflow"
```

### Task 10: Implement post detail and comment sections

**Files:**
- Create: `src/services/api/contentApi.ts`
- Create: `src/types/content.ts`
- Create: `src/components/post/PostDetailCard.vue`
- Create: `src/components/comment/CommentList.vue`
- Create: `src/views/PostDetailView.vue`
- Test: `src/views/PostDetailView.spec.ts`

- [ ] **Step 1: Write the failing post detail test**

```ts
import { mount } from '@vue/test-utils'
import PostDetailView from '@/views/PostDetailView.vue'

test('renders the selected post with comments section', () => {
  const wrapper = mount(PostDetailView, {
    props: {
      post: { title: 'Detail', summary: 'Expanded story' },
      comments: [{ id: 'c1', authorName: 'Bob', body: 'First comment' }]
    }
  })

  expect(wrapper.text()).toContain('Expanded story')
  expect(wrapper.text()).toContain('First comment')
})
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `npm test -- src/views/PostDetailView.spec.ts`

Expected: FAIL because `PostDetailView.vue` does not exist.

- [ ] **Step 3: Implement content detail and comment fetch helpers**

Use:

- `GET /api/v1/content/{postId}`
- `GET /api/v1/comment/hot`
- `GET /api/v1/comment/list`
- `GET /api/v1/comment/reply/list` if needed for expansion support

- [ ] **Step 4: Build post detail UI**

Requirements:

- full detail card reusing feed visual language
- comments section below
- persistent reaction/comment entry area
- localized loading and error states

- [ ] **Step 5: Run the post detail test to verify it passes**

Run: `npm test -- src/views/PostDetailView.spec.ts`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/services/api/contentApi.ts src/types/content.ts src/components/post/PostDetailCard.vue src/components/comment/CommentList.vue src/views/PostDetailView.vue src/views/PostDetailView.spec.ts
git commit -m "feat: add post detail and comments"
```

## Chunk 6: Integration Hardening

### Task 11: Finish auth refresh, optimistic interactions, and production verification

**Files:**
- Modify: `src/services/http/client.ts`
- Modify: `src/services/http/session.ts`
- Modify: `src/services/api/feedApi.ts`
- Modify: `src/components/feed/FeedCard.vue`
- Modify: `src/views/TimelineView.vue`
- Modify: `src/views/ProfileView.vue`
- Test: `src/services/http/client.spec.ts`
- Test: `src/views/TimelineView.spec.ts`

- [ ] **Step 1: Write the failing refresh replay test**

```ts
import { describe, expect, it } from 'vitest'
import { createAuthenticatedClient } from '@/services/http/client'

describe('http refresh handling', () => {
  it('retries the original request after refresh success', async () => {
    const client = createAuthenticatedClient()
    const result = await client.__testOnlyRetryAfterRefresh()
    expect(result).toBe('retried')
  })
})
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `npm test -- src/services/http/client.spec.ts`

Expected: FAIL because refresh replay logic is not implemented.

- [ ] **Step 3: Implement centralized refresh locking and request replay**

Requirements:

- single in-flight refresh
- queued requests await refresh completion
- local logout on refresh failure

- [ ] **Step 4: Add optimistic reaction behavior**

Requirements:

- timeline and profile cards update like state immediately
- rollback if `/api/v1/interact/reaction` fails
- no page-level reload for single-card interactions

- [ ] **Step 5: Run focused tests, then full test suite, then build**

Run:

```bash
npm test -- src/services/http/client.spec.ts src/views/TimelineView.spec.ts
npm test
npm run build
```

Expected:

- targeted tests PASS
- full test suite PASS
- build PASS

- [ ] **Step 6: Commit**

```bash
git add src/services/http/client.ts src/services/http/session.ts src/services/api/feedApi.ts src/components/feed/FeedCard.vue src/views/TimelineView.vue src/views/ProfileView.vue src/services/http/client.spec.ts src/views/TimelineView.spec.ts
git commit -m "feat: harden auth refresh and feed interactions"
```

## Verification Checklist

- [ ] Unauthenticated access redirects to `/login`
- [ ] Successful login persists tokens and loads `/`
- [ ] Timeline renders with mapped feed cards
- [ ] Search query stays URL-synced
- [ ] Profile pages reuse feed card UI and relationship actions
- [ ] Notifications render unread states and read actions
- [ ] Post detail loads comments without collapsing the whole page on partial failure
- [ ] App respects semantic tokens and editorial spacing rules
- [ ] No route causes horizontal scrolling at 375px width
- [ ] `npm test` and `npm run build` both pass

## Handoff Notes

- Keep the first implementation within the approved scope only.
- Do not start publish studio or admin pages.
- Re-check Java DTOs if frontend API docs are unclear because the generated markdown has encoding issues in parts.
- Use TDD rigorously: failing test first, then minimal implementation, then refactor.
- Prefer small commits after each task as listed above.
