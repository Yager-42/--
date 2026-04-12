import fs from 'node:fs'
import path from 'node:path'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, test, vi } from 'vitest'
import PrototypeAuthShell from '@/components/prototype/PrototypeAuthShell.vue'
import PrototypeShell from '@/components/prototype/PrototypeShell.vue'
import { useAuthStore } from '@/store/auth'

const { fetchAuthMe, logout } = vi.hoisted(() => ({
  fetchAuthMe: vi.fn(),
  logout: vi.fn()
}))

vi.mock('@/api/auth', () => ({
  fetchAuthMe,
  logout,
  changePassword: vi.fn()
}))

test('authenticated shell renders a fixed desktop nav container', () => {
  const pinia = createPinia()
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/search', component: { template: '<div />' } },
      { path: '/notifications', component: { template: '<div />' } },
      { path: '/publish', component: { template: '<div />' } },
      { path: '/profile', component: { template: '<div />' } },
      { path: '/login', component: { template: '<div />' } }
    ]
  })
  const wrapper = mount(PrototypeShell, {
    global: {
      plugins: [pinia, router]
    },
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

test('prototype header renders account actions, bootstraps identity, and logs out through auth state reset', async () => {
  localStorage.clear()

  fetchAuthMe.mockResolvedValue({
    userId: 42,
    phone: '18800000000',
    status: 'ACTIVE',
    nickname: 'Ari Stone',
    avatarUrl: ''
  })
  logout.mockResolvedValue(undefined)

  const pinia = createPinia()
  const authStore = useAuthStore(pinia)
  authStore.setToken('session-token', '11', 'refresh-token')

  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/search', component: { template: '<div />' } },
      { path: '/notifications', component: { template: '<div />' } },
      { path: '/publish', component: { template: '<div />' } },
      { path: '/profile', component: { template: '<div />' } },
      { path: '/login', component: { template: '<div />' } }
    ]
  })

  await router.push('/')
  await router.isReady()

  const wrapper = mount(PrototypeShell, {
    attachTo: document.body,
    global: {
      plugins: [pinia, router]
    },
    slots: { default: '<div>content</div>' }
  })

  await flushPromises()

  expect(fetchAuthMe).toHaveBeenCalledTimes(1)
  expect(authStore.userId).toBe('42')

  await wrapper.get('[data-account-menu-trigger]').trigger('click')

  expect(document.body.textContent).toContain('Change password')
  expect(document.body.textContent).toContain('Log out')

  await wrapper.get('[data-account-menu-logout]').trigger('click')
  await flushPromises()

  const confirmButton = document.body.querySelector('[data-confirm-accept]') as HTMLButtonElement | null
  expect(confirmButton).not.toBeNull()

  confirmButton?.click()
  await flushPromises()

  expect(logout).toHaveBeenCalledTimes(1)
  expect(authStore.token).toBeNull()
  expect(authStore.refreshToken).toBeNull()
  expect(authStore.userId).toBeNull()
  expect(localStorage.getItem('nexus_auth_token')).toBeNull()
  expect(localStorage.getItem('nexus_refresh_token')).toBeNull()
  expect(localStorage.getItem('nexus_auth_user_id')).toBeNull()
  expect(router.currentRoute.value.fullPath).toBe('/login?forceAuth=1')

  wrapper.unmount()
})

test('prototype header exposes a profile entry that routes to /profile', async () => {
  localStorage.clear()

  fetchAuthMe.mockResolvedValue({
    userId: 42,
    phone: '18800000000',
    status: 'ACTIVE',
    nickname: 'Ari Stone',
    avatarUrl: ''
  })
  logout.mockResolvedValue(undefined)

  const pinia = createPinia()
  const authStore = useAuthStore(pinia)
  authStore.setToken('session-token', '42', 'refresh-token')

  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/search', component: { template: '<div />' } },
      { path: '/notifications', component: { template: '<div />' } },
      { path: '/publish', component: { template: '<div />' } },
      { path: '/profile', component: { template: '<div>Profile</div>' } },
      { path: '/login', component: { template: '<div />' } }
    ]
  })

  await router.push('/')
  await router.isReady()

  const wrapper = mount(PrototypeShell, {
    attachTo: document.body,
    global: {
      plugins: [pinia, router]
    },
    slots: { default: '<div>content</div>' }
  })

  await flushPromises()
  await wrapper.get('[data-account-menu-trigger]').trigger('click')
  await wrapper.get('[data-account-menu-profile]').trigger('click')
  await flushPromises()

  expect(router.currentRoute.value.fullPath).toBe('/profile')

  wrapper.unmount()
})

test('prototype header clears local auth even when logout request fails', async () => {
  fetchAuthMe.mockResolvedValue({
    userId: 42,
    phone: '18800000000',
    status: 'ACTIVE',
    nickname: 'Ari Stone',
    avatarUrl: ''
  })
  logout.mockRejectedValue(new Error('transient logout failure'))

  const pinia = createPinia()
  const authStore = useAuthStore(pinia)
  authStore.setToken('session-token', '42', 'refresh-token')

  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/search', component: { template: '<div />' } },
      { path: '/notifications', component: { template: '<div />' } },
      { path: '/publish', component: { template: '<div />' } },
      { path: '/profile', component: { template: '<div />' } },
      { path: '/login', component: { template: '<div />' } }
    ]
  })

  await router.push('/')
  await router.isReady()

  const wrapper = mount(PrototypeShell, {
    attachTo: document.body,
    global: {
      plugins: [pinia, router]
    },
    slots: { default: '<div>content</div>' }
  })

  await flushPromises()
  await wrapper.get('[data-account-menu-trigger]').trigger('click')
  await wrapper.get('[data-account-menu-logout]').trigger('click')
  await flushPromises()

  const confirmButton = document.body.querySelector('[data-confirm-accept]') as HTMLButtonElement | null
  confirmButton?.click()
  await flushPromises()

  expect(logout).toHaveBeenCalledTimes(1)
  expect(authStore.token).toBeNull()
  expect(authStore.refreshToken).toBeNull()
  expect(authStore.userId).toBeNull()
  expect(router.currentRoute.value.fullPath).toBe('/login?forceAuth=1')

  wrapper.unmount()
})

const projectRoot = path.resolve(__dirname, '../..')

const supportRoutes = [
  {
    file: 'src/views/SearchResults.vue',
    marker: 'data-prototype-search'
  },
  {
    file: 'src/views/Notifications.vue',
    marker: 'data-prototype-notifications'
  },
  {
    file: 'src/views/RelationList.vue',
    marker: 'data-prototype-relation'
  },
  {
    file: 'src/views/Publish.vue',
    marker: 'data-prototype-publish'
  },
  {
    file: 'src/views/RiskCenter.vue',
    marker: 'data-prototype-risk'
  },
  {
    file: 'src/views/Profile.vue',
    marker: 'data-prototype-profile'
  }
] as const

describe('support routes adopt the prototype desktop shell', () => {
  test.each(supportRoutes)('$file uses PrototypeShell without legacy shell wrappers', ({ file, marker }) => {
    const source = fs.readFileSync(path.join(projectRoot, file), 'utf8')

    expect(source).toContain("import PrototypeContainer from '@/components/prototype/PrototypeContainer.vue'")
    expect(source).toContain("import PrototypeShell from '@/components/prototype/PrototypeShell.vue'")
    expect(source).toContain('<PrototypeShell>')
    expect(source).toContain(marker)

    expect(source).not.toContain('TheNavBar')
    expect(source).not.toContain('TheDock')
    expect(source).not.toContain('page-wrap')
    expect(source).not.toContain('page-main')
  })
})

beforeEach(() => {
  fetchAuthMe.mockReset()
  logout.mockReset()
  document.body.innerHTML = ''
  localStorage.clear()
})
