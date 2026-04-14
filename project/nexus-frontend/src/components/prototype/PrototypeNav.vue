<template>
  <header
    v-bind="$attrs"
    class="fixed inset-x-0 top-0 z-40 border-b border-prototype-line bg-prototype-bg/95 backdrop-blur-sm"
  >
    <PrototypeContainer
      as="div"
      class="flex min-h-[88px] items-center justify-between gap-6"
    >
      <div class="flex items-center gap-10">
        <RouterLink
          to="/"
          class="font-headline text-[1.75rem] tracking-[-0.04em] text-prototype-ink"
        >
          Nexus
        </RouterLink>

        <nav class="hidden items-center gap-6 md:flex">
          <RouterLink to="/" class="text-sm font-medium text-prototype-ink transition hover:text-prototype-accent">
            Gallery
          </RouterLink>
          <RouterLink to="/search" class="text-sm font-medium text-prototype-muted transition hover:text-prototype-ink">
            Search
          </RouterLink>
          <RouterLink to="/notifications" class="text-sm font-medium text-prototype-muted transition hover:text-prototype-ink">
            Notifications
          </RouterLink>
        </nav>
      </div>

      <div class="flex items-center gap-3">
        <RouterLink
          to="/publish"
          class="inline-flex min-h-[40px] items-center rounded-full border border-prototype-line px-4 text-sm font-medium text-prototype-ink transition hover:border-prototype-ink"
        >
          Publish
        </RouterLink>

        <AccountMenu
          :account="account"
          :busy="isSubmittingPassword || isLoggingOut"
          @open-profile="router.push('/profile')"
          @change-password="passwordPanelOpen = true"
          @logout="handleLogout"
        />
      </div>
    </PrototypeContainer>
  </header>

  <PasswordChangePanel
    :open="passwordPanelOpen"
    :saving="isSubmittingPassword"
    @close="passwordPanelOpen = false"
    @submit="handlePasswordSubmit"
  />
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { changePassword, fetchAuthMe, logout, type AuthMeResponseDTO, type ChangePasswordRequestDTO } from '@/api/auth'
import AccountMenu from '@/components/account/AccountMenu.vue'
import PasswordChangePanel from '@/components/account/PasswordChangePanel.vue'
import PrototypeContainer from '@/components/prototype/PrototypeContainer.vue'
import { useAuthStore } from '@/store/auth'

defineOptions({
  inheritAttrs: false
})

const router = useRouter()
const authStore = useAuthStore()

const account = ref<AuthMeResponseDTO | null>(null)
const passwordPanelOpen = ref(false)
const isLoggingOut = ref(false)
const isSubmittingPassword = ref(false)

const routeToForcedLogin = async () => {
  await router.push('/login?forceAuth=1')
}

const bootstrapAccount = async () => {
  if (!authStore.token) {
    return
  }

  try {
    const nextAccount = await fetchAuthMe()
    account.value = nextAccount
    authStore.setUserId(String(nextAccount.userId))
  } catch (error) {
    console.error('Failed to bootstrap account menu identity', error)
  }
}

const handleLogout = async () => {
  if (isLoggingOut.value) {
    return
  }

  isLoggingOut.value = true

  try {
    await logout()
  } catch (error) {
    console.error('Failed to log out', error)
  } finally {
    account.value = null
    authStore.clearAuth()
    await routeToForcedLogin()
    isLoggingOut.value = false
  }
}

const handlePasswordSubmit = async (payload: ChangePasswordRequestDTO) => {
  if (isSubmittingPassword.value) {
    return
  }

  isSubmittingPassword.value = true

  try {
    await changePassword(payload)
    passwordPanelOpen.value = false
    account.value = null
    authStore.clearAuth()
    await routeToForcedLogin()
  } catch (error) {
    console.error('Failed to change password', error)
  } finally {
    isSubmittingPassword.value = false
  }
}

onMounted(() => {
  void bootstrapAccount()
})
</script>
