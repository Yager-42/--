<script setup lang="ts">
import { ref } from 'vue'
import ReactionButton from './ReactionButton.vue'
import { postReaction } from '@/api/interact'

const props = defineProps<{
  post: {
    id: string
    title: string
    body: string
    author: string
    image: string
    isLiked?: boolean
    reactionCount?: number
    commentCount?: number
  }
}>()

const isLiked = ref(Boolean(props.post.isLiked))
const count = ref(Number(props.post.reactionCount ?? 0))

const handleLike = async () => {
  const prevLiked = isLiked.value
  const prevCount = count.value

  isLiked.value = !isLiked.value
  count.value = isLiked.value ? count.value + 1 : Math.max(0, count.value - 1)

  try {
    await postReaction({
      requestId: `req_${Date.now()}`,
      targetId: props.post.id,
      targetType: 'POST',
      type: 'LIKE',
      action: isLiked.value ? 'ADD' : 'REMOVE'
    })
  } catch (error) {
    isLiked.value = prevLiked
    count.value = prevCount
    console.error('like failed', error)
  }
}
</script>

<template>
  <article class="post-card" role="button" tabindex="0">
    <div class="cover-wrap">
      <img :src="post.image" :alt="post.title" class="cover-image">
      <div class="cover-gradient"></div>
      <div class="reaction-layer">
        <ReactionButton :is-liked="isLiked" :count="count" @toggle="handleLike" />
      </div>
    </div>

    <div class="post-body">
      <p class="author">@{{ post.author }}</p>
      <h2 class="title">{{ post.title }}</h2>
      <p class="desc">{{ post.body }}</p>
    </div>
  </article>
</template>

<style scoped>
.post-card {
  border: 1px solid var(--border-soft);
  border-radius: var(--radius-xl);
  overflow: hidden;
  background: var(--bg-surface);
  box-shadow: var(--shadow-soft);
  cursor: pointer;
  transition: transform 180ms ease, box-shadow 180ms ease;
}

.post-card:hover {
  transform: translateY(-2px);
  box-shadow: var(--shadow-elevated);
}

.cover-wrap {
  position: relative;
  aspect-ratio: 16 / 11;
  overflow: hidden;
  background: #f9e4ea;
}

.cover-image {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.cover-gradient {
  position: absolute;
  inset: 0;
  background: linear-gradient(to top, rgba(45, 16, 32, 0.3), rgba(45, 16, 32, 0));
}

.reaction-layer {
  position: absolute;
  right: 14px;
  bottom: 14px;
}

.post-body {
  padding: 14px 16px 16px;
}

.author {
  color: var(--text-secondary);
  font-size: 0.84rem;
  margin-bottom: 6px;
}

.title {
  margin: 0;
  font-size: 1.1rem;
  line-height: 1.35;
  font-weight: 800;
  font-family: 'Manrope', 'Noto Sans SC', sans-serif;
}

.desc {
  margin-top: 8px;
  color: var(--text-secondary);
  font-size: 0.95rem;
  line-height: 1.6;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
</style>
