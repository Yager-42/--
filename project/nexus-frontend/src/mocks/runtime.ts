import type { useAuthStore } from '@/store/auth'
import { UI_MOCK_DEFAULT_SESSION } from '@/mocks/http'

type AuthStore = ReturnType<typeof useAuthStore>

declare global {
  interface Window {
    __NEXUS_UI_MOCK_FETCH_INSTALLED__?: boolean
  }
}

export const isUIMockModeEnabled = (): boolean => import.meta.env.MODE === 'mock'

export const installUIMockRuntime = (authStore: AuthStore): void => {
  if (!isUIMockModeEnabled()) {
    return
  }

  if (!authStore.token) {
    authStore.setToken(
      UI_MOCK_DEFAULT_SESSION.token,
      UI_MOCK_DEFAULT_SESSION.userId,
      UI_MOCK_DEFAULT_SESSION.refreshToken
    )
  }

  if (typeof window === 'undefined' || window.__NEXUS_UI_MOCK_FETCH_INSTALLED__) {
    return
  }

  const nativeFetch = window.fetch.bind(window)
  window.fetch = async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const url =
      typeof input === 'string'
        ? input
        : input instanceof URL
          ? input.toString()
          : input.url

    if (url.startsWith('mock://upload/')) {
      return new Response(null, { status: 200, statusText: 'OK' })
    }

    return nativeFetch(input, init)
  }

  window.__NEXUS_UI_MOCK_FETCH_INSTALLED__ = true
}
