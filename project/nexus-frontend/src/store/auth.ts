import { defineStore } from 'pinia'
import { ref } from 'vue'

export const useAuthStore = defineStore('auth', () => {
  const token = ref<string | null>(localStorage.getItem('nexus_auth_token'))
  
  const setToken = (newToken: string) => {
    token.value = newToken
    localStorage.setItem('nexus_auth_token', newToken)
  }

  const clearAuth = () => {
    token.value = null
    localStorage.removeItem('nexus_auth_token')
  }

  // Check if currently logged in
  const isLoggedIn = () => {
    return !!token.value
  }

  return {
    token,
    setToken,
    clearAuth,
    isLoggedIn
  }
})
