import fs from 'node:fs'
import path from 'node:path'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { defineComponent } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { describe, expect, test, vi } from 'vitest'
import Home from '@/views/Home.vue'
import { useAuthStore } from '@/store/auth'
import { mockRequest } from '@/mocks/http'
import Profile from '@/views/Profile.vue'
import SearchResults from '@/views/SearchResults.vue'

const projectRoot = path.resolve(__dirname, '../..')

const { fetchSearch } = vi.hoisted(() => ({
  fetchSearch: vi.fn()
}))
const { fetchProfilePage, fetchMyPrivacy, fetchProfileTimeline } = vi.hoisted(() => ({
  fetchProfilePage: vi.fn(),
  fetchMyPrivacy: vi.fn(),
  fetchProfileTimeline: vi.fn()
}))
const { fetchTimeline } = vi.hoisted(() => ({
  fetchTimeline: vi.fn()
}))

vi.mock('@/api/search', async () => {
  const actual = await vi.importActual<typeof import('@/api/search')>('@/api/search')
  return {
    ...actual,
    fetchSearch
  }
})

vi.mock('@/api/user', async () => {
  const actual = await vi.importActual<typeof import('@/api/user')>('@/api/user')
  return {
    ...actual,
    fetchProfilePage,
    fetchMyPrivacy
  }
})

vi.mock('@/api/feed', async () => {
  const actual = await vi.importActual<typeof import('@/api/feed')>('@/api/feed')
  return {
    ...actual,
    fetchTimeline,
    fetchProfileTimeline
  }
})

describe('home feed aligns with prototype feed navigation', () => {
  test('supports following, recommended, and trending feed modes', async () => {
    const following = await mockRequest<{ items: Array<{ postId: string }> }>('get', '/feed/timeline', {
      params: { feedType: 'FOLLOWING', limit: 3 }
    })
    const recommended = await mockRequest<{ items: Array<{ postId: string }> }>('get', '/feed/timeline', {
      params: { feedType: 'RECOMMENDED', limit: 3 }
    })
    const trending = await mockRequest<{ items: Array<{ postId: string }> }>('get', '/feed/timeline', {
      params: { feedType: 'TRENDING', limit: 3 }
    })

    expect(following.items.length).toBeGreaterThan(0)
    expect(recommended.items.length).toBeGreaterThan(0)
    expect(trending.items.length).toBeGreaterThan(0)
    expect(following.items.map((item) => item.postId)).not.toEqual(
      recommended.items.map((item) => item.postId)
    )
    expect(trending.items[0]?.postId).not.toBeUndefined()
  })

  test('home view uses feed tabs and avoids detail-page sections', () => {
    const source = fs.readFileSync(path.join(projectRoot, 'src/views/Home.vue'), 'utf8')

    expect(source).toContain('Following')
    expect(source).toContain('Recommended')
    expect(source).toContain('Trending')
    expect(source).not.toContain('PrototypeReadingColumn')
    expect(source).not.toContain('HomeHeroCard')
  })

  test('home card author click routes to the user profile without opening the post', async () => {
    fetchTimeline.mockResolvedValue({
      items: [
        {
          postId: 'post-quiet-light',
          authorId: '2',
          author: 'Mina Vale',
          authorAvatar: 'https://example.com/avatar.jpg',
          title: 'Quiet Light',
          body: 'Body',
          image: 'https://example.com/1.jpg',
          mediaUrls: ['https://example.com/1.jpg'],
          createTime: 1710000000000,
          reactionCount: 12,
          commentCount: 0,
          isLiked: false,
          id: 'post-quiet-light'
        }
      ],
      page: {
        nextCursor: null,
        hasMore: false
      }
    })

    const pinia = createPinia()
    const authStore = useAuthStore(pinia)
    authStore.setToken('session-token', '1', 'refresh-token')

    const homeRouter = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', component: Home },
        { path: '/content/:postId', component: defineComponent({ template: '<div>Content</div>' }) },
        { path: '/user/:userId', component: defineComponent({ template: '<div>User</div>' }) },
        { path: '/search', component: defineComponent({ template: '<div>Search</div>' }) }
      ]
    })
    await homeRouter.push('/')
    await homeRouter.isReady()

    const homeWrapper = mount(Home, {
      global: {
        plugins: [pinia, homeRouter],
        stubs: {
          PrototypeShell: { template: '<div><slot /></div>' },
          PrototypeContainer: { template: '<div><slot /></div>' },
          SearchInput: { template: '<div>Search Input</div>' },
          ZenButton: { template: '<button><slot /></button>' }
        }
      }
    })

    await flushPromises()
    await flushPromises()

    const authorButton = homeWrapper.get('[data-author-link=\"2\"]')
    await authorButton.trigger('click')
    await flushPromises()

    expect(homeRouter.currentRoute.value.fullPath).toBe('/user/2')
  })

  test('search and profile views expose the updated prototype sections', async () => {
    fetchSearch.mockResolvedValue({
      items: [
        {
          id: 'post-1',
          title: 'The Architecture of Quiet Light',
          body: 'An editorial body',
          author: 'Nadia Rose',
          image: 'https://example.com/1.jpg',
          isLiked: false,
          reactionCount: 12,
          commentCount: 0,
          tags: ['Architecture', 'Quiet archive'],
          authorAvatar: 'https://example.com/a.jpg'
        },
        {
          id: 'post-2',
          title: 'Silent Gallery',
          body: 'Another body',
          author: 'Mina Vale',
          image: 'https://example.com/2.jpg',
          isLiked: false,
          reactionCount: 8,
          commentCount: 0,
          tags: ['Collections'],
          authorAvatar: 'https://example.com/b.jpg'
        }
      ],
      page: {
        nextAfter: null,
        hasMore: false
      }
    })
    fetchProfilePage.mockResolvedValue({
      userId: '1',
      nickname: 'Nadia Rose',
      avatar: 'https://example.com/avatar.jpg',
      bio: 'Editorial architect.',
      stats: {
        likeCount: 12,
        followerCount: 8,
        followCount: 6
      },
      relationState: 'SELF',
      riskStatus: 'NORMAL'
    })
    fetchMyPrivacy.mockResolvedValue({ needApproval: false })
    fetchProfileTimeline.mockResolvedValue({
      items: [
        {
          postId: 'post-1',
          authorId: '1',
          authorNickname: 'Nadia Rose',
          authorAvatarUrl: 'https://example.com/avatar.jpg',
          title: 'Quiet Light',
          contentText: 'Body',
          mediaType: 1,
          mediaInfo: JSON.stringify(['https://example.com/1.jpg']),
          coverUrl: 'https://example.com/1.jpg',
          likeCount: 12,
          commentCount: 3,
          isLiked: false,
          createTime: 1710000000000,
          tags: ['Architecture']
        }
      ],
      page: {
        nextCursor: null,
        hasMore: false
      }
    })

    const searchSource = fs.readFileSync(path.join(projectRoot, 'src/views/SearchResults.vue'), 'utf8')

    expect(searchSource).toContain('Featured result')
    expect(searchSource).toContain('Related curators')
    expect(searchSource).toContain('Curated collections')

    const pinia = createPinia()
    const authStore = useAuthStore(pinia)
    authStore.setToken('session-token', '1', 'refresh-token')

    const profileRouter = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/profile', component: Profile },
        { path: '/user/:userId', component: defineComponent({ template: '<div>User</div>' }) },
        { path: '/search', component: defineComponent({ template: '<div>Search</div>' }) },
        { path: '/settings/risk', component: defineComponent({ template: '<div>Risk</div>' }) }
      ]
    })
    await profileRouter.push('/profile')
    await profileRouter.isReady()

    const profileWrapper = mount(Profile, {
      global: {
        plugins: [pinia, profileRouter],
        stubs: {
          PrototypeShell: { template: '<div><slot /></div>' },
          PrototypeContainer: { template: '<div><slot /></div>' },
          FollowButton: { template: '<button>Follow</button>' },
          EditProfilePanel: { template: '<div>Edit Profile</div>' },
          ProfileActionMenu: { template: '<div>Profile Action Menu</div>' },
          ProfileFeedGrid: { template: '<div>Profile moments</div>' },
          ProfilePrivacyPanel: { template: '<div>Privacy</div>' },
          ZenButton: { template: '<button><slot /></button>' },
          FormMessage: { template: '<div><slot /></div>' },
          StatePanel: { template: '<div><slot /></div>' },
          ZenIcon: { template: '<span />' }
        }
      }
    })
    await flushPromises()
    await flushPromises()

    expect(profileWrapper.text()).toContain('A calmer creator profile.')
    expect(profileWrapper.text()).toContain('Profile moments')
    expect(profileWrapper.text()).toContain('Privacy')
  })
})
