<script setup lang="ts">
import type { ProfilePageViewModel } from '@/api/user'
import ZenButton from '@/components/primitives/ZenButton.vue'

defineProps<{
  profile: ProfilePageViewModel
  isMyProfile: boolean
}>()

defineEmits<{
  (event: 'edit'): void
}>()
</script>

<template>
  <section class="grid gap-8">
    <div class="grid gap-6 md:grid-cols-[8rem,minmax(0,1fr)] md:items-center">
      <img
        :src="profile.avatar || 'https://via.placeholder.com/160'"
        alt="avatar"
        class="h-28 w-28 rounded-full object-cover md:h-32 md:w-32"
      >
      <div class="grid gap-3">
        <p class="text-[11px] font-semibold uppercase tracking-[0.24em] text-prototype-muted">
          Creator Profile
        </p>
        <h2 class="font-headline text-4xl tracking-[-0.04em] text-prototype-ink md:text-5xl">
          {{ profile.nickname }}
        </h2>
        <p class="max-w-2xl text-sm leading-7 text-prototype-muted">
          {{ profile.bio || '这个用户还没有填写个人简介。' }}
        </p>
      </div>
    </div>

    <div class="grid gap-3 md:grid-cols-3">
      <div class="rounded-[1.5rem] border border-prototype-line bg-prototype-bg px-5 py-4">
        <p class="font-headline text-3xl tracking-[-0.04em] text-prototype-ink">
          {{ profile.stats.likeCount }}
        </p>
        <p class="mt-1 text-xs font-semibold uppercase tracking-[0.18em] text-prototype-muted">
          获赞
        </p>
      </div>
      <div class="rounded-[1.5rem] border border-prototype-line bg-prototype-bg px-5 py-4">
        <p class="font-headline text-3xl tracking-[-0.04em] text-prototype-ink">
          {{ profile.stats.followCount }}
        </p>
        <p class="mt-1 text-xs font-semibold uppercase tracking-[0.18em] text-prototype-muted">
          关注
        </p>
      </div>
      <div class="rounded-[1.5rem] border border-prototype-line bg-prototype-bg px-5 py-4">
        <p class="font-headline text-3xl tracking-[-0.04em] text-prototype-ink">
          {{ profile.stats.followerCount }}
        </p>
        <p class="mt-1 text-xs font-semibold uppercase tracking-[0.18em] text-prototype-muted">
          粉丝
        </p>
      </div>
    </div>

    <ZenButton v-if="isMyProfile" variant="secondary" class="justify-self-start" @click="$emit('edit')">
      编辑资料
    </ZenButton>
  </section>
</template>

