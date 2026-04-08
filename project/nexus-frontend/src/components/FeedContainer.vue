<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useFeedStore } from '@/store/feed'
import PostCard from './PostCard.vue'
import type { FeedCardViewModel } from '@/api/feed'

const feedStore = useFeedStore()
const containerRef = ref<HTMLElement | null>(null)

const emit = defineEmits<{
  (event: 'select', post: FeedCardViewModel): void
}>()

const onScroll = () => {
  if (!containerRef.value) return
  const { scrollTop, scrollHeight, clientHeight } = containerRef.value
  if (scrollTop + clientHeight >= scrollHeight - 280) {
    void feedStore.fetchNextPage()
  }
}

onMounted(() => {
  if (feedStore.posts.length === 0) {
    void feedStore.fetchNextPage()
  }
})

const retryFetch = () => {
  void feedStore.refresh()
}
</script>

<template>
  <section
    ref="containerRef"
    class="feed-container"
    aria-label="推荐内容流"
    @scroll="onScroll"
  >
    <div v-if="feedStore.error && feedStore.posts.length === 0" class="error-state surface-card">
      <h3>内容加载失败</h3>
      <p>{{ feedStore.error }}</p>
      <button class="primary-btn retry-btn" type="button" @click="retryFetch">
        重试
      </button>
    </div>

    <div v-else class="feed-grid">
      <PostCard
        v-for="post in feedStore.posts"
        :key="post.postId"
        :post="{
          id: post.postId,
          title: post.title,
          body: post.body,
          author: post.author,
          image: post.image,
          isLiked: post.isLiked,
          reactionCount: post.reactionCount,
          commentCount: post.commentCount
        }"
        @click="emit('select', post)"
      />
    </div>

    <div v-if="feedStore.loading" class="loading-status">
      <div class="spinner"></div>
      <span>正在加载内容...</span>
    </div>

    <div v-if="!feedStore.loading && !feedStore.hasMore" class="loading-status done">
      已经到底了
    </div>
  </section>
</template>

<style scoped>
.feed-container {
  height: calc(100dvh - var(--header-height) - var(--dock-height) - var(--safe-top) - var(--safe-bottom) - 18px);
  overflow: auto;
  width: 100%;
}

.feed-grid {
  display: grid;
  gap: 14px;
}

.error-state {
  margin: 6px auto 18px;
  min-height: 180px;
  display: grid;
  place-items: center;
  text-align: center;
  gap: 10px;
  padding: 20px;
}

.error-state h3 {
  margin: 0;
  font-size: 1.05rem;
}

.error-state p {
  margin: 0;
  color: var(--text-secondary);
  font-size: 0.92rem;
}

.retry-btn {
  min-width: 120px;
}

.loading-status {
  margin: 6px auto 18px;
  min-height: 56px;
  border-radius: 14px;
  border: 1px solid var(--border-soft);
  background: var(--bg-surface);
  color: var(--text-secondary);
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  font-size: 0.92rem;
}

.loading-status.done {
  color: var(--text-muted);
}
</style>
