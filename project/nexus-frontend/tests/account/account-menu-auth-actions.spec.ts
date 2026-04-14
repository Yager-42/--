import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, test, vi } from 'vitest'
import PrototypeShell from '@/components/prototype/PrototypeShell.vue'
import { useAuthStore } from '@/store/auth'

const { fetchAuthMe, logout, changePassword } = vi.hoisted(() => ({
  fetchAuthMe: vi.fn(),
  logout: vi.fn(),
  changePassword: vi.fn()
}))

vi.mock('@/api/auth', () => ({
  fetchAuthMe,
  logout,
  changePassword
}))

describe('account menu auth actions', () => {
  beforeEach(() => {
    fetchAuthMe.mockReset()
    logout.mockReset()
    changePassword.mockReset()
    document.body.innerHTML = ''
    localStorage.clear()
  })

  test('password change submits the backend payload and clears auth state', async () => {
    fetchAuthMe.mockResolvedValue({
      userId: 77,
      phone: '18800000001',
      status: 'ACTIVE',
      nickname: 'Mina Vale',
      avatarUrl: ''
    })
    logout.mockResolvedValue(undefined)
    changePassword.mockResolvedValue(undefined)

    const pinia = createPinia()
    const authStore = useAuthStore(pinia)
    authStore.setToken('session-token', '77', 'refresh-token')

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
    await wrapper.get('[data-account-menu-password]').trigger('click')
    await flushPromises()

    const oldPasswordInput = document.body.querySelector('input[name="oldPassword"]') as HTMLInputElement | null
    const newPasswordInput = document.body.querySelector('input[name="newPassword"]') as HTMLInputElement | null
    const submitButton = document.body.querySelector('[data-password-change-submit]') as HTMLButtonElement | null

    expect(oldPasswordInput).not.toBeNull()
    expect(newPasswordInput).not.toBeNull()
    expect(submitButton).not.toBeNull()

    oldPasswordInput!.value = 'old-secret'
    oldPasswordInput!.dispatchEvent(new Event('input'))
    newPasswordInput!.value = 'new-secret-123'
    newPasswordInput!.dispatchEvent(new Event('input'))
    await flushPromises()

    submitButton?.click()
    await flushPromises()

    expect(changePassword).toHaveBeenCalledWith({
      oldPassword: 'old-secret',
      newPassword: 'new-secret-123'
    })
    expect(authStore.token).toBeNull()
    expect(authStore.refreshToken).toBeNull()
    expect(authStore.userId).toBeNull()
    expect(router.currentRoute.value.fullPath).toBe('/login?forceAuth=1')

    wrapper.unmount()
  })
})
