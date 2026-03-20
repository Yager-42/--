<script setup lang="ts">
defineProps<{
  comment: {
    authorName: string;
    content: string;
    createTime: number;
  }
}>();
</script>

<template>
  <div class="comment-item">
    <div class="comment-header">
      <span class="author-name">{{ comment.authorName }}</span>
      <span class="comment-time text-secondary">刚刚</span>
    </div>
    <p class="comment-body text-body">{{ comment.content }}</p>
  </div>
</template>

<style scoped>
.comment-item {
  padding: 16px;
  background-color: var(--apple-card-bg);
  border-radius: 16px;
  margin-bottom: 12px;
}

.comment-header {
  display: flex;
  justify-content: space-between;
  margin-bottom: 6px;
}

.author-name {
  font-weight: 600;
  font-size: 15px;
}

.comment-time {
  font-size: 13px;
}

.comment-body {
  font-size: 15px;
  line-height: 1.4;
}
</style>
