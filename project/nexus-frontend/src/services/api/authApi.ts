import { http, normalizeApiResponse } from '@/services/http/client'
import type { ApiEnvelope } from '@/types/api'
import type {
  AuthChangePasswordPayload,
  AuthLoginPayload,
  AuthMe,
  AuthRegisterPayload,
  AuthTokenResponse
} from '@/types/auth'

export async function loginByPassword(payload: AuthLoginPayload) {
  const response = await http.post<ApiEnvelope<AuthTokenResponse>>('/api/v1/auth/login/password', payload)
  return normalizeApiResponse(response.data)
}

export async function registerAccount(payload: AuthRegisterPayload) {
  const response = await http.post<ApiEnvelope<{ userId: number }>>('/api/v1/auth/register', payload)
  return normalizeApiResponse(response.data)
}

export async function fetchMe() {
  const response = await http.get<ApiEnvelope<AuthMe>>('/api/v1/auth/me')
  return normalizeApiResponse(response.data)
}

export async function logout() {
  const response = await http.post<ApiEnvelope<null>>('/api/v1/auth/logout')
  return normalizeApiResponse(response.data)
}

export async function changePassword(payload: AuthChangePasswordPayload) {
  const response = await http.post<ApiEnvelope<null>>('/api/v1/auth/password/change', payload)
  return normalizeApiResponse(response.data)
}
