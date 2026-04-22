<script setup lang="ts">
import { storeToRefs } from 'pinia'
import { useUiStore } from '@/stores/ui'

const uiStore = useUiStore()
const { unreadNotificationCount } = storeToRefs(uiStore)
</script>

<template>
  <nav
    aria-label="Mobile primary"
    class="fixed inset-x-4 bottom-4 z-20 rounded-full border border-nx-border bg-white/95 p-2 shadow-[var(--nx-shadow-card)] backdrop-blur lg:hidden"
  >
    <ul class="grid grid-cols-4 gap-1">
      <li><RouterLink class="flex min-h-11 items-center justify-center rounded-full text-sm font-medium text-nx-text" to="/">首页</RouterLink></li>
      <li><RouterLink class="flex min-h-11 items-center justify-center rounded-full text-sm font-medium text-nx-text" to="/search">搜索</RouterLink></li>
      <li>
        <RouterLink class="relative flex min-h-11 items-center justify-center rounded-full text-sm font-medium text-nx-text" to="/notifications">
          通知
          <span
            v-if="unreadNotificationCount"
            class="absolute right-2 top-1.5 inline-flex min-w-5 items-center justify-center rounded-full bg-nx-accent px-1.5 py-0.5 text-[10px] font-semibold text-white"
          >
            {{ unreadNotificationCount }}
          </span>
        </RouterLink>
      </li>
      <li><RouterLink class="flex min-h-11 items-center justify-center rounded-full text-sm font-medium text-nx-text" to="/me">我的</RouterLink></li>
    </ul>
  </nav>
</template>
