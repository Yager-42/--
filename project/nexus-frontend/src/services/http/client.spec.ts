import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  clearSessionExpiredHandler,
  http,
  normalizeApiResponse,
  registerSessionExpiredHandler
} from '@/services/http/client'

describe('normalizeApiResponse', () => {
  afterEach(() => {
    localStorage.clear()
    http.defaults.adapter = undefined
    clearSessionExpiredHandler()
    window.history.replaceState({}, '', '/')
  })

  it('returns data for code 0000 and throws for business errors', () => {
    expect(normalizeApiResponse({ code: '0000', info: 'ok', data: { userId: 1 } })).toEqual({ userId: 1 })

    expect(() =>
      normalizeApiResponse({ code: '0404', info: 'not found', data: null })
    ).toThrow('not found')
  })

  it('injects Authorization header from session tokens', async () => {
    localStorage.setItem('nexus.accessToken', 'Bearer token-1')

    http.defaults.adapter = async (config) => ({
      data: { ok: true },
      status: 200,
      statusText: 'OK',
      headers: {},
      config
    })

    const response = await http.get('/api/v1/feed/timeline')

    expect(response.config.headers.Authorization).toBe('Bearer token-1')
  })

  it('refreshes access token and retries protected requests once', async () => {
    localStorage.setItem('nexus.accessToken', 'Bearer token-1')
    localStorage.setItem('nexus.refreshToken', 'refresh-1')

    const calls: string[] = []

    http.defaults.adapter = async (config) => {
      calls.push(`${config.method}:${config.url}:${String(config.headers.Authorization ?? '')}`)

      if (config.url === '/api/v1/feed/timeline' && config.headers.Authorization === 'Bearer token-1') {
        return {
          data: { code: '0002', info: '非法参数', data: null },
          status: 200,
          statusText: 'OK',
          headers: {},
          config
        }
      }

      if (config.url === '/api/v1/auth/refresh') {
        return {
          data: {
            code: '0000',
            info: 'ok',
            data: {
              userId: 7,
              tokenName: 'Authorization',
              tokenPrefix: 'Bearer',
              token: 'token-2',
              refreshToken: 'refresh-2'
            }
          },
          status: 200,
          statusText: 'OK',
          headers: {},
          config
        }
      }

      if (config.url === '/api/v1/feed/timeline' && config.headers.Authorization === 'Bearer token-2') {
        return {
          data: { code: '0000', info: 'ok', data: { ok: true } },
          status: 200,
          statusText: 'OK',
          headers: {},
          config
        }
      }

      throw new Error(`Unexpected request: ${config.method} ${config.url}`)
    }

    const response = await http.get('/api/v1/feed/timeline')

    expect(response.data).toEqual({ code: '0000', info: 'ok', data: { ok: true } })
    expect(localStorage.getItem('nexus.accessToken')).toBe('Bearer token-2')
    expect(localStorage.getItem('nexus.refreshToken')).toBe('refresh-2')
    expect(calls).toEqual([
      'get:/api/v1/feed/timeline:Bearer token-1',
      'post:/api/v1/auth/refresh:Bearer token-1',
      'get:/api/v1/feed/timeline:Bearer token-2'
    ])
  })

  it('clears session and redirects only when refresh also fails', async () => {
    localStorage.setItem('nexus.accessToken', 'Bearer token-1')
    localStorage.setItem('nexus.refreshToken', 'refresh-1')
    window.history.replaceState({}, '', '/compose/editor?draftId=9')

    const onExpired = vi.fn()
    registerSessionExpiredHandler(onExpired)

    http.defaults.adapter = async (config) => {
      if (config.url === '/api/v1/auth/refresh') {
        return {
          data: { code: '0002', info: 'refreshToken 无效或已过期', data: null },
          status: 200,
          statusText: 'OK',
          headers: {},
          config
        }
      }

      return {
        data: { code: '0002', info: '非法参数', data: null },
        status: 200,
        statusText: 'OK',
        headers: {},
        config
      }
    }

    const response = await http.get('/api/v1/feed/timeline')

    expect(response.data.code).toBe('0002')
    expect(localStorage.getItem('nexus.accessToken')).toBeNull()
    expect(localStorage.getItem('nexus.refreshToken')).toBeNull()
    expect(onExpired).toHaveBeenCalledWith('/compose/editor?draftId=9')
  })

  it('does not clear session when a retried request still returns a business 0002 error', async () => {
    localStorage.setItem('nexus.accessToken', 'Bearer token-1')
    localStorage.setItem('nexus.refreshToken', 'refresh-1')

    const onExpired = vi.fn()
    registerSessionExpiredHandler(onExpired)

    const calls: string[] = []

    http.defaults.adapter = async (config) => {
      calls.push(`${config.method}:${config.url}:${String(config.headers.Authorization ?? '')}`)

      if (config.url === '/api/v1/interact/comment' && config.headers.Authorization === 'Bearer token-1') {
        return {
          data: { code: '0002', info: '非法参数', data: null },
          status: 200,
          statusText: 'OK',
          headers: {},
          config
        }
      }

      if (config.url === '/api/v1/auth/refresh') {
        return {
          data: {
            code: '0000',
            info: 'ok',
            data: {
              userId: 7,
              tokenName: 'Authorization',
              tokenPrefix: 'Bearer',
              token: 'token-2',
              refreshToken: 'refresh-2'
            }
          },
          status: 200,
          statusText: 'OK',
          headers: {},
          config
        }
      }

      if (config.url === '/api/v1/interact/comment' && config.headers.Authorization === 'Bearer token-2') {
        return {
          data: { code: '0002', info: '非法参数', data: null },
          status: 200,
          statusText: 'OK',
          headers: {},
          config
        }
      }

      throw new Error(`Unexpected request: ${config.method} ${config.url}`)
    }

    const response = await http.post('/api/v1/interact/comment', {
      postId: 101,
      parentId: 999999,
      content: 'Reply body'
    })

    expect(response.data).toEqual({ code: '0002', info: '非法参数', data: null })
    expect(localStorage.getItem('nexus.accessToken')).toBe('Bearer token-2')
    expect(localStorage.getItem('nexus.refreshToken')).toBe('refresh-2')
    expect(onExpired).not.toHaveBeenCalled()
    expect(calls).toEqual([
      'post:/api/v1/interact/comment:Bearer token-1',
      'post:/api/v1/auth/refresh:Bearer token-1',
      'post:/api/v1/interact/comment:Bearer token-2'
    ])
  })

  it('does not treat register validation errors as session expiration', async () => {
    localStorage.setItem('nexus.accessToken', 'Bearer token-1')
    localStorage.setItem('nexus.refreshToken', 'refresh-1')

    const onExpired = vi.fn()
    registerSessionExpiredHandler(onExpired)

    http.defaults.adapter = async (config) => ({
      data: { code: '0002', info: 'smsCode 不能为空', data: null },
      status: 200,
      statusText: 'OK',
      headers: {},
      config
    })

    const response = await http.post('/api/v1/auth/register', {
      phone: '13800138000',
      password: 'Pwd@123456',
      nickname: 'tester'
    })

    expect(response.data.info).toBe('smsCode 不能为空')
    expect(localStorage.getItem('nexus.accessToken')).toBe('Bearer token-1')
    expect(localStorage.getItem('nexus.refreshToken')).toBe('refresh-1')
    expect(onExpired).not.toHaveBeenCalled()
  })
})
