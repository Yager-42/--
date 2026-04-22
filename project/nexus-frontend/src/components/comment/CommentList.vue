<script setup lang="ts">
import type { CommentItemViewModel, CommentReplyViewModel } from '@/types/content'

defineProps<{
  comments: CommentItemViewModel[]
  focusedCommentId?: string
}>()

const emit = defineEmits<{
  (event: 'reply-comment', commentId: string): void
  (event: 'reply-reply', payload: { commentId: string; replyId: string }): void
  (event: 'view-replies', commentId: string): void
  (event: 'pin-comment', commentId: string): void
  (event: 'delete-comment', commentId: string): void
  (event: 'delete-reply', replyId: string): void
}>()

function getVisibleReplies(comment: CommentItemViewModel): CommentReplyViewModel[] {
  if (comment.replies?.length) {
    return comment.replies
  }

  return comment.repliesPreview ?? []
}
</script>

<template>
  <div class="space-y-3">
    <article
      v-for="comment in comments"
      :key="comment.id"
      :data-comment-id="comment.id"
      :data-comment-focused="focusedCommentId === comment.id ? 'true' : 'false'"
      class="rounded-[var(--nx-radius-card)] border border-nx-border bg-nx-surface p-5 shadow-[var(--nx-shadow-card)] transition"
      :class="focusedCommentId === comment.id ? 'border-nx-primary bg-blue-50/70 shadow-[0_18px_40px_rgba(37,99,235,0.12)]' : ''"
    >
      <div class="flex items-start justify-between gap-4">
        <div>
          <p class="text-sm font-semibold text-nx-text">{{ comment.authorName }}</p>
          <p class="mt-2 text-sm leading-6 text-nx-text-muted">{{ comment.body }}</p>
        </div>

        <span
          v-if="comment.isPinned"
          class="rounded-full bg-blue-50 px-3 py-1 text-xs font-semibold text-nx-primary"
        >
          置顶
        </span>
      </div>

      <div v-if="getVisibleReplies(comment).length" class="mt-3 space-y-2 border-l border-nx-border pl-4">
        <article
          v-for="reply in getVisibleReplies(comment)"
          :key="reply.id"
          :data-comment-id="reply.id"
          :data-comment-focused="focusedCommentId === reply.id ? 'true' : 'false'"
          class="rounded-2xl px-2 py-1 transition"
          :class="focusedCommentId === reply.id ? 'bg-blue-50/80 ring-1 ring-blue-200' : ''"
        >
          <div class="flex items-start justify-between gap-3">
            <div class="flex-1">
              <p class="text-xs font-semibold text-nx-text">{{ reply.authorName }}</p>
              <p class="mt-1 text-sm leading-6 text-nx-text-muted">{{ reply.body }}</p>
              <button
                :data-test="`reply-reply-${reply.id}`"
                type="button"
                class="mt-2 text-xs font-medium text-nx-primary"
                @click="emit('reply-reply', { commentId: comment.id, replyId: reply.id })"
              >
                回复
              </button>
            </div>
            <button
              v-if="reply.canDelete"
              :data-test="`delete-reply-${reply.id}`"
              type="button"
              class="inline-flex min-h-10 items-center justify-center rounded-full px-3 text-xs font-medium text-red-600 transition hover:bg-red-50"
              @click="emit('delete-reply', reply.id)"
            >
              删除回复
            </button>
          </div>
        </article>
      </div>

      <div class="mt-4 flex flex-wrap items-center gap-3">
        <span v-if="comment.likeCountLabel" class="text-xs text-nx-text-muted">{{ comment.likeCountLabel }} 赞</span>
        <button
          :data-test="`reply-comment-${comment.id}`"
          type="button"
          class="text-xs font-medium text-nx-primary"
          @click="emit('reply-comment', comment.id)"
        >
          回复
        </button>
        <button
          v-if="comment.replyCount"
          :data-test="`view-replies-${comment.id}`"
          type="button"
          class="text-xs font-medium text-nx-primary"
          @click="emit('view-replies', comment.id)"
        >
          查看回复 {{ comment.replyCount }}
        </button>
        <button
          v-if="comment.canPin && !comment.isPinned"
          :data-test="`pin-comment-${comment.id}`"
          type="button"
          class="inline-flex min-h-10 items-center justify-center rounded-full border border-nx-border px-3 text-xs font-medium text-nx-text transition hover:border-nx-primary hover:text-nx-primary"
          @click="emit('pin-comment', comment.id)"
        >
          置顶评论
        </button>
        <button
          v-if="comment.canDelete"
          :data-test="`delete-comment-${comment.id}`"
          type="button"
          class="inline-flex min-h-10 items-center justify-center rounded-full px-3 text-xs font-medium text-red-600 transition hover:bg-red-50"
          @click="emit('delete-comment', comment.id)"
        >
          删除评论
        </button>
      </div>
    </article>
  </div>
</template>
