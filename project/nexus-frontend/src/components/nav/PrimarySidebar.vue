<script setup lang="ts">
import { storeToRefs } from 'pinia'
import { useUiStore } from '@/stores/ui'

const uiStore = useUiStore()
const { unreadNotificationCount } = storeToRefs(uiStore)
</script>

<template>
  <nav aria-label="Primary" class="hidden w-64 shrink-0 lg:block">
    <div class="sticky top-6 rounded-[var(--nx-radius-card)] border border-nx-border bg-nx-surface p-5 shadow-[var(--nx-shadow-card)]">
      <ul class="space-y-2">
        <li><RouterLink class="block rounded-2xl px-4 py-3 text-sm font-medium text-nx-text" to="/">首页</RouterLink></li>
        <li><RouterLink class="block rounded-2xl px-4 py-3 text-sm font-medium text-nx-text" to="/search">搜索</RouterLink></li>
        <li>
          <RouterLink class="flex items-center justify-between rounded-2xl px-4 py-3 text-sm font-medium text-nx-text" to="/notifications">
            <span>通知</span>
            <span
              v-if="unreadNotificationCount"
              class="inline-flex min-w-6 items-center justify-center rounded-full bg-nx-accent px-2 py-0.5 text-xs font-semibold text-white"
            >
              {{ unreadNotificationCount }}
            </span>
          </RouterLink>
        </li>
        <li><RouterLink class="block rounded-2xl px-4 py-3 text-sm font-medium text-nx-text" to="/me">我的</RouterLink></li>
      </ul>
    </div>
  </nav>
</template>
