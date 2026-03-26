<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useFeedStore } from '@/store/feed'
import PostCard from './PostCard.vue'
import type { FeedCardViewModel } from '@/api/feed'

const feedStore = useFeedStore()
const containerRef = ref<HTMLElement | null>(null)

const emit = defineEmits(['select']);

const onScroll = () => {
  if (!containerRef.value) return
  const { scrollTop, scrollHeight, clientHeight } = containerRef.value
  
  // Trigger load more when scroll reaches 80%
  if (scrollTop + clientHeight >= scrollHeight * 0.8) {
    feedStore.fetchNextPage()
  }
}

const onCardClick = (post: FeedCardViewModel) => {
  emit('select', post);
}

onMounted(() => {
  // Initial load
  if (feedStore.posts.length === 0) {
    feedStore.fetchNextPage()
  }
})
</script>

<template>
  <div 
    ref="containerRef" 
    class="feed-container" 
    @scroll="onScroll"
  >
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
      @click="onCardClick(post)"
    />
    
    <div v-if="feedStore.loading" class="loading-status">
      <div class="spinner"></div>
    </div>
    <div v-if="!feedStore.hasMore" class="loading-status text-secondary">
      已显示全部内容
    </div>
  </div>
</template>

<style scoped>
.feed-container {
  height: 100vh;
  width: 100%;
  overflow-y: scroll;
  scroll-snap-type: y mandatory;
  -webkit-overflow-scrolling: touch;
}

.feed-container::-webkit-scrollbar {
  display: none;
}

.loading-status {
  padding: 40px 0;
  display: flex;
  justify-content: center;
  align-items: center;
  font-size: 14px;
}

.spinner {
  width: 20px;
  height: 20px;
  border: 2px solid rgba(0, 0, 0, 0.1);
  border-top-color: var(--apple-accent);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}
</style>
