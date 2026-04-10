<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/store/auth'
import ZenButton from '@/components/primitives/ZenButton.vue'
import ZenIcon from '@/components/primitives/ZenIcon.vue'
import SearchInput from '@/components/SearchInput.vue'

const router = useRouter()
const route = useRoute()
const authStore = useAuthStore()

const searchOpen = ref(false)

const routeLabel = computed(() => {
  if (route.path.startsWith('/search')) return 'Search'
  if (route.path.startsWith('/notifications')) return 'Activity'
  if (route.path.startsWith('/profile') || route.path.startsWith('/user')) return 'Profile'
  if (route.path.startsWith('/publish')) return 'Create'
  if (route.path.startsWith('/content')) return 'Editorial'
  return 'Gallery'
})

const ctaLabel = computed(() => (authStore.isLoggedIn() ? '我的主页' : '登录'))

const goHome = () => {
  void router.push('/')
}

const handleSearch = (keyword: string) => {
  void router.push({ path: '/search', query: { q: keyword } })
  searchOpen.value = false
}

const goNotifications = () => {
  void router.push('/notifications')
}

const goProfile = () => {
  void router.push('/profile')
}

const handleCta = () => {
  void router.push(authStore.isLoggedIn() ? '/profile' : '/login')
}
</script>

<template>
  <header class="fixed inset-x-0 top-0 z-50 px-4 pt-4 sm:px-6 lg:px-8">
    <div class="mx-auto flex w-full max-w-editorial items-center gap-4 rounded-[28px] border border-outline-variant/15 bg-white/70 px-4 py-3 shadow-soft backdrop-blur-editorial">
      <button
        type="button"
        class="flex min-w-0 items-center gap-3 rounded-full px-1 py-1 text-left transition hover:bg-surface-container-low/70"
        aria-label="回到首页"
        @click="goHome"
      >
        <span class="grid h-11 w-11 place-items-center rounded-full bg-gradient-to-br from-primary to-primary-dim text-sm font-extrabold text-on-primary">
          N
        </span>
        <span class="hidden min-w-0 sm:grid">
          <strong class="truncate text-lg font-bold tracking-tight text-on-surface">Nexus</strong>
          <small class="truncate text-[11px] font-semibold uppercase tracking-[0.26em] text-on-surface-variant">
            {{ routeLabel }}
          </small>
        </span>
      </button>

      <div class="min-w-0 flex-1">
        <SearchInput
          :is-expanded="searchOpen"
          @expand="searchOpen = true"
          @collapse="searchOpen = false"
          @search="handleSearch"
        />
      </div>

      <div class="hidden items-center gap-2 md:flex">
        <button
          type="button"
          class="grid h-11 w-11 place-items-center rounded-full border border-outline-variant/15 bg-surface-container-lowest/70 text-on-surface-variant transition hover:bg-surface-container-low hover:text-primary"
          aria-label="通知"
          @click="goNotifications"
        >
          <ZenIcon name="notifications" :size="20" />
        </button>

        <button
          type="button"
          class="grid h-11 w-11 place-items-center rounded-full border border-outline-variant/15 bg-surface-container-lowest/70 text-on-surface-variant transition hover:bg-surface-container-low hover:text-primary"
          aria-label="个人主页"
          @click="goProfile"
        >
          <ZenIcon name="person" :size="20" />
        </button>

        <ZenButton variant="secondary" class="min-w-[108px]" @click="handleCta">
          {{ ctaLabel }}
        </ZenButton>
      </div>
    </div>
  </header>
</template>
