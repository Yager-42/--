import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { defineComponent } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, test, vi } from 'vitest'
import type { FeedCardViewModel } from '@/api/feed'
import type { ProfilePageViewModel } from '@/api/user'
import { useAuthStore } from '@/store/auth'
import Profile from '@/views/Profile.vue'

const { fetchProfileTimeline } = vi.hoisted(() => ({
  fetchProfileTimeline: vi.fn()
}))

const { fetchProfilePage, fetchMyPrivacy, updateMyPrivacy, updateMyProfile } = vi.hoisted(() => ({
  fetchProfilePage: vi.fn(),
  fetchMyPrivacy: vi.fn(),
  updateMyPrivacy: vi.fn(),
  updateMyProfile: vi.fn()
}))

const { blockUser } = vi.hoisted(() => ({
  blockUser: vi.fn()
}))

vi.mock('@/api/feed', () => ({
  fetchProfileTimeline
}))

vi.mock('@/api/user', () => ({
  fetchProfilePage,
  fetchMyPrivacy,
  updateMyPrivacy,
  updateMyProfile
}))

vi.mock('@/api/relation', () => ({
  blockUser
}))

const buildProfile = (overrides: Partial<ProfilePageViewModel> = {}): ProfilePageViewModel => ({
  userId: '1',
  username: 'owner',
  nickname: 'Owner Profile',
  avatar: 'https://example.com/avatar.jpg',
  bio: 'Profile bio',
  status: 'ACTIVE',
  stats: {
    likeCount: 12,
    followCount: 8,
    followerCount: 22
  },
  riskStatus: 'NORMAL',
  relationState: 'NOT_FOLLOWING',
  ...overrides
})

const buildFeedItem = (overrides: Partial<FeedCardViewModel> = {}): FeedCardViewModel => ({
  id: 'post-1',
  postId: 'post-1',
  authorId: '1',
  author: 'Owner Profile',
  authorAvatar: 'https://example.com/avatar.jpg',
  title: 'Backend Archive Card',
  body: 'Archive body',
  image: 'https://example.com/archive.jpg',
  mediaUrls: ['https://example.com/archive.jpg'],
  createTime: 1710000000000,
  reactionCount: 7,
  commentCount: 0,
  isLiked: false,
  ...overrides
})

const createTestRouter = async (path: string) => {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/profile', component: Profile },
      { path: '/user/:userId', component: Profile },
      { path: '/search', component: defineComponent({ template: '<div>Search</div>' }) },
      { path: '/content/:postId', component: defineComponent({ template: '<div>Content</div>' }) },
      { path: '/relation/following/:userId', component: defineComponent({ template: '<div>Following</div>' }) },
      { path: '/settings/risk', component: defineComponent({ template: '<div>Risk</div>' }) }
    ]
  })

  await router.push(path)
  await router.isReady()

  return router
}

const findButtonByText = (wrapper: ReturnType<typeof mount>, label: string) => {
  return wrapper.findAll('button').find((candidate) => candidate.text().includes(label))
}

const mountProfile = async (path: string, authUserId = '1') => {
  const pinia = createPinia()
  const authStore = useAuthStore(pinia)
  authStore.setUserId(authUserId)

  const router = await createTestRouter(path)
  const wrapper = mount(Profile, {
    global: {
      plugins: [pinia, router],
      stubs: {
        PrototypeShell: { template: '<div><slot /></div>' },
        PrototypeContainer: { template: '<div><slot /></div>' },
        FollowButton: { template: '<button>Follow</button>' },
        EditProfilePanel: { template: '<div>Edit Profile Panel</div>' },
        FormMessage: {
          props: ['message'],
          template: '<div>{{ message }}</div>'
        },
        StatePanel: {
          props: ['title', 'body', 'actionLabel'],
          template: '<div><p>{{ title }}</p><p>{{ body }}</p><button v-if=\"actionLabel\">{{ actionLabel }}</button></div>'
        },
        ZenButton: {
          props: ['disabled', 'type'],
          emits: ['click'],
          template: `<button :disabled="disabled" :type="type || 'button'" @click="$emit('click')"><slot /></button>`
        },
        ZenIcon: { template: '<span />' }
      }
    }
  })

  await flushPromises()
  await flushPromises()

  return { wrapper, router }
}

beforeEach(() => {
  vi.clearAllMocks()
  localStorage.clear()

  fetchProfilePage.mockResolvedValue(buildProfile())
  fetchProfileTimeline.mockResolvedValue({
    items: [buildFeedItem()],
    page: {
      nextCursor: null,
      hasMore: false
    }
  })
  fetchMyPrivacy.mockResolvedValue({ needApproval: true })
  updateMyPrivacy.mockResolvedValue({ success: true })
  updateMyProfile.mockResolvedValue({ success: true })
  blockUser.mockResolvedValue({ success: true })
})

describe('profile capability backfill', () => {
  test('owner profile keeps privacy mode pending until privacy loads', async () => {
    fetchMyPrivacy.mockImplementation(() => new Promise(() => {}))

    const { wrapper } = await mountProfile('/profile')

    expect(fetchMyPrivacy).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('正在同步隐私设置')
    expect(wrapper.text()).not.toContain('关注需要我的批准')
    expect(wrapper.text()).not.toContain('任何人都可以直接关注')
  })

  test('owner profile surfaces privacy load failure without guessing current mode', async () => {
    fetchMyPrivacy.mockRejectedValue(new Error('privacy unavailable'))

    const { wrapper } = await mountProfile('/profile')

    expect(wrapper.text()).toContain('隐私设置暂时不可用')
    expect(wrapper.text()).not.toContain('关注需要我的批准')
    expect(wrapper.text()).not.toContain('任何人都可以直接关注')
  })

  test('owner profile shows privacy controls', async () => {
    const { wrapper } = await mountProfile('/profile')

    expect(fetchMyPrivacy).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('关注需要我的批准')

    const privacyButton = findButtonByText(wrapper, '关注需要我的批准')
    expect(privacyButton).toBeTruthy()

    await privacyButton?.trigger('click')
    await flushPromises()

    expect(updateMyPrivacy).toHaveBeenCalledWith({ needApproval: false })
  })

  test('visitor profile shows block action', async () => {
    fetchProfilePage.mockResolvedValue(buildProfile({ userId: '2', nickname: 'Visitor Profile' }))
    fetchProfileTimeline.mockResolvedValue({
      items: [buildFeedItem({ authorId: '2', author: 'Visitor Profile' })],
      page: {
        nextCursor: null,
        hasMore: false
      }
    })

    const { wrapper, router } = await mountProfile('/user/2')

    expect(wrapper.text()).toContain('屏蔽此用户')

    const blockButton = findButtonByText(wrapper, '屏蔽此用户')
    expect(blockButton).toBeTruthy()

    await blockButton?.trigger('click')
    await flushPromises()

    expect(blockUser).toHaveBeenCalledWith(expect.objectContaining({ targetId: '2' }))
    expect(router.currentRoute.value.fullPath).toBe('/search')
  })

  test('visitor block action is gated when viewer identity is missing', async () => {
    fetchProfilePage.mockResolvedValue(buildProfile({ userId: '2', nickname: 'Visitor Profile' }))
    fetchProfileTimeline.mockResolvedValue({
      items: [buildFeedItem({ authorId: '2', author: 'Visitor Profile' })],
      page: {
        nextCursor: null,
        hasMore: false
      }
    })

    const { wrapper, router } = await mountProfile('/user/2', null)

    const blockButton = findButtonByText(wrapper, '屏蔽此用户')
    expect(blockButton).toBeTruthy()

    await blockButton?.trigger('click')
    await flushPromises()

    expect(blockUser).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('缺少当前登录用户，无法执行屏蔽')
    expect(router.currentRoute.value.fullPath).toBe('/user/2')
  })

  test('profile renders backend-backed posts instead of synthetic moments', async () => {
    fetchProfilePage.mockResolvedValue(buildProfile({ userId: '2', nickname: 'Visitor Profile' }))
    fetchProfileTimeline.mockResolvedValue({
      items: [
        buildFeedItem({
          postId: 'post-backfill',
          authorId: '2',
          author: 'Visitor Profile',
          title: 'Backend Archive Card'
        })
      ],
      page: {
        nextCursor: null,
        hasMore: false
      }
    })

    const { wrapper } = await mountProfile('/user/2')

    expect(fetchProfileTimeline).toHaveBeenCalledWith(expect.objectContaining({ targetId: '2' }))
    expect(wrapper.text()).toContain('Backend Archive Card')
    expect(wrapper.text()).not.toContain('Whispering Pines')
    expect(wrapper.text()).not.toContain('An asymmetric gallery for identity and archive.')
    expect(wrapper.text()).not.toContain('All moments')
  })

  test('archive empty state when author feed is empty', async () => {
    fetchProfilePage.mockResolvedValue(buildProfile({ userId: '2', nickname: 'Visitor Profile' }))
    fetchProfileTimeline.mockResolvedValue({
      items: [],
      page: {
        nextCursor: null,
        hasMore: false
      }
    })

    const { wrapper } = await mountProfile('/user/2')

    expect(wrapper.text()).toContain('这个档案还没有公开内容')
    expect(wrapper.text()).toContain('作者的 archive 目前为空')
  })

  test('load-more failures keep existing posts and surface feedback', async () => {
    fetchProfilePage.mockResolvedValue(buildProfile({ userId: '2', nickname: 'Visitor Profile' }))
    fetchProfileTimeline
      .mockResolvedValueOnce({
        items: [
          buildFeedItem({
            postId: 'post-backfill',
            authorId: '2',
            author: 'Visitor Profile',
            title: 'Backend Archive Card'
          })
        ],
        page: {
          nextCursor: 'cursor-2',
          hasMore: true
        }
      })
      .mockRejectedValueOnce(new Error('append failed'))

    const { wrapper } = await mountProfile('/user/2')

    const loadMoreButton = findButtonByText(wrapper, '加载更多帖子')
    expect(loadMoreButton).toBeTruthy()

    await loadMoreButton?.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('Backend Archive Card')
    expect(wrapper.text()).toContain('加载更多失败，请稍后重试')
  })
})
