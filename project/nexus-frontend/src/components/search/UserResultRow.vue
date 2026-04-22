<script setup lang="ts">
import type { SearchUserViewModel } from '@/types/search'

defineProps<{
  user: SearchUserViewModel
}>()
</script>

<template>
  <RouterLink
    :data-test="`search-user-link-${user.id}`"
    :to="`/profile/${user.id}`"
    class="flex items-center justify-between gap-4 rounded-[var(--nx-radius-card)] border border-nx-border bg-nx-surface p-5 shadow-[var(--nx-shadow-card)] transition hover:-translate-y-0.5 hover:border-nx-primary"
  >
    <article class="flex min-w-0 flex-1 items-center justify-between gap-4">
      <div class="min-w-0">
        <p class="text-base font-semibold text-nx-text">{{ user.nickname }}</p>
        <p class="mt-1 text-sm leading-6 text-nx-text-muted">{{ user.bio }}</p>
      </div>

      <span
        class="min-h-11 shrink-0 rounded-full border border-nx-border px-4 py-2 text-sm font-medium text-nx-text"
      >
        {{ user.isFollowing ? '已关注' : '关注' }}
      </span>
    </article>
  </RouterLink>
</template>
