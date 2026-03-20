<script setup lang="ts">
import ReactionButton from './ReactionButton.vue'
import { postReaction } from '@/api/interact'
import { ref } from 'vue'

const props = defineProps<{
  post: {
    id: string;
    title: string;
    body: string;
    author: string;
    image: string;
    isLiked?: boolean;
    reactionCount?: number;
    commentCount?: number;
  }
}>();

const isLiked = ref(props.post.isLiked || false);
const count = ref(props.post.reactionCount || 0);

const handleLike = async () => {
  // Optimistic UI Update
  const prevIsLiked = isLiked.value;
  const prevCount = count.value;
  
  isLiked.value = !isLiked.value;
  count.value = isLiked.value ? count.value + 1 : Math.max(0, count.value - 1);
  
  try {
    await postReaction({
      requestId: `req_${Date.now()}`,
      targetId: props.post.id,
      targetType: 'POST',
      type: 'LIKE',
      action: isLiked.value ? 'ADD' : 'REMOVE'
    })
  } catch (err) {
    // Rollback on error
    isLiked.value = prevIsLiked;
    count.value = prevCount;
    console.error('Like failed', err);
  }
}
</script>

<template>
  <div class="post-card">
    <div class="card-content">
      <div class="image-wrapper">
        <img :src="post.image" :alt="post.title" class="post-image" />
        <div class="interaction-layer">
          <ReactionButton 
            :is-liked="isLiked" 
            :count="count" 
            @toggle="handleLike"
          />
        </div>
      </div>
      <div class="text-overlay">
        <h2 class="text-large-title">{{ post.title }}</h2>
        <p class="text-body">{{ post.body }}</p>
      </div>
    </div>
  </div>
</template>

<style scoped>
.post-card {
  height: 100vh;
  width: 100%;
  scroll-snap-align: start;
  padding: 12px 16px;
  background-color: var(--apple-bg);
  display: flex;
  align-items: center;
  justify-content: center;
}

.card-content {
  width: 100%;
  height: 100%;
  background-color: var(--apple-card-bg);
  border-radius: 28px;
  overflow: hidden;
  position: relative;
  display: flex;
  flex-direction: column;
}

.image-wrapper {
  flex: 1;
  position: relative;
  overflow: hidden;
}

.post-image {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.interaction-layer {
  position: absolute;
  bottom: 24px;
  right: 24px;
  z-index: 5;
}

.text-overlay {
  padding: 32px 24px;
  background: white;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
</style>
