import { flushPromises, mount, RouterLinkStub } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { beforeEach, expect, test, vi } from 'vitest'

const push = vi.fn()

const profileApiMocks = vi.hoisted(() => ({
  fetchMyProfileViewModel: vi.fn().mockResolvedValue({
    id: '7',
    nickname: 'Nexus Mock',
    bio: 'Profile copy',
    avatarUrl: '',
    followerCountLabel: '256',
    followingCountLabel: '32',
    isFollowing: false,
    needApproval: false
  })
}))

const feedApiMocks = vi.hoisted(() => ({
  fetchProfileTimeline: vi.fn().mockResolvedValue({
    items: [
      {
        id: '101',
        authorId: '7',
        authorName: 'Nexus Mock',
        summary: 'Published story',
        likeCountLabel: '24'
      }
    ],
    nextCursor: ''
  })
}))

const composerStoreMocks = vi.hoisted(() => ({
  draft: {
    draftId: 901,
    postId: null,
    title: 'Workspace draft',
    body: 'Draft body',
    mediaIds: [],
    serverVersion: 'v1'
  },
  startNewDraft: vi.fn(),
  hydrateFromPost: vi.fn().mockResolvedValue(undefined)
}))

vi.mock('vue-router', () => ({
  useRouter: () => ({
    push
  })
}))

vi.mock('@/services/api/profileApi', () => ({
  fetchMyProfileViewModel: profileApiMocks.fetchMyProfileViewModel
}))

vi.mock('@/services/api/feedApi', () => ({
  fetchProfileTimeline: feedApiMocks.fetchProfileTimeline
}))

vi.mock('@/stores/composer', () => ({
  useComposerStore: () => composerStoreMocks
}))

import ComposeHubView from '@/views/ComposeHubView.vue'

beforeEach(() => {
  push.mockReset()
  profileApiMocks.fetchMyProfileViewModel.mockClear()
  feedApiMocks.fetchProfileTimeline.mockClear()
  composerStoreMocks.startNewDraft.mockClear()
  composerStoreMocks.hydrateFromPost.mockClear()
})

test('shows local draft and published posts, then opens the editor flow', async () => {
  const wrapper = mount(ComposeHubView, {
    global: {
      plugins: [createPinia()],
      stubs: {
        RouterLink: RouterLinkStub
      }
    }
  })

  await flushPromises()

  expect(wrapper.text()).toContain('Workspace draft')
  expect(wrapper.text()).toContain('Published story')

  await wrapper.get('[data-test=open-published-101]').trigger('click')
  await flushPromises()

  expect(composerStoreMocks.hydrateFromPost).toHaveBeenCalledWith('101')
  expect(push).toHaveBeenCalledWith('/compose/editor?postId=101')
})
