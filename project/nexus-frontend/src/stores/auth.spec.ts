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
