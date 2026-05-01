import axios from 'axios'
import type { AxiosRequestConfig, InternalAxiosRequestConfig } from 'axios'
import type { ApiEnvelope } from '@/types/api'
import type { AuthTokenResponse } from '@/types/auth'
import { clearSessionTokens, readSessionTokens, writeSessionTokens } from './session'
import { ApiError } from './errors'

export function normalizeApiResponse<T>(payload: ApiEnvelope<T>): T {
  if (payload.code !== '0000') {
    throw new ApiError(payload.info || payload.code, payload.code)
  }

  return payload.data
}

export const http = axios.create({
  baseURL: '/',
  timeout: 10000
})

type RetriableRequestConfig = InternalAxiosRequestConfig & {
  _retryAfterRefresh?: boolean
}

const PUBLIC_AUTH_PATHS = new Set(['/api/v1/auth/login/password', '/api/v1/auth/register'])
const REFRESH_PATH = '/api/v1/auth/refresh'

let sessionExpiredHandler: ((redirectPath: string) => void | Promise<void>) | null = null
let refreshRequest: Promise<boolean> | null = null

export function registerSessionExpiredHandler(handler: (redirectPath: string) => void | Promise<void>) {
  sessionExpiredHandler = handler
}

export function clearSessionExpiredHandler() {
  sessionExpiredHandler = null
}

http.interceptors.request.use((config) => {
  const { accessToken } = readSessionTokens()

  if (accessToken) {
    config.headers = config.headers ?? {}
    config.headers.Authorization = accessToken
  }

  return config
})

http.interceptors.response.use(async (response) => {
  const payload = response.data as ApiEnvelope<unknown> | undefined

  if (!isProtectedAuthFailure(response.config, payload)) {
    return response
  }

  const requestConfig = response.config as RetriableRequestConfig
  const requestPath = resolveRequestPath(requestConfig.url)

  if (requestPath === REFRESH_PATH) {
    handleSessionExpired()
    return response
  }

  if (!requestConfig._retryAfterRefresh && (await refreshAccessToken())) {
    requestConfig._retryAfterRefresh = true
    const { accessToken } = readSessionTokens()
    requestConfig.headers = requestConfig.headers ?? {}
    if (accessToken) {
      requestConfig.headers.Authorization = accessToken
    }
    return http(requestConfig)
  }

  if (requestConfig._retryAfterRefresh) {
    return response
  }

  handleSessionExpired()
  return response
})

function isProtectedAuthFailure(config: AxiosRequestConfig, payload?: ApiEnvelope<unknown>) {
  if (!payload || payload.code !== '0002') {
    return false
  }

  const url = resolveRequestPath(config.url)
  if (!url) {
    return false
  }

  if (PUBLIC_AUTH_PATHS.has(url)) {
    return false
  }

  if (url === REFRESH_PATH) {
    return true
  }

  return payload.info === '非法参数'
}

function resolveRequestPath(url?: string) {
  if (!url) {
    return ''
  }

  if (url.startsWith('http://') || url.startsWith('https://')) {
    return new URL(url).pathname
  }

  return url.startsWith('/') ? url : `/${url}`
}

async function refreshAccessToken() {
  if (refreshRequest) {
    return refreshRequest
  }

  refreshRequest = performRefreshAccessToken()

  try {
    return await refreshRequest
  } finally {
    refreshRequest = null
  }
}

async function performRefreshAccessToken() {
  const { refreshToken } = readSessionTokens()

  if (!refreshToken) {
    return false
  }

  const response = await http.post<ApiEnvelope<AuthTokenResponse>>(REFRESH_PATH, { refreshToken }, {
    headers: {
      Authorization: ''
    }
  })

  if (response.data.code !== '0000' || !response.data.data) {
    return false
  }

  const tokenPayload = response.data.data
  const nextAccessToken = `${tokenPayload.tokenPrefix} ${tokenPayload.token}`.trim()
  writeSessionTokens(nextAccessToken, tokenPayload.refreshToken)
  return true
}

function handleSessionExpired() {
  clearSessionTokens()
  const redirectPath = typeof window === 'undefined'
    ? '/'
    : `${window.location.pathname}${window.location.search}${window.location.hash}`

  void sessionExpiredHandler?.(redirectPath)
}
