<script setup lang="ts">
import { computed } from 'vue'
import type { ContentDetailViewModel } from '@/api/content'

const props = defineProps<{
  detail: ContentDetailViewModel
}>()

const heroImage = computed(
  () => props.detail.mediaUrls[0] || 'https://via.placeholder.com/1200x900?text=Nexus'
)
</script>

<template>
  <section class="grid gap-6">
    <div class="grid gap-4">
      <p class="section-kicker">Editorial Feature</p>
      <h1 class="text-[clamp(2.4rem,5vw,4.5rem)] font-bold leading-[0.95] tracking-tight text-on-surface">
        {{ detail.title }}
      </h1>
      <p class="flex flex-wrap gap-4 text-sm text-on-surface-variant">
        <span>{{ detail.authorName }}</span>
        <span v-if="detail.locationInfo">{{ detail.locationInfo }}</span>
        <span v-if="detail.likeCount">{{ detail.likeCount }} 喜欢</span>
      </p>
      <p v-if="detail.summary" class="max-w-3xl text-sm leading-8 text-on-surface-variant md:text-base">
        {{ detail.summary }}
      </p>
    </div>

    <div class="overflow-hidden rounded-[32px]">
      <img :src="heroImage" :alt="detail.title" class="min-h-[18rem] w-full object-cover">
    </div>
  </section>
</template>
