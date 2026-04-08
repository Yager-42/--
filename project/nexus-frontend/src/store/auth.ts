import { defineStore } from 'pinia'
import { ref } from 'vue'

export const useAuthStore = defineStore('auth', () => {
  const token = ref<string | null>(localStorage.getItem('nexus_auth_token'))
  const refreshToken = ref<string | null>(localStorage.getItem('nexus_refresh_token'))
  const userId = ref<string | null>(localStorage.getItem('nexus_auth_user_id'))
  
  const setToken = (
    newToken: string,
    newUserId?: string | null,
    newRefreshToken?: string | null
  ) => {
    token.value = newToken
    localStorage.setItem('nexus_auth_token', newToken)

    if (newUserId !== undefined) {
      userId.value = newUserId
      if (newUserId) {
        localStorage.setItem('nexus_auth_user_id', newUserId)
      } else {
        localStorage.removeItem('nexus_auth_user_id')
      }
    }

    if (newRefreshToken !== undefined) {
      refreshToken.value = newRefreshToken
      if (newRefreshToken) {
        localStorage.setItem('nexus_refresh_token', newRefreshToken)
      } else {
        localStorage.removeItem('nexus_refresh_token')
      }
    }
  }

  const setUserId = (newUserId: string | null) => {
    userId.value = newUserId
    if (newUserId) {
      localStorage.setItem('nexus_auth_user_id', newUserId)
    } else {
      localStorage.removeItem('nexus_auth_user_id')
    }
  }

  const clearAuth = () => {
    token.value = null
    refreshToken.value = null
    userId.value = null
    localStorage.removeItem('nexus_auth_token')
    localStorage.removeItem('nexus_refresh_token')
    localStorage.removeItem('nexus_auth_user_id')
  }

  const isLoggedIn = () => {
    return !!token.value
  }

  return {
    token,
    refreshToken,
    userId,
    setToken,
    setUserId,
    clearAuth,
    isLoggedIn
  }
})
