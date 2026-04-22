import { flushPromises, mount, RouterLinkStub } from '@vue/test-utils'
import { expect, test, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { usePostInteractionsStore } from '@/stores/postInteractions'

const routeState = {
  params: {
    id: '8'
  }
}

vi.mock('vue-router', () => ({
  useRoute: () => routeState
}))

vi.mock('@/services/api/profileApi', () => ({
  fetchProfileHeader: vi.fn().mockResolvedValue({
    id: '8',
    nickname: 'Remote Editor',
    bio: 'Writes about distributed systems',
    followerCountLabel: '128',
    followingCountLabel: '64',
    isFollowing: false
  })
}))

const relationApiMocks = vi.hoisted(() => ({
  followUser: vi.fn().mockResolvedValue({ status: 'FOLLOWED' }),
  unfollowUser: vi.fn().mockResolvedValue({ status: 'UNFOLLOWED' }),
  fetchFollowers: vi.fn().mockResolvedValue({
    items: [
      {
        id: '10',
        nickname: 'Alice',
        followTimeLabel: '2026/4/21'
      }
    ],
    nextCursor: ''
  }),
  fetchFollowing: vi.fn().mockResolvedValue({
    items: [
      {
        id: '11',
        nickname: 'Bob',
        followTimeLabel: '2026/4/20'
      }
    ],
    nextCursor: ''
  })
}))

vi.mock('@/services/api/relationApi', () => ({
  followUser: relationApiMocks.followUser,
  unfollowUser: relationApiMocks.unfollowUser,
  fetchFollowers: relationApiMocks.fetchFollowers,
  fetchFollowing: relationApiMocks.fetchFollowing
}))

vi.mock('@/services/api/feedApi', () => ({
  fetchProfileTimeline: vi.fn().mockResolvedValue({
    items: [
      {
        id: 'post-1',
        authorName: 'Remote Editor',
        summary: 'Profile feed item',
        likeCountLabel: '12'
      }
    ],
    nextCursor: ''
  })
}))

import ProfileView from '@/views/ProfileView.vue'

function createGlobalOptions() {
  const pinia = createPinia()
  setActivePinia(pinia)
  usePostInteractionsStore().clearInteractions()

  return {
    plugins: [pinia],
    stubs: {
      RouterLink: RouterLinkStub
    }
  }
}

test('renders profile identity and stats', () => {
  const wrapper = mount(ProfileView, {
    global: createGlobalOptions(),
    props: {
      profile: {
        id: '8',
        nickname: 'Editor',
        bio: 'Writes about distributed systems',
        followerCountLabel: '128',
        followingCountLabel: '64',
        isFollowing: false
      }
    }
  })

  expect(wrapper.text()).toContain('Editor')
  expect(wrapper.text()).toContain('Writes about distributed systems')
  expect(wrapper.text()).toContain('128')
})

test('loads profile data from api when props are absent', async () => {
  const wrapper = mount(ProfileView, {
    global: createGlobalOptions()
  })

  await flushPromises()

  expect(wrapper.text()).toContain('Remote Editor')
  expect(wrapper.text()).toContain('Writes about distributed systems')
  expect(wrapper.text()).toContain('Profile feed item')
})

test('toggles follow state from the profile header', async () => {
  const wrapper = mount(ProfileView, {
    global: createGlobalOptions(),
    props: {
      profile: {
        id: '8',
        nickname: 'Editor',
        bio: 'Writes about distributed systems',
        followerCountLabel: '128',
        followingCountLabel: '64',
        isFollowing: false
      }
    }
  })

  await wrapper.get('[data-test=follow-toggle]').trigger('click')
  await flushPromises()

  expect(relationApiMocks.followUser).toHaveBeenCalledWith({
    sourceId: 7,
    targetId: 8
  })
})

test('opens followers and following sheets', async () => {
  const wrapper = mount(ProfileView, {
    global: createGlobalOptions(),
    props: {
      profile: {
        id: '8',
        nickname: 'Editor',
        bio: 'Writes about distributed systems',
        followerCountLabel: '128',
        followingCountLabel: '64',
        isFollowing: false
      }
    }
  })

  await wrapper.get('[data-test=view-followers]').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('Alice')

  await wrapper.get('[data-test=close-relations]').trigger('click')
  await wrapper.get('[data-test=view-following]').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('Bob')
})

test('shows profile posts as detail links without owner actions', async () => {
  const wrapper = mount(ProfileView, {
    global: createGlobalOptions(),
    props: {
      profile: {
        id: '8',
        nickname: 'Editor',
        bio: 'Writes about distributed systems',
        followerCountLabel: '128',
        followingCountLabel: '64',
        isFollowing: false
      },
      posts: [
        {
          id: '201',
          authorId: '8',
          authorName: 'Editor',
          summary: 'Profile feed item',
          likeCountLabel: '12'
        }
      ]
    }
  })

  const detailLink = wrapper
    .findAllComponents(RouterLinkStub)
    .find((link) => link.props('to') === '/post/201')

  expect(detailLink).toBeTruthy()
  expect(wrapper.find('[data-test="feed-card-menu-201"]').exists()).toBe(false)
})
