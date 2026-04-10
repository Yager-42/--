<script setup lang="ts">
import type { FeedCardViewModel } from '@/api/feed'

defineProps<{
  post: FeedCardViewModel
}>()

defineEmits<{
  (event: 'select', post: FeedCardViewModel): void
}>()
</script>

<template>
  <article
    class="grid cursor-pointer gap-5 rounded-[36px] border border-outline-variant/10 bg-white/80 p-4 shadow-editorial transition duration-200 hover:-translate-y-1 hover:shadow-float md:grid-cols-[minmax(0,1.1fr),minmax(18rem,0.9fr)] md:p-6"
    @click="$emit('select', post)"
  >
    <div class="min-h-[18rem] overflow-hidden rounded-[24px]">
      <img :src="post.image" :alt="post.title" class="h-full w-full object-cover">
    </div>

    <div class="grid content-end gap-4">
      <p class="section-kicker">Featured Story</p>
      <h1 class="text-[clamp(2rem,4vw,3.6rem)] font-bold leading-[0.96] tracking-tight text-on-surface">
        {{ post.title }}
      </h1>
      <p class="line-clamp-4 text-sm leading-8 text-on-surface-variant md:text-base">
        {{ post.body || '进入详情继续阅读这则内容。' }}
      </p>
      <div class="flex flex-wrap gap-4 text-sm text-on-surface-variant">
        <span>{{ post.author }}</span>
        <span>{{ post.reactionCount }} 喜欢</span>
      </div>
    </div>
  </article>
</template>
