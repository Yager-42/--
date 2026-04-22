<script setup lang="ts">
import PostInteractionBar from '@/components/post/PostInteractionBar.vue'
import type { PostDetailViewModel } from '@/types/content'

defineProps<{
  post: PostDetailViewModel
}>()

const emit = defineEmits<{
  (event: 'toggle-like'): void
}>()
</script>

<template>
  <article class="rounded-[var(--nx-radius-card)] border border-nx-border bg-nx-surface p-6 shadow-[var(--nx-shadow-card)]">
    <header class="flex items-start justify-between gap-4">
      <div class="min-w-0">
        <p class="text-sm font-medium uppercase tracking-[0.18em] text-nx-text-muted">Story</p>
        <h1 class="mt-2 font-headline text-3xl font-semibold text-nx-text">
          {{ post.title || post.summary }}
        </h1>
        <p class="mt-3 text-sm text-nx-text-muted">{{ post.authorName }}</p>
      </div>

      <span class="rounded-full bg-nx-surface-muted px-3 py-1 text-xs font-medium text-nx-text-muted">
        {{ post.likeCountLabel }} 赞
      </span>
    </header>

    <p class="mt-5 text-base leading-7 text-nx-text">{{ post.body || post.summary }}</p>
    <PostInteractionBar :liked="post.liked" :like-count-label="post.likeCountLabel" @toggle-like="emit('toggle-like')" />
  </article>
</template>
