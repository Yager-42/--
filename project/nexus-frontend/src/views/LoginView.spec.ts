import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, expect, test, vi } from 'vitest'

const push = vi.fn()
const authApiMocks = vi.hoisted(() => ({
  loginByPassword: vi.fn().mockResolvedValue({
    userId: 7,
    tokenName: 'Authorization',
    tokenPrefix: 'Bearer',
    token: 'token-1',
    refreshToken: 'refresh-1'
  }),
  registerAccount: vi.fn().mockResolvedValue({
    userId: 9
  }),
  fetchMe: vi.fn().mockResolvedValue({
    userId: 7,
    phone: '13800138000',
    status: 1,
    nickname: 'Nexus User',
    avatarUrl: ''
  })
}))

vi.mock('vue-router', () => ({
  useRoute: () => ({
    query: {
      redirect: '/'
    }
  }),
  useRouter: () => ({
    push
  })
}))

vi.mock('@/services/api/authApi', () => ({
  loginByPassword: authApiMocks.loginByPassword,
  registerAccount: authApiMocks.registerAccount,
  fetchMe: authApiMocks.fetchMe
}))

import LoginView from '@/views/LoginView.vue'

beforeEach(() => {
  setActivePinia(createPinia())
  localStorage.clear()
  push.mockReset()
  authApiMocks.loginByPassword.mockClear()
  authApiMocks.registerAccount.mockClear()
  authApiMocks.fetchMe.mockClear()
})

test('submits password login with phone and password', async () => {
  const wrapper = mount(LoginView)

  await wrapper.get('[data-test=phone-input]').setValue('13800138000')
  await wrapper.get('[data-test=password-input]').setValue('secret123')
  await wrapper.get('form').trigger('submit.prevent')

  expect(wrapper.emitted('login-submitted')).toBeTruthy()
})

test('logs in and redirects to the timeline', async () => {
  const wrapper = mount(LoginView, {
    global: {
      plugins: [createPinia()]
    }
  })

  await wrapper.get('[data-test=phone-input]').setValue('13800138000')
  await wrapper.get('[data-test=password-input]').setValue('secret123')
  await wrapper.get('form').trigger('submit.prevent')
  await flushPromises()

  expect(localStorage.getItem('nexus.accessToken')).toBe('Bearer token-1')
  expect(push).toHaveBeenCalledWith('/')
})

test('does not render a mock login entry point', async () => {
  const wrapper = mount(LoginView, {
    global: {
      plugins: [createPinia()]
    }
  })

  expect(wrapper.find('[data-test=mock-login]').exists()).toBe(false)
})

test('submits register form in register mode', async () => {
  const wrapper = mount(LoginView, {
    global: {
      plugins: [createPinia()]
    }
  })

  await wrapper.get('[data-test=auth-mode-register]').trigger('click')
  await wrapper.get('[data-test=register-nickname-input]').setValue('Nexus New')
  await wrapper.get('[data-test=phone-input]').setValue('13800138000')
  await wrapper.get('[data-test=password-input]').setValue('secret123')
  await wrapper.get('form').trigger('submit.prevent')
  await flushPromises()

  expect(authApiMocks.registerAccount).toHaveBeenCalledWith({
    nickname: 'Nexus New',
    phone: '13800138000',
    password: 'secret123'
  })
  expect(wrapper.text()).toContain('注册成功')
})
