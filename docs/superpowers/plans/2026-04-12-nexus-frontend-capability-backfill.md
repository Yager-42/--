# Nexus Frontend Capability Backfill Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Backfill the desktop Nexus frontend so it exposes the approved, real backend capabilities without adding new top-level routes or reintroducing SMS login.

**Architecture:** Keep the current route structure and prototype-aligned layout, then add missing capabilities through page-local enhancements. Shared work should land in API wrappers, mock handlers, and reusable overlay components so profile, content detail, publish, search, notifications, and account actions can all consume the same foundation.

**Tech Stack:** Vue 3, Pinia, Vue Router, Vite, Vitest, Tailwind, local UI mock runtime.

---

### Task 1: Extend API Clients and UI Mock Coverage

**Files:**
- Modify: `project/nexus-frontend/src/api/auth.ts`
- Modify: `project/nexus-frontend/src/api/content.ts`
- Modify: `project/nexus-frontend/src/api/feed.ts`
- Modify: `project/nexus-frontend/src/api/interact.ts`
- Modify: `project/nexus-frontend/src/api/notification.ts`
- Modify: `project/nexus-frontend/src/api/relation.ts`
- Modify: `project/nexus-frontend/src/api/user.ts`
- Modify: `project/nexus-frontend/src/mocks/http.ts`
- Test: `project/nexus-frontend/tests/mocks/ui-mock.spec.ts`
- Create: `project/nexus-frontend/tests/api/capability-backfill-api.spec.ts`

- [ ] **Step 1: Write the failing API and mock tests**

```ts
it('maps author feed, privacy, schedule, and history responses', async () => {
  const timeline = await fetchProfileTimeline('2')
  const privacy = await fetchMyPrivacy()
  const history = await fetchContentHistory('post-quiet-light')

  expect(timeline.items.length).toBeGreaterThan(0)
  expect(typeof privacy.needApproval).toBe('boolean')
  expect(history.versions[0]).toHaveProperty('versionId')
})

it('supports mock handlers for block, hot comments, pin, delete, schedule, rollback, auth me, and logout', async () => {
  expect(await blockUser({ targetId: '4' })).toMatchObject({ success: true })
  expect(await fetchHotComments({ postId: 'post-quiet-light' })).toHaveProperty('items')
  expect(await fetchAuthMe()).toHaveProperty('userId')
})

it('preserves publish identity continuity between draft save and publish in mock mode', async () => {
  const draft = await saveDraft({ title: 'Draft', contentText: 'Body', mediaIds: [] })
  const publish = await publishContent({ postId: draft.draftId, title: 'Draft', text: 'Body', mediaInfo: '', visibility: 'PUBLIC' })

  expect(publish.postId).toBe(draft.draftId)
})
```

- [ ] **Step 2: Run the targeted tests to confirm the gaps**

Run:

```bash
cd project/nexus-frontend
npm test -- tests/mocks/ui-mock.spec.ts tests/api/capability-backfill-api.spec.ts
```

Expected: FAIL because the new endpoint wrappers, response mappers, and mock handlers do not exist yet.

- [ ] **Step 3: Add the missing wrappers and normalizers**

Implement these additions in the API layer:

```ts
// src/api/feed.ts
export const fetchProfileTimeline = async (
  targetUserId: string,
  params?: ProfileFeedRequestDTO
): Promise<CursorPageResult<FeedCardViewModel>> =>
  mapTimelineResponse(
    await http.get<FeedTimelineResponseDTO>(`/feed/profile/${targetUserId}`, { params })
  )

// src/api/user.ts
export const fetchMyPrivacy = () => http.get<UserPrivacyResponseDTO>('/user/me/privacy')
export const updateMyPrivacy = (data: UserPrivacyUpdateRequestDTO) =>
  http.post<OperationResultDTO>('/user/me/privacy', data)

// src/api/relation.ts
export const blockUser = (data: { targetId: string }) =>
  http.post<BlockOperationResultDTO>('/relation/block', data)

// src/api/interact.ts
export const fetchHotComments = (params: FetchCommentsRequestDTO) =>
  http.get<RawCommentListResponseDTO>('/comment/hot', { params })
export const deleteComment = (commentId: string) =>
  http.delete<OperationResultDTO>(`/comment/${commentId}`)
export const pinComment = (data: PinCommentRequestDTO) =>
  http.post<OperationResultDTO>('/interact/comment/pin', data)
export const fetchReactionState = (params: ReactionStateRequestDTO) =>
  http.get<ReactionStateResponseDTO>('/interact/reaction/state', { params })

// src/api/content.ts
export const deleteContent = (postId: string) =>
  http.delete<OperationResultDTO>(`/content/${postId}`)
export const syncDraft = (draftId: string, data: DraftSyncRequestDTO) =>
  http.patch<DraftSyncResponseDTO>(`/content/draft/${draftId}`, data)
export const fetchPublishAttempt = (attemptId: string, userId: string) =>
  http.get<PublishAttemptResponseDTO>(`/content/publish/attempt/${attemptId}`, { params: { userId } })
export const scheduleContent = (data: ScheduleContentRequestDTO) =>
  http.post<ScheduleContentResponseDTO>('/content/schedule', data)
export const updateSchedule = (data: ScheduleUpdateRequestDTO) =>
  http.patch<OperationResultDTO>('/content/schedule', data)
export const cancelSchedule = (data: ScheduleCancelRequestDTO) =>
  http.post<OperationResultDTO>('/content/schedule/cancel', data)
export const fetchScheduleAudit = (taskId: string, userId: string) =>
  http.get<ScheduleAuditResponseDTO>(`/content/schedule/${taskId}`, { params: { userId } })
export const fetchContentHistory = (postId: string) =>
  http.get<ContentHistoryResponseDTO>(`/content/${postId}/history`)
export const rollbackContent = (postId: string, data: ContentRollbackRequestDTO) =>
  http.post<OperationResultDTO>(`/content/${postId}/rollback`, data)

// src/api/auth.ts
export const fetchAuthMe = () => http.get<AuthMeResponseDTO>('/auth/me')
export const logout = () => http.post<void>('/auth/logout')
export const changePassword = (data: ChangePasswordRequestDTO) =>
  http.post<void>('/auth/password/change', data)
```

Contract rules for all follow-up tasks:

- `fetchProfileTimeline()` must return the same normalized shape as `fetchTimeline()`: `{ items, page: { nextCursor, hasMore } }`
- `updateSchedule()` returns only `OperationResultDTO`; when the UI needs the latest task data after an update, it must immediately call `fetchScheduleAudit(taskId, userId)`
- destructive content-detail actions must wait for `ZenConfirmDialog` confirmation before calling delete endpoints

- [ ] **Step 4: Extend the UI mock backend**

Add mock state and handlers for:

```ts
// src/mocks/http.ts
if (path.startsWith('/feed/profile/')) { return authorFeedPayload(...) }
if (path === '/user/me/privacy' && method === 'get') { return { needApproval } }
if (path === '/user/me/privacy' && method === 'post') { needApproval = nextValue; return ok() }
if (path === '/relation/block') { relationEdges.delete(`${viewerId}->${targetId}`); return ok() }
if (path === '/comment/hot') { return hotCommentPayload(postId) }
if (method === 'delete' && path.startsWith('/comment/')) { removeCommentById(commentId); return ok() }
if (path === '/interact/comment/pin') { setPinnedComment(postId, commentId); return ok() }
if (path === '/interact/reaction/state') { return { state: likedBy.has(viewerId), currentCount: likeCount } }
if (method === 'delete' && path.startsWith('/content/')) { postRecords.delete(postId); return ok() }
if (method === 'patch' && path.startsWith('/content/draft/')) { updateDraftSync(draftId, payload); return syncPayload(...) }
if (path.startsWith('/content/publish/attempt/')) { return publishAttemptPayload(attemptId) }
if (path === '/content/schedule' && method === 'post') { return createSchedulePayload(...) }
if (path === '/content/schedule' && method === 'patch') { return updateSchedulePayload(...) }
if (path === '/content/schedule/cancel') { return cancelSchedulePayload(...) }
if (path.startsWith('/content/schedule/')) { return scheduleAuditPayload(taskId) }
if (path.endsWith('/history')) { return contentHistoryPayload(postId) }
if (path.endsWith('/rollback')) { return rollbackPayload(postId, targetVersionId) }
if (path === '/auth/me') { return currentUserPayload(viewerId) }
if (path === '/auth/logout') { return ok() }
if (path === '/auth/password/change') { return ok() }
```

Mock identity rules:

- the first `PUT /api/v1/content/draft` creates a draft record whose `draftId` is the identity passed back into `publishContent({ postId: draftId, ... })`
- `PATCH /api/v1/content/draft/{draftId}` updates that same record in place
- `POST /api/v1/content/publish` must preserve that provided identity as the returned `postId` and create an `attemptId`
- `/content/{postId}/history`, `/content/{postId}/rollback`, and `/content/schedule*` must all resolve against that shared identity in mock mode

- [ ] **Step 5: Re-run the targeted tests**

Run:

```bash
cd project/nexus-frontend
npm test -- tests/mocks/ui-mock.spec.ts tests/api/capability-backfill-api.spec.ts
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add project/nexus-frontend/src/api/auth.ts \
  project/nexus-frontend/src/api/content.ts \
  project/nexus-frontend/src/api/feed.ts \
  project/nexus-frontend/src/api/interact.ts \
  project/nexus-frontend/src/api/notification.ts \
  project/nexus-frontend/src/api/relation.ts \
  project/nexus-frontend/src/api/user.ts \
  project/nexus-frontend/src/mocks/http.ts \
  project/nexus-frontend/tests/mocks/ui-mock.spec.ts \
  project/nexus-frontend/tests/api/capability-backfill-api.spec.ts
git commit -m "feat: add capability backfill api and mock support"
```

### Task 2: Add Shared Overlay and Header/Search Infrastructure

**Files:**
- Create: `project/nexus-frontend/src/components/system/ZenOverlayPanel.vue`
- Create: `project/nexus-frontend/src/components/system/ZenConfirmDialog.vue`
- Create: `project/nexus-frontend/src/components/account/AccountMenu.vue`
- Create: `project/nexus-frontend/src/components/account/PasswordChangePanel.vue`
- Modify: `project/nexus-frontend/src/components/SearchInput.vue`
- Modify: `project/nexus-frontend/src/components/prototype/PrototypeNav.vue`
- Modify: `project/nexus-frontend/src/store/auth.ts`
- Modify: `project/nexus-frontend/src/views/Home.vue`
- Modify: `project/nexus-frontend/src/views/SearchResults.vue`
- Test: `project/nexus-frontend/tests/layout/prototype-shell.spec.ts`
- Test: `project/nexus-frontend/tests/layout/prototype-account-pages.spec.ts`
- Create: `project/nexus-frontend/tests/account/account-menu-auth-actions.spec.ts`

- [ ] **Step 1: Write the failing shell and search tests**

```ts
it('renders the account menu actions from the prototype header', async () => {
  const wrapper = mount(PrototypeNav, { global: testGlobals })
  expect(wrapper.get('button[aria-label=\"Profile\"]').exists()).toBe(true)
  await wrapper.get('button[aria-label=\"Profile\"]').trigger('click')
  expect(wrapper.text()).toContain('Change password')
  expect(wrapper.text()).toContain('Log out')
})

it('bootstraps account identity from auth me and logs out through the backend', async () => {
  const wrapper = mount(PrototypeNav, { global: testGlobals })
  expect(fetchAuthMe).toHaveBeenCalled()
  await wrapper.get('[data-account-action=\"logout\"]').trigger('click')
  expect(logout).toHaveBeenCalled()
  expect(authStore.clearAuth).toHaveBeenCalled()
})

it('submits password change through the account panel', async () => {
  const wrapper = mount(PrototypeNav, { global: testGlobals })
  await wrapper.get('[data-account-action=\"password\"]').trigger('click')
  await wrapper.get('input[name=\"oldPassword\"]').setValue('old-pass-123')
  await wrapper.get('input[name=\"newPassword\"]').setValue('new-pass-456')
  await wrapper.get('button[data-password-submit=\"true\"]').trigger('click')
  expect(changePassword).toHaveBeenCalledWith({
    oldPassword: 'old-pass-123',
    newPassword: 'new-pass-456'
  })
})

it('reuses SearchInput on home and search pages with suggestion support', async () => {
  const wrapper = mount(Home, { global: testGlobals })
  expect(wrapper.find('[aria-label=\"搜索内容\"]').exists()).toBe(true)
})
```

- [ ] **Step 2: Run the shell-focused tests**

Run:

```bash
cd project/nexus-frontend
npm test -- \
  tests/layout/prototype-shell.spec.ts \
  tests/layout/prototype-account-pages.spec.ts \
  tests/account/account-menu-auth-actions.spec.ts
```

Expected: FAIL because the header still uses plain links and the pages still use raw search fields.

- [ ] **Step 3: Build the shared overlay primitives**

Implement reusable shells:

```vue
<!-- ZenOverlayPanel.vue -->
<template>
  <Teleport to="body">
    <div v-if="open" class="fixed inset-0 z-[120] flex justify-end bg-[rgba(27,31,31,0.28)]">
      <aside class="h-full w-[32rem] max-w-[92vw] bg-prototype-surface shadow-[0_24px_64px_rgba(27,31,31,0.18)]">
        <slot />
      </aside>
    </div>
  </Teleport>
</template>
```

```vue
<!-- ZenConfirmDialog.vue -->
<template>
  <Teleport to="body">
    <div v-if="open" class="fixed inset-0 z-[130] grid place-items-center bg-[rgba(27,31,31,0.34)]">
      <section class="w-[28rem] rounded-[2rem] border border-prototype-line bg-prototype-surface p-6">
        <slot />
      </section>
    </div>
  </Teleport>
</template>
```

- [ ] **Step 4: Upgrade `SearchInput` and the global header**

Implement:

```ts
// SearchInput.vue
defineProps<{ modelValue?: string; isExpanded: boolean; placeholder?: string }>()
defineEmits<{ 'update:modelValue': [value: string]; 'search': [keyword: string] }>()
```

And in the header:

```vue
<AccountMenu
  :user-id="authStore.userId"
  @logout="handleLogout"
  @open-password-panel="passwordPanelOpen = true"
/>
```

And wire the account actions:

```ts
const account = ref<AuthMeResponseDTO | null>(null)

onMounted(async () => {
  account.value = await fetchAuthMe()
  authStore.setUserId(String(account.value.userId))
})

const handleLogout = async () => {
  await logout()
  authStore.clearAuth()
  await router.push('/login?forceAuth=1')
}

const submitPasswordChange = async (payload: ChangePasswordRequestDTO) => {
  await changePassword(payload)
  authStore.clearAuth()
  await router.push('/login?forceAuth=1')
}
```

Then replace the raw search controls in `Home.vue` and `SearchResults.vue` with the shared `SearchInput`.

- [ ] **Step 5: Re-run the shell tests**

Run:

```bash
cd project/nexus-frontend
npm test -- \
  tests/layout/prototype-shell.spec.ts \
  tests/layout/prototype-account-pages.spec.ts \
  tests/account/account-menu-auth-actions.spec.ts
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add project/nexus-frontend/src/components/system/ZenOverlayPanel.vue \
  project/nexus-frontend/src/components/system/ZenConfirmDialog.vue \
  project/nexus-frontend/src/components/account/AccountMenu.vue \
  project/nexus-frontend/src/components/account/PasswordChangePanel.vue \
  project/nexus-frontend/src/components/SearchInput.vue \
  project/nexus-frontend/src/components/prototype/PrototypeNav.vue \
  project/nexus-frontend/src/store/auth.ts \
  project/nexus-frontend/src/views/Home.vue \
  project/nexus-frontend/src/views/SearchResults.vue \
  project/nexus-frontend/tests/layout/prototype-shell.spec.ts \
  project/nexus-frontend/tests/layout/prototype-account-pages.spec.ts \
  project/nexus-frontend/tests/account/account-menu-auth-actions.spec.ts
git commit -m "feat: add shared account and search capability shell"
```

### Task 3: Backfill the Profile Surface

**Files:**
- Create: `project/nexus-frontend/src/components/profile/ProfilePrivacyPanel.vue`
- Create: `project/nexus-frontend/src/components/profile/ProfileActionMenu.vue`
- Create: `project/nexus-frontend/src/components/profile/ProfileFeedGrid.vue`
- Modify: `project/nexus-frontend/src/views/Profile.vue`
- Test: `project/nexus-frontend/tests/layout/prototype-secondary-pages.spec.ts`
- Create: `project/nexus-frontend/tests/profile/profile-capability-backfill.spec.ts`

- [ ] **Step 1: Write the failing profile tests**

```ts
it('shows privacy controls only on the owner profile', async () => {
  const wrapper = mount(Profile, { global: ownerGlobals })
  expect(wrapper.text()).toContain('Privacy')
})

it('shows block action only on a visitor profile', async () => {
  const wrapper = mount(Profile, { global: visitorGlobals })
  expect(wrapper.find('[data-profile-action=\"block\"]').exists()).toBe(true)
})

it('renders backend-backed profile posts instead of synthetic moments', async () => {
  const wrapper = mount(Profile, { global: visitorGlobals })
  expect(wrapper.text()).toContain('The Architecture of Quiet Light')
})

it('shows an archive empty state when the profile feed is empty', async () => {
  const wrapper = mount(Profile, { global: emptyFeedGlobals })
  expect(wrapper.text()).toContain('No published work yet')
})
```

- [ ] **Step 2: Run the targeted profile tests**

Run:

```bash
cd project/nexus-frontend
npm test -- tests/layout/prototype-secondary-pages.spec.ts tests/profile/profile-capability-backfill.spec.ts
```

Expected: FAIL because the page still renders `profileMoments` and has no privacy/block controls.

- [ ] **Step 3: Replace the synthetic gallery with the real author feed**

Implement:

```ts
const profileFeed = ref<FeedCardViewModel[]>([])
const profileFeedPage = ref({ nextCursor: null, hasMore: false })

const loadProfileFeed = async () => {
  profileFeed.value = []
  const res = await fetchProfileTimeline(targetUserId)
  profileFeed.value = res.items
  profileFeedPage.value = res.page
}
```

Then render the feed through `ProfileFeedGrid.vue` using the same uniform card rhythm as the home page.

- [ ] **Step 4: Add owner privacy and visitor block actions**

Implement:

```ts
const privacy = ref({ needApproval: false })
const updatePrivacyState = async (nextValue: boolean) => {
  await updateMyPrivacy({ needApproval: nextValue })
  privacy.value.needApproval = nextValue
}

const onBlockUser = async () => {
  await blockUser({ targetId: routeUserId.value! })
  await router.push('/search')
}
```

- [ ] **Step 5: Re-run the profile tests**

Run:

```bash
cd project/nexus-frontend
npm test -- tests/layout/prototype-secondary-pages.spec.ts tests/profile/profile-capability-backfill.spec.ts
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add project/nexus-frontend/src/components/profile/ProfilePrivacyPanel.vue \
  project/nexus-frontend/src/components/profile/ProfileActionMenu.vue \
  project/nexus-frontend/src/components/profile/ProfileFeedGrid.vue \
  project/nexus-frontend/src/views/Profile.vue \
  project/nexus-frontend/tests/layout/prototype-secondary-pages.spec.ts \
  project/nexus-frontend/tests/profile/profile-capability-backfill.spec.ts
git commit -m "feat: backfill profile feed privacy and block actions"
```

### Task 4: Backfill the Content Detail Surface

**Files:**
- Modify: `project/nexus-frontend/src/views/ContentDetail.vue`
- Create: `project/nexus-frontend/src/components/content/CommentActionMenu.vue`
- Create: `project/nexus-frontend/src/components/content/ContentDetailActionMenu.vue`
- Modify: `project/nexus-frontend/src/components/system/ZenConfirmDialog.vue`
- Create: `project/nexus-frontend/tests/content/content-detail-capabilities.spec.ts`
- Test: `project/nexus-frontend/tests/layout/prototype-secondary-pages.spec.ts`

- [ ] **Step 1: Write the failing content-detail tests**

```ts
it('renders a hot comment block ahead of the main thread', async () => {
  const wrapper = mount(ContentDetail, { global: testGlobals })
  expect(wrapper.text()).toContain('Hot comment')
})

it('shows delete and pin actions only when permitted', async () => {
  const wrapper = mount(ContentDetail, { global: testGlobals })
  expect(wrapper.find('[data-comment-action=\"delete\"]').exists()).toBe(true)
  expect(wrapper.find('[data-comment-action=\"pin\"]').exists()).toBe(true)
})

it('shows a delete-post action for the author', async () => {
  const wrapper = mount(ContentDetail, { global: testGlobals })
  expect(wrapper.find('[data-post-action=\"delete\"]').exists()).toBe(true)
})

it('requires confirmation before destructive requests fire', async () => {
  const wrapper = mount(ContentDetail, { global: testGlobals })
  await wrapper.get('[data-comment-action=\"delete\"]').trigger('click')
  expect(wrapper.text()).toContain('Are you sure')
  expect(deleteComment).not.toHaveBeenCalled()
})
```

- [ ] **Step 2: Run the targeted content-detail tests**

Run:

```bash
cd project/nexus-frontend
npm test -- tests/layout/prototype-secondary-pages.spec.ts tests/content/content-detail-capabilities.spec.ts
```

Expected: FAIL because hot comments, comment actions, and post deletion are not wired.

- [ ] **Step 3: Add hot comment and reaction bootstrap**

Implement:

```ts
const hotComment = ref<RootCommentDisplayItem | null>(null)

const bootstrapDetailState = async () => {
  const [detailRes, hotRes, reactionRes] = await Promise.all([
    fetchContentDetail(postId.value, authStore.userId || undefined),
    fetchHotComments({ postId: postId.value, limit: 1, preloadReplyLimit: 2 }),
    fetchReactionState({ targetId: postId.value, targetType: 'POST', type: 'LIKE' })
  ])
}
```

- [ ] **Step 4: Add destructive and editorial actions**

Implement:

```ts
const pendingDelete = ref<{ type: 'comment' | 'post'; id: string } | null>(null)

const requestCommentDelete = (commentId: string) => {
  pendingDelete.value = { type: 'comment', id: commentId }
}

const pinRootComment = async (commentId: string) => {
  await pinComment({ commentId, postId: postId.value })
  await loadComments(true)
}

const requestPostDelete = () => {
  pendingDelete.value = { type: 'post', id: postId.value }
}

const confirmPendingDelete = async () => {
  if (!pendingDelete.value) return

  if (pendingDelete.value.type === 'comment') {
    await deleteComment(pendingDelete.value.id)
    comments.value = comments.value.filter((item) => item.commentId !== pendingDelete.value?.id)
  }

  if (pendingDelete.value.type === 'post') {
    await deleteContent(pendingDelete.value.id)
    await router.push('/')
  }
}
```

- [ ] **Step 5: Re-run the targeted content-detail tests**

Run:

```bash
cd project/nexus-frontend
npm test -- tests/layout/prototype-secondary-pages.spec.ts tests/content/content-detail-capabilities.spec.ts
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add project/nexus-frontend/src/views/ContentDetail.vue \
  project/nexus-frontend/src/components/content/CommentActionMenu.vue \
  project/nexus-frontend/src/components/content/ContentDetailActionMenu.vue \
  project/nexus-frontend/src/components/system/ZenConfirmDialog.vue \
  project/nexus-frontend/tests/content/content-detail-capabilities.spec.ts \
  project/nexus-frontend/tests/layout/prototype-secondary-pages.spec.ts
git commit -m "feat: backfill content detail moderation actions"
```

### Task 5: Backfill the Publish Workspace

**Files:**
- Create: `project/nexus-frontend/src/composables/usePublishSession.ts`
- Create: `project/nexus-frontend/src/components/publish/PublishAttemptStrip.vue`
- Create: `project/nexus-frontend/src/components/publish/PublishSchedulePanel.vue`
- Create: `project/nexus-frontend/src/components/publish/PublishHistoryDrawer.vue`
- Modify: `project/nexus-frontend/src/views/Publish.vue`
- Modify: `project/nexus-frontend/src/composables/usePublishForm.ts`
- Create: `project/nexus-frontend/tests/publish/publish-capability-backfill.spec.ts`
- Test: `project/nexus-frontend/tests/layout/prototype-secondary-pages.spec.ts`

- [ ] **Step 1: Write the failing publish tests**

```ts
it('shows publish attempt status after publish', async () => {
  const wrapper = mount(Publish, { global: testGlobals })
  expect(wrapper.text()).toContain('Publishing')
})

it('stores draftId first, then postId and attemptId after publish', async () => {
  const wrapper = mount(Publish, { global: testGlobals })
  expect(wrapper.text()).toContain('Draft saved')
  expect(wrapper.text()).toContain('Attempt ID')
})

it('lets the user schedule, reschedule, and cancel publication', async () => {
  const wrapper = mount(Publish, { global: testGlobals })
  expect(wrapper.find('[aria-label=\"Schedule for later\"]').exists()).toBe(true)
})

it('opens version history and restores a prior version', async () => {
  const wrapper = mount(Publish, { global: testGlobals })
  expect(wrapper.text()).toContain('Version history')
  expect(wrapper.get('[data-open-history=\"true\"]').attributes('disabled')).toBeDefined()
})
```

- [ ] **Step 2: Run the publish-focused tests**

Run:

```bash
cd project/nexus-frontend
npm test -- tests/layout/prototype-secondary-pages.spec.ts tests/publish/publish-capability-backfill.spec.ts
```

Expected: FAIL because the publish workspace only supports upload, save draft, and publish.

- [ ] **Step 3: Add a concrete publish-session state model**

Create `usePublishSession.ts` to centralize the identity lifecycle:

```ts
export interface PublishSessionState {
  draftId: string | null
  postId: string | null
  attemptId: string | null
  taskId: string | null
}

export const usePublishSession = () => {
  const session = ref<PublishSessionState>({
    draftId: null,
    postId: null,
    attemptId: null,
    taskId: null
  })

  const storeDraftId = (draftId: string) => {
    session.value.draftId = draftId
  }

  const storePublishResult = (result: PublishContentResponseDTO) => {
    session.value.postId = result.postId
    session.value.attemptId = result.attemptId
  }

  return { session, storeDraftId, storePublishResult }
}
```

Apply these lifecycle rules in `Publish.vue`:

- first successful `saveDraft` creates and stores `draftId`
- publish sends `postId: session.draftId`
- publish success stores `postId` and `attemptId`
- schedule actions store `taskId`
- version history and rollback stay disabled until `session.postId` exists
- rollback reloads editor fields from the selected history snapshot, then refreshes history from the server
- Task 1 mock tests must lock the continuity rule: when publish is called with `postId: draftId`, the returned `postId` must stay equal to that value in UI mock mode

- [ ] **Step 4: Add autosync and publish-attempt polling**

Implement:

```ts
watch(editorState, async () => {
  if (!session.value.draftId) return
  await syncDraft(session.value.draftId, {
    title: form.title,
    diffContent: form.text,
    clientVersion: version.value,
    deviceId: 'web-desktop',
    mediaIds: uploadedMediaIds.value
  })
})

const pollAttempt = async (attemptId: string) => {
  attempt.value = await fetchPublishAttempt(attemptId, authStore.userId!)
}
```

- [ ] **Step 5: Add schedule controls and history drawer**

Implement:

```ts
const saveSchedule = async () => {
  if (!session.value.postId) return

  if (session.value.taskId) {
    await updateSchedule({ taskId: session.value.taskId, publishTime, contentData: form.text, reason: 'user update' })
    scheduledTask.value = await fetchScheduleAudit(session.value.taskId, authStore.userId!)
  } else {
    scheduledTask.value = await scheduleContent({ postId: session.value.postId, publishTime, timezone: 'Asia/Hong_Kong' })
    session.value.taskId = scheduledTask.value.taskId
  }
}

const openHistory = async () => {
  if (!session.value.postId) return
  history.value = await fetchContentHistory(session.value.postId)
  historyOpen.value = true
}

const restoreVersion = async (versionId: string) => {
  if (!session.value.postId) return
  await rollbackContent(session.value.postId, { targetVersionId: versionId })
  const selected = history.value.versions.find((item) => item.versionId === versionId)
  if (selected) {
    form.title = selected.title
    form.text = selected.content
  }
  history.value = await fetchContentHistory(session.value.postId)
}
```

- [ ] **Step 6: Re-run the publish tests**

Run:

```bash
cd project/nexus-frontend
npm test -- tests/layout/prototype-secondary-pages.spec.ts tests/publish/publish-capability-backfill.spec.ts
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add project/nexus-frontend/src/composables/usePublishSession.ts \
  project/nexus-frontend/src/components/publish/PublishAttemptStrip.vue \
  project/nexus-frontend/src/components/publish/PublishSchedulePanel.vue \
  project/nexus-frontend/src/components/publish/PublishHistoryDrawer.vue \
  project/nexus-frontend/src/views/Publish.vue \
  project/nexus-frontend/src/composables/usePublishForm.ts \
  project/nexus-frontend/tests/publish/publish-capability-backfill.spec.ts \
  project/nexus-frontend/tests/layout/prototype-secondary-pages.spec.ts
git commit -m "feat: backfill publish scheduling and history controls"
```

### Task 6: Verify Notification Single-Read and Run Regression

**Files:**
- Modify: `project/nexus-frontend/src/components/NotificationItem.vue`
- Modify: `project/nexus-frontend/src/views/Notifications.vue`
- Create: `project/nexus-frontend/tests/notifications/notification-read-flow.spec.ts`
- Test: `project/nexus-frontend/tests/layout/prototype-secondary-pages.spec.ts`
- Test: `project/nexus-frontend/tests/mocks/ui-mock.spec.ts`

- [ ] **Step 1: Write the failing notification regression tests**

```ts
it('marks a notification as read before routing to its target', async () => {
  const wrapper = mount(NotificationItem, { props: unreadNotification, global: testGlobals })
  await wrapper.trigger('click')
  expect(markAsRead).toHaveBeenCalledWith('notification-1')
})

it('routes follow notifications to the profile surface instead of content detail', async () => {
  const wrapper = mount(NotificationItem, { props: followNotification, global: testGlobals })
  await wrapper.trigger('click')
  expect(router.push).toHaveBeenCalledWith('/user/2')
})

it('preserves mark-all-as-read after single-read wiring', async () => {
  const wrapper = mount(Notifications, { global: testGlobals })
  expect(wrapper.text()).toContain('Mark all as read')
})
```

- [ ] **Step 2: Run the notification and regression tests**

Run:

```bash
cd project/nexus-frontend
npm test -- \
  tests/notifications/notification-read-flow.spec.ts \
  tests/layout/prototype-secondary-pages.spec.ts \
  tests/mocks/ui-mock.spec.ts
```

Expected: if the current single-read wiring is incomplete, FAIL; if it already exists, the mounted test becomes the guardrail before the parent-level optimistic state refactor.

- [ ] **Step 3: Wire single-read state into the item and parent list**

Implement:

```ts
// NotificationItem.vue
const openTarget = async () => {
  if (props.notification.hasUnread) {
    await markAsRead(props.notification.notificationId)
    emit('read', props.notification.notificationId)
  }

  if (props.notification.type === 'FOLLOW' && props.notification.senderId) {
    router.push(`/user/${props.notification.senderId}`)
    return
  }

  if (props.notification.targetId) {
    router.push(`/content/${props.notification.targetId}`)
    return
  }

  router.push('/notifications')
}

// Notifications.vue
const markLocalRead = (notificationId: string) => {
  notifications.value = notifications.value.map((item) =>
    item.notificationId === notificationId ? { ...item, hasUnread: false, isRead: true } : item
  )
}
```

- [ ] **Step 4: Run the full targeted regression suite**

Run:

```bash
cd project/nexus-frontend
npm test -- \
  tests/api/capability-backfill-api.spec.ts \
  tests/mocks/ui-mock.spec.ts \
  tests/layout/prototype-shell.spec.ts \
  tests/layout/prototype-account-pages.spec.ts \
  tests/account/account-menu-auth-actions.spec.ts \
  tests/layout/prototype-secondary-pages.spec.ts \
  tests/notifications/notification-read-flow.spec.ts \
  tests/feed/prototype-feed-alignment.spec.ts \
  tests/profile/profile-capability-backfill.spec.ts \
  tests/content/content-detail-capabilities.spec.ts \
  tests/publish/publish-capability-backfill.spec.ts
npm run build
```

Expected: PASS for all targeted tests and a successful production build.

- [ ] **Step 5: Commit**

```bash
git add project/nexus-frontend/src/components/NotificationItem.vue \
  project/nexus-frontend/src/views/Notifications.vue \
  project/nexus-frontend/tests/notifications/notification-read-flow.spec.ts \
  project/nexus-frontend/tests/layout/prototype-secondary-pages.spec.ts \
  project/nexus-frontend/tests/mocks/ui-mock.spec.ts \
  project/nexus-frontend/tests/api/capability-backfill-api.spec.ts \
  project/nexus-frontend/tests/profile/profile-capability-backfill.spec.ts \
  project/nexus-frontend/tests/content/content-detail-capabilities.spec.ts \
  project/nexus-frontend/tests/publish/publish-capability-backfill.spec.ts
git commit -m "feat: finish notification read flow and regression coverage"
```
