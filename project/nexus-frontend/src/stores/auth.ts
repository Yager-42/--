import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { clearSessionTokens, readSessionTokens, writeSessionTokens } from '@/services/http/session'

type CurrentUser = {
  userId: number
  nickname?: string
  avatarUrl?: string
}

export const useAuthStore = defineStore('auth', () => {
  const currentUser = ref<CurrentUser | null>(null)
  const accessToken = ref(readSessionTokens().accessToken)
  const refreshToken = ref(readSessionTokens().refreshToken)
  const isAuthenticated = computed(() => Boolean(accessToken.value))

  async function completeLogin(payload: {
    userId: number
    tokenName: string
    tokenPrefix: string
    token: string
    refreshToken: string
  }) {
    accessToken.value = `${payload.tokenPrefix} ${payload.token}`.trim()
    refreshToken.value = payload.refreshToken
    currentUser.value = { userId: payload.userId }
    writeSessionTokens(accessToken.value, refreshToken.value)
  }

  function setCurrentUser(user: CurrentUser | null) {
    currentUser.value = user
  }

  function logoutLocally() {
    currentUser.value = null
    accessToken.value = ''
    refreshToken.value = ''
    clearSessionTokens()
  }

  return {
    currentUser,
    accessToken,
    refreshToken,
    isAuthenticated,
    completeLogin,
    setCurrentUser,
    logoutLocally
  }
})
