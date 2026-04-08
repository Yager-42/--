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
  <article class="comment-item" :class="{ compact }">
    <img :src="comment.authorAvatar || 'https://via.placeholder.com/80'" class="avatar" alt="avatar">
    <div class="main">
      <header class="top">
        <p>
          <strong>{{ comment.authorName }}</strong>
          <span v-if="comment.replyToId" class="text-secondary"> 回复</span>
        </p>
        <span class="time text-secondary">{{ timeText }}</span>
      </header>

      <p class="body">{{ comment.content }}</p>

      <p v-if="!compact" class="meta text-secondary">
        赞 {{ comment.likeCount }} · 回复 {{ comment.replyCount }}
      </p>
    </div>
  </article>
</template>

<style scoped>
.comment-item {
  display: grid;
  grid-template-columns: 36px 1fr;
  gap: 10px;
  padding: 10px;
  border-radius: 12px;
  background: #fff;
  border: 1px solid #f8e0e8;
}

.comment-item.compact {
  background: #fffbfc;
}

.avatar {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  object-fit: cover;
}

.top {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  align-items: center;
}

.time {
  font-size: 0.76rem;
}

.body {
  margin-top: 4px;
  font-size: 0.92rem;
  line-height: 1.55;
  white-space: pre-wrap;
}

.meta {
  margin-top: 5px;
  font-size: 0.8rem;
}
</style>
