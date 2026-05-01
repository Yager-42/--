import { beforeEach, describe, expect, it, vi } from 'vitest'
import { http } from '@/services/http/client'
import { changePassword, logout, registerAccount } from '@/services/api/authApi'

describe('authApi expansion', () => {
  beforeEach(() => {
    http.defaults.adapter = undefined
  })

  it('calls logout endpoint', async () => {
    const postSpy = vi.spyOn(http, 'post').mockResolvedValue({
      data: {
        code: '0000',
        info: 'ok',
        data: null
      }
    } as never)

    await logout()

    expect(postSpy).toHaveBeenCalledWith('/api/v1/auth/logout')
  })

  it('submits password change payload', async () => {
    const postSpy = vi.spyOn(http, 'post').mockResolvedValue({
      data: {
        code: '0000',
        info: 'ok',
        data: null
      }
    } as never)

    await changePassword({
      oldPassword: 'old-secret',
      newPassword: 'new-secret'
    })

    expect(postSpy).toHaveBeenCalledWith('/api/v1/auth/password/change', {
      oldPassword: 'old-secret',
      newPassword: 'new-secret'
    })
  })

  it('submits register payload', async () => {
    const postSpy = vi.spyOn(http, 'post').mockResolvedValue({
      data: {
        code: '0000',
        info: 'ok',
        data: {
          userId: 9
        }
      }
    } as never)

    const response = await registerAccount({
      phone: '13800138000',
      password: 'secret123',
      nickname: 'Nexus New'
    })

    expect(postSpy).toHaveBeenCalledWith('/api/v1/auth/register', {
      phone: '13800138000',
      password: 'secret123',
      nickname: 'Nexus New'
    })
    expect(response.userId).toBe(9)
  })
})
