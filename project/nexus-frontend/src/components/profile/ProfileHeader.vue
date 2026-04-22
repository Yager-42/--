<script setup lang="ts">
import type { ProfileHeaderViewModel } from '@/types/profile'

defineProps<{
  profile: ProfileHeaderViewModel
  me?: boolean
}>()

const emit = defineEmits<{
  (event: 'toggle-follow'): void
  (event: 'show-followers'): void
  (event: 'show-following'): void
  (event: 'edit-profile'): void
}>()
</script>

<template>
  <section class="rounded-[var(--nx-radius-card)] border border-nx-border bg-nx-surface p-6 shadow-[var(--nx-shadow-card)]">
    <div class="flex flex-col gap-5 sm:flex-row sm:items-start sm:justify-between">
      <div class="flex min-w-0 items-start gap-4">
        <div class="flex h-16 w-16 shrink-0 items-center justify-center rounded-full bg-nx-surface-muted text-lg font-semibold text-nx-text">
          {{ profile.nickname.slice(0, 1) }}
        </div>

        <div class="min-w-0">
          <h1 class="font-headline text-3xl font-semibold text-nx-text">{{ profile.nickname }}</h1>
          <p class="mt-2 text-sm leading-6 text-nx-text-muted">{{ profile.bio }}</p>
          <div class="mt-4 flex flex-wrap gap-4 text-sm text-nx-text-muted">
            <button data-test="view-followers" type="button" class="transition hover:text-nx-text" @click="emit('show-followers')">
              {{ profile.followerCountLabel }} 粉丝
            </button>
            <button data-test="view-following" type="button" class="transition hover:text-nx-text" @click="emit('show-following')">
              {{ profile.followingCountLabel }} 关注中
            </button>
          </div>
        </div>
      </div>

      <button
        data-test="follow-toggle"
        type="button"
        class="min-h-11 rounded-full border border-nx-border px-5 text-sm font-medium text-nx-text"
        @click="me ? emit('edit-profile') : emit('toggle-follow')"
      >
        {{ me ? '编辑资料' : profile.isFollowing ? '已关注' : '关注' }}
      </button>
    </div>
  </section>
</template>
