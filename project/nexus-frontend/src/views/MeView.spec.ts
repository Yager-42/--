import { flushPromises, mount, RouterLinkStub } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, expect, test, vi } from 'vitest'

const push = vi.fn()
const authApiMocks = vi.hoisted(() => ({
  logout: vi.fn().mockResolvedValue(undefined),
  changePassword: vi.fn().mockResolvedValue(undefined)
}))
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
  }),
  updateMyProfile: vi.fn().mockResolvedValue(undefined),
  updateMyPrivacy: vi.fn().mockResolvedValue(undefined)
}))
const feedApiMocks = vi.hoisted(() => ({
  fetchProfileTimeline: vi.fn().mockResolvedValue({
    items: [
      {
        id: '101',
        authorId: '7',
        authorName: 'Nexus Mock',
        summary: 'My published story',
        likeCountLabel: '12'
      }
    ],
    nextCursor: ''
  })
}))
const contentApiMocks = vi.hoisted(() => ({
  deletePost: vi.fn().mockResolvedValue({
    success: true
  })
}))
const relationApiMocks = vi.hoisted(() => ({
  fetchFollowers: vi.fn().mockResolvedValue({
    items: [
      {
        id: '21',
        nickname: 'Follower A',
        followTimeLabel: '2026/4/21'
      }
    ],
    nextCursor: ''
  }),
  fetchFollowing: vi.fn().mockResolvedValue({
    items: [
      {
        id: '22',
        nickname: 'Following B',
        followTimeLabel: '2026/4/20'
      }
    ],
    nextCursor: ''
  })
}))

vi.mock('vue-router', () => ({
  useRouter: () => ({
    push
  })
}))

vi.mock('@/services/api/profileApi', () => ({
  fetchMyProfileViewModel: profileApiMocks.fetchMyProfileViewModel,
  updateMyProfile: profileApiMocks.updateMyProfile,
  updateMyPrivacy: profileApiMocks.updateMyPrivacy
}))

vi.mock('@/services/api/feedApi', () => ({
  fetchProfileTimeline: feedApiMocks.fetchProfileTimeline
}))

vi.mock('@/services/api/authApi', () => ({
  logout: authApiMocks.logout,
  changePassword: authApiMocks.changePassword
}))

vi.mock('@/services/api/contentApi', () => ({
  deletePost: contentApiMocks.deletePost
}))

vi.mock('@/services/api/relationApi', () => ({
  fetchFollowers: relationApiMocks.fetchFollowers,
  fetchFollowing: relationApiMocks.fetchFollowing
}))

import { useAuthStore } from '@/stores/auth'
import MeView from '@/views/MeView.vue'

beforeEach(() => {
  setActivePinia(createPinia())
  localStorage.clear()
  push.mockReset()
  authApiMocks.logout.mockClear()
  authApiMocks.changePassword.mockClear()
  profileApiMocks.fetchMyProfileViewModel.mockClear()
  profileApiMocks.updateMyProfile.mockClear()
  profileApiMocks.updateMyPrivacy.mockClear()
  feedApiMocks.fetchProfileTimeline.mockClear()
  contentApiMocks.deletePost.mockClear()
  relationApiMocks.fetchFollowers.mockClear()
  relationApiMocks.fetchFollowing.mockClear()
})

test('submits password change and shows success feedback', async () => {
  const wrapper = mount(MeView, {
    global: {
      plugins: [createPinia()],
      stubs: {
        RouterLink: RouterLinkStub
      }
    }
  })

  await flushPromises()
  await wrapper.get('[data-test=old-password-input]').setValue('old-secret')
  await wrapper.get('[data-test=new-password-input]').setValue('new-secret')
  await wrapper.get('[data-test=password-form]').trigger('submit.prevent')
  await flushPromises()

  expect(authApiMocks.changePassword).toHaveBeenCalledWith({
    oldPassword: 'old-secret',
    newPassword: 'new-secret'
  })
  expect(wrapper.text()).toContain('密码已更新')
})

test('logs out remotely, clears auth state, and redirects to login', async () => {
  const pinia = createPinia()
  setActivePinia(pinia)
  const authStore = useAuthStore()

  await authStore.completeLogin({
    userId: 7,
    tokenName: 'Authorization',
    tokenPrefix: 'Bearer',
    token: 'token-1',
    refreshToken: 'refresh-1'
  })

  const wrapper = mount(MeView, {
    global: {
      plugins: [pinia],
      stubs: {
        RouterLink: RouterLinkStub
      }
    }
  })

  await flushPromises()
  await wrapper.get('[data-test=logout-button]').trigger('click')
  await flushPromises()

  expect(authApiMocks.logout).toHaveBeenCalled()
  expect(localStorage.getItem('nexus.accessToken')).toBeNull()
  expect(push).toHaveBeenCalledWith('/login')
})

test('updates profile and privacy settings', async () => {
  const wrapper = mount(MeView, {
    global: {
      plugins: [createPinia()],
      stubs: {
        RouterLink: RouterLinkStub
      }
    }
  })

  await flushPromises()
  await wrapper.get('[data-test=profile-nickname-input]').setValue('New Alias')
  await wrapper.get('[data-test=profile-avatar-input]').setValue('https://img.example/avatar.jpg')
  await wrapper.get('[data-test=profile-form]').trigger('submit.prevent')
  await flushPromises()

  await wrapper.get('[data-test=privacy-approval-toggle]').setValue(true)
  await wrapper.get('[data-test=privacy-form]').trigger('submit.prevent')
  await flushPromises()

  expect(profileApiMocks.updateMyProfile).toHaveBeenCalledWith({
    nickname: 'New Alias',
    avatarUrl: 'https://img.example/avatar.jpg'
  })
  expect(profileApiMocks.updateMyPrivacy).toHaveBeenCalledWith({
    needApproval: true
  })
})

test('loads my published timeline and focuses the profile form from the header action', async () => {
  const scrollIntoView = vi.fn()
  vi.stubGlobal('HTMLElement', HTMLElement)
  HTMLElement.prototype.scrollIntoView = scrollIntoView

  const wrapper = mount(MeView, {
    attachTo: document.body,
    global: {
      plugins: [createPinia()],
      stubs: {
        RouterLink: RouterLinkStub
      }
    }
  })

  await flushPromises()

  expect(feedApiMocks.fetchProfileTimeline).toHaveBeenCalledWith('7')
  expect(wrapper.text()).toContain('My published story')

  await wrapper.get('[data-test=follow-toggle]').trigger('click')
  await flushPromises()

  expect(scrollIntoView).toHaveBeenCalled()
  expect(document.activeElement).toBe(wrapper.get('[data-test=profile-nickname-input]').element)

  wrapper.unmount()
  vi.unstubAllGlobals()
})

test('shows owner actions on my published cards and routes edit/delete correctly', async () => {
  const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true)

  const wrapper = mount(MeView, {
    global: {
      plugins: [createPinia()],
      stubs: {
        RouterLink: RouterLinkStub
      }
    }
  })

  await flushPromises()

  const detailLink = wrapper
    .findAllComponents(RouterLinkStub)
    .find((link) => link.props('to') === '/post/101')

  expect(detailLink).toBeTruthy()

  await wrapper.get('[data-test="feed-card-menu-101"]').trigger('click')
  await wrapper.get('[data-test="feed-card-edit-101"]').trigger('click')
  expect(push).toHaveBeenCalledWith('/compose/editor?postId=101')

  await wrapper.get('[data-test="feed-card-menu-101"]').trigger('click')
  await wrapper.get('[data-test="feed-card-delete-101"]').trigger('click')
  await flushPromises()

  expect(confirmSpy).toHaveBeenCalled()
  expect(contentApiMocks.deletePost).toHaveBeenCalledWith('101', {
    userId: 7,
    postId: 101
  })
  expect(wrapper.text()).not.toContain('My published story')

  confirmSpy.mockRestore()
})

test('opens followers and following sheets on my profile page', async () => {
  const wrapper = mount(MeView, {
    global: {
      plugins: [createPinia()],
      stubs: {
        RouterLink: RouterLinkStub
      }
    }
  })

  await flushPromises()

  await wrapper.get('[data-test=view-followers]').trigger('click')
  await flushPromises()

  expect(relationApiMocks.fetchFollowers).toHaveBeenCalledWith('7')
  expect(wrapper.text()).toContain('Follower A')

  await wrapper.get('[data-test=close-relations]').trigger('click')
  await wrapper.get('[data-test=view-following]').trigger('click')
  await flushPromises()

  expect(relationApiMocks.fetchFollowing).toHaveBeenCalledWith('7')
  expect(wrapper.text()).toContain('Following B')
})
