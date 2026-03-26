<script setup lang="ts">
import { computed } from 'vue'
import type { CommentDisplayItem } from '@/api/interact'

const props = defineProps<{
  comment: CommentDisplayItem;
  compact?: boolean;
}>()

const timeText = computed(() => {
  if (!props.comment.createTime) {
    return '刚刚'
  }

  return new Date(props.comment.createTime).toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  })
})
</script>

<template>
  <div class="comment-item" :class="{ compact }">
    <img :src="comment.authorAvatar || 'https://via.placeholder.com/80'" class="author-avatar" />
    <div class="comment-main">
      <div class="comment-header">
        <div>
          <span class="author-name">{{ comment.authorName }}</span>
          <span v-if="comment.replyToId" class="reply-tag text-secondary">回复</span>
        </div>
        <span class="comment-time text-secondary">{{ timeText }}</span>
      </div>
      <p class="comment-body text-body">{{ comment.content }}</p>
      <div v-if="!compact" class="comment-meta text-secondary">
        <span>赞 {{ comment.likeCount }}</span>
        <span>回复 {{ comment.replyCount }}</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.comment-item {
  padding: 16px;
  background-color: var(--apple-card-bg);
  border-radius: 16px;
  margin-bottom: 12px;
  display: flex;
  gap: 12px;
}

.comment-item.compact {
  background: #fafafa;
}

.author-avatar {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  object-fit: cover;
  flex-shrink: 0;
}

.comment-main {
  flex: 1;
}

.comment-header {
  display: flex;
  justify-content: space-between;
  margin-bottom: 6px;
  align-items: center;
  gap: 12px;
}

.author-name {
  font-weight: 600;
  font-size: 15px;
}

.reply-tag {
  margin-left: 8px;
  font-size: 12px;
}

.comment-time {
  font-size: 13px;
  white-space: nowrap;
}

.comment-body {
  font-size: 15px;
  line-height: 1.4;
  white-space: pre-wrap;
}

.comment-meta {
  display: flex;
  gap: 12px;
  font-size: 12px;
  margin-top: 8px;
}
</style>
