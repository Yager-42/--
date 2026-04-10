<script setup lang="ts">
import { computed } from 'vue'
import type { CommentDisplayItem } from '@/api/interact'

const props = defineProps<{
  comment: CommentDisplayItem
  compact?: boolean
}>()

const timeText = computed(() => {
  if (!props.comment.createTime) return '刚刚'
  return new Date(props.comment.createTime).toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  })
})
</script>

<template>
  <article
    class="grid grid-cols-[2.6rem,minmax(0,1fr)] gap-4 rounded-2xl border border-outline-variant/10 p-4"
    :class="compact ? 'bg-surface-container-low/75' : 'bg-white/80'"
  >
    <img :src="comment.authorAvatar || 'https://via.placeholder.com/80'" class="h-10 w-10 rounded-full object-cover" alt="avatar">

    <div class="grid gap-2">
      <header class="flex items-center justify-between gap-3">
        <div class="flex items-center gap-2">
          <strong class="text-sm font-semibold text-on-surface">{{ comment.authorName }}</strong>
          <span v-if="comment.replyToId" class="text-xs text-on-surface-variant">回复中</span>
        </div>
        <span class="text-xs text-on-surface-variant">{{ timeText }}</span>
      </header>

      <p class="whitespace-pre-wrap text-sm leading-7 text-on-surface">{{ comment.content }}</p>

      <p v-if="!compact" class="text-xs text-on-surface-variant">
        赞 {{ comment.likeCount }} · 回复 {{ comment.replyCount }}
      </p>
    </div>
  </article>
</template>
