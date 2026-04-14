<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import ReactionButton from './ReactionButton.vue'
import { postReaction } from '@/api/interact'

const props = withDefaults(
  defineProps<{
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
    variant?: 'feature' | 'standard'
  }>(),
  {
    variant: 'standard'
  }
)

const isLiked = ref(Boolean(props.post.isLiked))
const count = ref(Number(props.post.reactionCount ?? 0))

watch(
  () => props.post,
  (post) => {
    isLiked.value = Boolean(post.isLiked)
    count.value = Number(post.reactionCount ?? 0)
  },
  { deep: true }
)

const bodyPreview = computed(() => props.post.body || '进入详情继续浏览这条内容。')

const handleLike = async () => {
  const previousLiked = isLiked.value
  const previousCount = count.value

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
    isLiked.value = previousLiked
    count.value = previousCount
    console.error('like failed', error)
  }
}
</script>

<template>
  <article
    class="overflow-hidden rounded-[30px] border border-outline-variant/10 bg-white/80 shadow-soft transition duration-200 hover:-translate-y-1 hover:shadow-float"
    :class="variant === 'feature' ? 'grid gap-0' : 'grid gap-0'"
    role="button"
    tabindex="0"
  >
    <div class="relative overflow-hidden" :class="variant === 'feature' ? 'aspect-[16/10]' : 'aspect-[4/5]'">
      <img :src="post.image" :alt="post.title" class="h-full w-full object-cover">
      <div class="absolute inset-0 bg-gradient-to-t from-[rgba(47,43,36,0.24)] to-transparent to-55%" />
      <div class="absolute bottom-4 right-4">
        <ReactionButton :is-liked="isLiked" :count="count" @toggle="handleLike" />
      </div>
    </div>

    <div class="grid gap-2.5 p-4">
      <p class="text-[11px] font-semibold uppercase tracking-[0.22em] text-on-surface-variant">{{ post.author }}</p>
      <h2 class="text-xl font-bold tracking-tight text-on-surface">{{ post.title }}</h2>
      <p class="line-clamp-3 text-sm leading-7 text-on-surface-variant">{{ bodyPreview }}</p>
    </div>
  </article>
</template>
