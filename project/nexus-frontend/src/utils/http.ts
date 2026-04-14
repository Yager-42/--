import axios from 'axios'
import type {
  AxiosRequestConfig,
  AxiosResponse,
  InternalAxiosRequestConfig,
  AxiosError
} from 'axios'
import type { ApiResponse } from '@/api/types'
import { mockRequest, type MockRequestOptions } from '@/mocks/http'
import { isUIMockModeEnabled } from '@/mocks/runtime'
import { useAuthStore } from '@/store/auth'
import router from '@/router'
import { refreshAccessToken } from '@/api/auth'

const rawBaseURL = import.meta.env.VITE_API_BASE_URL?.trim()
const baseURL = rawBaseURL && rawBaseURL.length > 0
  ? rawBaseURL.replace(/\/+$/, '')
  : '/api/v1'

const client = axios.create({
  baseURL,
  timeout: 12000,
  headers: {
    'Content-Type': 'application/json'
  }
})

type RetryableConfig = InternalAxiosRequestConfig & { _retry?: boolean }
let refreshPromise: Promise<string> | null = null
const uiMockEnabled = isUIMockModeEnabled()

client.interceptors.request.use(
  (config: InternalAxiosRequestConfig) => {
    const authStore = useAuthStore()
    if (authStore.token) {
      config.headers.Authorization = `Bearer ${authStore.token}`
    }
    return config
  },
  (error) => Promise.reject(error)
)

const redirectToLoginIfNeeded = () => {
  const authStore = useAuthStore()
  authStore.clearAuth()
  if (router.currentRoute.value.path !== '/login') {
    void router.push('/login')
  }
}

const getValidAccessToken = async (): Promise<string> => {
  if (refreshPromise) {
    return refreshPromise
  }

  const authStore = useAuthStore()
  const refreshToken = authStore.refreshToken
  if (!refreshToken) {
    throw new Error('missing refresh token')
  }

  refreshPromise = (async () => {
    const refreshed = await refreshAccessToken({ refreshToken })
    authStore.setToken(refreshed.token, refreshed.userId, refreshed.refreshToken)
    return refreshed.token
  })()

  try {
    return await refreshPromise
  } finally {
    refreshPromise = null
  }
}

const unwrapResponse = <T>(response: AxiosResponse<ApiResponse<T>>): T => {
  const payload = response.data

  if (payload.code === '0000') {
    return payload.data
  }

  if (payload.code === '0401' || payload.code === '0410') {
    redirectToLoginIfNeeded()
  }

  throw new Error(payload.info || '请求失败，请稍后重试')
}

const retryWithFreshToken = async <T>(error: AxiosError<ApiResponse<T>>): Promise<T> => {
  const originalConfig = error.config as RetryableConfig | undefined
  if (!originalConfig || originalConfig._retry) {
    throw error
  }

  const status = error.response?.status
  const bizCode = error.response?.data?.code
  if (status !== 401 && bizCode !== '0401') {
    throw error
  }

  originalConfig._retry = true

  const nextToken = await getValidAccessToken()
  originalConfig.headers = originalConfig.headers ?? {}
  originalConfig.headers.Authorization = `Bearer ${nextToken}`

  const retried = await client.request<ApiResponse<T>>(originalConfig)
  return unwrapResponse(retried)
}

const request = async <T>(promise: Promise<AxiosResponse<ApiResponse<T>>>): Promise<T> => {
  try {
    const response = await promise
    return unwrapResponse(response)
  } catch (error) {
    if (axios.isAxiosError(error)) {
      try {
        return await retryWithFreshToken(error)
      } catch (retryErr) {
        if (axios.isAxiosError(retryErr)) {
          const retryStatus = retryErr.response?.status
          const retryCode = retryErr.response?.data?.code
          if (retryStatus === 401 || retryCode === '0401' || retryCode === '0410') {
            redirectToLoginIfNeeded()
          }
          return Promise.reject(new Error(retryErr.message || '网络异常，请检查连接后重试'))
        }

        redirectToLoginIfNeeded()
        return Promise.reject(new Error('登录已失效，请重新登录'))
      }
    }

    return Promise.reject(error)
  }
}

const normalizeParams = (params: AxiosRequestConfig['params']): Record<string, unknown> | undefined => {
  if (!params) {
    return undefined
  }
  if (params instanceof URLSearchParams) {
    return Object.fromEntries(params.entries())
  }
  if (typeof params === 'object' && !Array.isArray(params)) {
    return params as Record<string, unknown>
  }
  return undefined
}

const normalizeHeaders = (
  headers: AxiosRequestConfig['headers']
): Record<string, string | undefined> | undefined => {
  if (!headers) {
    return undefined
  }

  if (typeof (headers as { toJSON?: () => unknown }).toJSON === 'function') {
    const serialized = (headers as { toJSON: () => unknown }).toJSON()
    if (serialized && typeof serialized === 'object') {
      return serialized as Record<string, string | undefined>
    }
  }

  if (typeof headers === 'object') {
    return headers as Record<string, string | undefined>
  }

  return undefined
}

const toMockOptions = (
  config?: AxiosRequestConfig,
  data?: unknown
): MockRequestOptions => ({
  params: normalizeParams(config?.params),
  data,
  headers: normalizeHeaders(config?.headers)
})

const http = {
  get<T>(url: string, config?: AxiosRequestConfig): Promise<T> {
    if (uiMockEnabled) {
      return mockRequest<T>('get', url, toMockOptions(config))
    }
    return request(client.get<ApiResponse<T>>(url, config))
  },
  post<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
    if (uiMockEnabled) {
      return mockRequest<T>('post', url, toMockOptions(config, data))
    }
    return request(client.post<ApiResponse<T>>(url, data, config))
  },
  put<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
    if (uiMockEnabled) {
      return mockRequest<T>('put', url, toMockOptions(config, data))
    }
    return request(client.put<ApiResponse<T>>(url, data, config))
  },
  patch<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
    if (uiMockEnabled) {
      return mockRequest<T>('patch', url, toMockOptions(config, data))
    }
    return request(client.patch<ApiResponse<T>>(url, data, config))
  },
  delete<T>(url: string, config?: AxiosRequestConfig): Promise<T> {
    if (uiMockEnabled) {
      return mockRequest<T>('delete', url, toMockOptions(config))
    }
    return request(client.delete<ApiResponse<T>>(url, config))
  }
}

export default http
