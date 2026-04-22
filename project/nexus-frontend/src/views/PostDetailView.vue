<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import CommentComposer from '@/components/comment/CommentComposer.vue'
import CommentList from '@/components/comment/CommentList.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import LoadingSkeleton from '@/components/common/LoadingSkeleton.vue'
import StatusMessage from '@/components/common/StatusMessage.vue'
import TimelineList from '@/components/feed/TimelineList.vue'
import PostDetailCard from '@/components/post/PostDetailCard.vue'
import {
  deleteComment,
  fetchCommentReplies,
  fetchComments,
  fetchHotComments,
  fetchPostDetail
} from '@/services/api/contentApi'
import { fetchNeighborsTimeline } from '@/services/api/feedApi'
import { pinComment, reactToTarget, submitComment } from '@/services/api/interactionApi'
import { ApiError } from '@/services/http/errors'
import { useAuthStore } from '@/stores/auth'
import { usePostInteractionsStore } from '@/stores/postInteractions'
import type { CommentItemViewModel, CommentReplyViewModel, PostDetailViewModel } from '@/types/content'
import type { FeedCardViewModel } from '@/types/viewModels'

type ReplyTarget =
  | { type: 'comment'; commentId: string; authorName: string }
  | { type: 'reply'; commentId: string; replyId: string; authorName: string }

const route = useRoute()
const authStore = useAuthStore()
const postInteractionsStore = usePostInteractionsStore()

const props = defineProps<{
  post?: PostDetailViewModel
  comments?: CommentItemViewModel[]
}>()

const remotePost = ref<PostDetailViewModel | null>(null)
const remoteComments = ref<CommentItemViewModel[]>([])
const similarPosts = ref<FeedCardViewModel[]>([])
const isSimilarLoading = ref(false)
const similarErrorMessage = ref('')
const hasRemoteComments = ref(false)
const isLoading = ref(false)
const replyTarget = ref<ReplyTarget | null>(null)
const composerInitialValue = ref('')
const commentErrorMessage = ref('')
const isCommentSubmitting = ref(false)
const commentsSectionRef = ref<HTMLElement | null>(null)
const lastFocusedThreadKey = ref('')
const focusedCommentId = ref('')
const isComposerOpen = ref(false)

const fallbackPost: PostDetailViewModel = {
  id: '0',
  authorId: '0',
  authorName: '未知作者',
  summary: '内容暂不可用',
  likeCountLabel: '0'
}

const resolvedPost = computed<PostDetailViewModel>(() => {
  const basePost = props.post ?? remotePost.value ?? fallbackPost
  const cachedInteraction = postInteractionsStore.getInteraction(basePost.id)

  if (!cachedInteraction) {
    return basePost
  }

  return {
    ...basePost,
    liked: cachedInteraction.liked,
    likeCountLabel: cachedInteraction.likeCountLabel
  }
})

const currentUserId = computed(() => String(authStore.currentUser?.userId ?? ''))
const canPinComments = computed(() => resolvedPost.value.authorId === currentUserId.value && currentUserId.value !== '')

const composerPlaceholder = computed(() => {
  return replyTarget.value ? `回复 ${replyTarget.value.authorName}...` : '写下你的评论。'
})

const composerSubmitLabel = computed(() => {
  return replyTarget.value ? '发送回复' : '发送评论'
})

const comments = computed(() => {
  const source = hasRemoteComments.value ? remoteComments.value : props.comments ?? []

  return source.map((item) => ({
    ...item,
    canPin: canPinComments.value,
    canDelete: item.authorId === currentUserId.value,
    repliesPreview: item.repliesPreview?.map((reply) => ({
      ...reply,
      canDelete: reply.authorId === currentUserId.value
    })),
    replies: item.replies?.map((reply) => ({
      ...reply,
      canDelete: reply.authorId === currentUserId.value
    }))
  }))
})

function getNumericId(value: string) {
  return Number(value.replace(/\D/g, '') || '0')
}

function resolveLikedState(post: unknown) {
  if (!post || typeof post !== 'object') {
    return false
  }

  return 'liked' in post ? Boolean((post as { liked?: boolean }).liked) : false
}

function getQueryValue(value: unknown) {
  if (typeof value === 'string') {
    return value
  }

  if (Array.isArray(value)) {
    return typeof value[0] === 'string' ? value[0] : ''
  }

  return ''
}

function mapRepliesWithPermission(replies: CommentReplyViewModel[]) {
  return replies.map((reply) => ({
    ...reply,
    canDelete: reply.authorId === currentUserId.value
  }))
}

function mergeRepliesIntoComment(commentId: string, replies: CommentReplyViewModel[]) {
  const nextReplies = mapRepliesWithPermission(replies)

  hasRemoteComments.value = true
  remoteComments.value = comments.value.map((item) =>
    item.id === commentId
      ? {
          ...item,
          replies: nextReplies,
          repliesPreview: nextReplies.slice(0, 3)
        }
      : item
  )
}

async function scrollToCommentFocus() {
  await nextTick()

  const targetCommentId = focusedCommentId.value || getQueryValue(route.query.commentId) || getQueryValue(route.query.rootCommentId)
  if (targetCommentId && typeof document !== 'undefined') {
    const target = document.querySelector<HTMLElement>(`[data-comment-id="${targetCommentId}"]`)
    if (target && typeof target.scrollIntoView === 'function') {
      target.scrollIntoView({ block: 'center', behavior: 'smooth' })
      return
    }
  }

  if (commentsSectionRef.value && typeof commentsSectionRef.value.scrollIntoView === 'function') {
    commentsSectionRef.value.scrollIntoView({ block: 'start', behavior: 'smooth' })
  }
}

function createLocalReply(commentId: number, content: string): CommentReplyViewModel {
  return {
    id: String(commentId),
    authorId: currentUserId.value || '0',
    authorName: authStore.currentUser?.nickname || 'Nexus User',
    body: content,
    canDelete: true
  }
}

function getReplyParentId(target: ReplyTarget) {
  return getNumericId(target.commentId)
}

function openComposer() {
  commentErrorMessage.value = ''
  isComposerOpen.value = true
}

function closeComposerIfIdle() {
  if (!replyTarget.value) {
    isComposerOpen.value = false
    commentErrorMessage.value = ''
  }
}

async function loadSimilarPosts(postId: string) {
  if (!postId || postId === '0') {
    similarPosts.value = []
    similarErrorMessage.value = ''
    isSimilarLoading.value = false
    return
  }

  isSimilarLoading.value = true
  similarErrorMessage.value = ''

  try {
    const neighbors = await fetchNeighborsTimeline(postId)
    similarPosts.value = neighbors.items
  } catch (error) {
    similarPosts.value = []
    similarErrorMessage.value =
      error instanceof ApiError || error instanceof Error
        ? error.message
        : '相似内容加载失败，请稍后重试'
  } finally {
    isSimilarLoading.value = false
  }
}

async function handleToggleLike() {
  const currentCount = Number(resolvedPost.value.likeCountLabel || '0')
  const nextLiked = !resolvedPost.value.liked
  const result = await reactToTarget({
    requestId: `post-${resolvedPost.value.id}-like`,
    targetId: getNumericId(resolvedPost.value.id),
    targetType: 'POST',
    type: 'LIKE',
    action: nextLiked ? 'ADD' : 'REMOVE'
  })

  const nextPost = {
    ...resolvedPost.value,
    liked: nextLiked,
    likeCountLabel: String(result.currentCount || (nextLiked ? currentCount + 1 : currentCount - 1))
  }

  remotePost.value = nextPost
  postInteractionsStore.updateInteraction(resolvedPost.value.id, {
    liked: nextPost.liked ?? false,
    likeCountLabel: nextPost.likeCountLabel
  })
}

async function handleCommentSubmit(content: string) {
  if (isCommentSubmitting.value) {
    return false
  }

  const target = replyTarget.value
  const currentComments = comments.value
  commentErrorMessage.value = ''
  isCommentSubmitting.value = true

  try {
    const result = await submitComment({
      postId: getNumericId(resolvedPost.value.id),
      parentId: target ? getReplyParentId(target) : undefined,
      content
    })

    if (target) {
      const newReply = createLocalReply(result.commentId, content)
      remoteComments.value = currentComments.map((item) => {
        if (item.id !== target.commentId) {
          return item
        }

        const nextReplies = [newReply, ...(item.replies ?? item.repliesPreview ?? [])]
        return {
          ...item,
          replyCount: (item.replyCount ?? 0) + 1,
          replies: nextReplies,
          repliesPreview: nextReplies.slice(0, 3)
        }
      })
      hasRemoteComments.value = true
      replyTarget.value = null
      composerInitialValue.value = ''
      closeComposerIfIdle()
      return true
    }

    remoteComments.value = [
      {
        id: String(result.commentId),
        authorId: currentUserId.value || '0',
        authorName: authStore.currentUser?.nickname || 'Nexus User',
        body: content,
        repliesPreview: [],
        canDelete: true,
        canPin: canPinComments.value
      },
      ...currentComments
    ]
    hasRemoteComments.value = true
    composerInitialValue.value = ''
    closeComposerIfIdle()
    return true
  } catch (error) {
    commentErrorMessage.value =
      error instanceof ApiError || error instanceof Error
        ? error.message
        : '评论发送失败，请稍后重试'
    return false
  } finally {
    isCommentSubmitting.value = false
  }
}

function handleReplyComment(commentId: string) {
  const comment = comments.value.find((item) => item.id === commentId)

  if (!comment) {
    return
  }

  replyTarget.value = {
    type: 'comment',
    commentId,
    authorName: comment.authorName
  }
  composerInitialValue.value = ''
  openComposer()
}

function handleReplyReply(payload: { commentId: string; replyId: string }) {
  const comment = comments.value.find((item) => item.id === payload.commentId)
  const reply = comment?.replies?.find((item) => item.id === payload.replyId)
    ?? comment?.repliesPreview?.find((item) => item.id === payload.replyId)

  if (!comment || !reply) {
    return
  }

  replyTarget.value = {
    type: 'reply',
    commentId: payload.commentId,
    replyId: payload.replyId,
    authorName: reply.authorName
  }
  composerInitialValue.value = `@${reply.authorName} `
  openComposer()
}

function handleCancelReply() {
  replyTarget.value = null
  composerInitialValue.value = ''
  closeComposerIfIdle()
}

async function handleViewReplies(commentId: string) {
  const response = await fetchCommentReplies(getNumericId(commentId))
  mergeRepliesIntoComment(commentId, response.items)
}

async function syncFocusedCommentThread() {
  const focus = getQueryValue(route.query.focus)
  const rootCommentId = getQueryValue(route.query.rootCommentId)
  const commentId = getQueryValue(route.query.commentId)
  const focusKey = `${resolvedPost.value.id}:${focus}:${rootCommentId}:${commentId}`

  if (focus !== 'comments' || !comments.value.length) {
    focusedCommentId.value = ''
    return
  }

  focusedCommentId.value = commentId || rootCommentId

  if (lastFocusedThreadKey.value === focusKey) {
    await scrollToCommentFocus()
    return
  }

  if (rootCommentId) {
    const targetComment = comments.value.find((item) => getNumericId(item.id) === getNumericId(rootCommentId))

    if (targetComment && !targetComment.replies?.length) {
      const response = await fetchCommentReplies(getNumericId(rootCommentId))
      mergeRepliesIntoComment(targetComment.id, response.items)
    }
  }

  lastFocusedThreadKey.value = focusKey
  await scrollToCommentFocus()
}

async function handlePinComment(commentId: string) {
  await pinComment({
    commentId: getNumericId(commentId),
    postId: getNumericId(resolvedPost.value.id)
  })

  const currentComments = comments.value
  const targetComment = currentComments.find((item) => item.id === commentId)

  if (!targetComment) {
    return
  }

  hasRemoteComments.value = true
  remoteComments.value = [
    {
      ...targetComment,
      isPinned: true
    },
    ...currentComments
      .filter((item) => item.id !== commentId)
      .map((item) => ({
        ...item,
        isPinned: false
      }))
  ]
}

async function handleDeleteComment(commentId: string) {
  if (!window.confirm('确认删除这条评论吗？')) {
    return
  }

  await deleteComment(getNumericId(commentId))

  hasRemoteComments.value = true
  remoteComments.value = comments.value.filter((item) => item.id !== commentId)

  if (replyTarget.value?.commentId === commentId) {
    replyTarget.value = null
    composerInitialValue.value = ''
    closeComposerIfIdle()
  }
}

async function handleDeleteReply(replyId: string) {
  if (!window.confirm('确认删除这条回复吗？')) {
    return
  }

  await deleteComment(getNumericId(replyId))

  hasRemoteComments.value = true
  remoteComments.value = comments.value.map((item) => {
    const replies = item.replies?.filter((reply) => reply.id !== replyId)
    const repliesPreview = item.repliesPreview?.filter((reply) => reply.id !== replyId)
    const removedCount =
      (item.replies?.length ?? 0) - (replies?.length ?? 0) ||
      (item.repliesPreview?.length ?? 0) - (repliesPreview?.length ?? 0)

    if (!removedCount) {
      return item
    }

    return {
      ...item,
      replyCount: Math.max((item.replyCount ?? 0) - removedCount, 0),
      replies,
      repliesPreview
    }
  })

  if (replyTarget.value?.type === 'reply' && replyTarget.value.replyId === replyId) {
    replyTarget.value = null
    composerInitialValue.value = ''
    closeComposerIfIdle()
  }
}

onMounted(async () => {
  if (!props.post && route.params.id) {
    isLoading.value = true

    try {
      const routePostId = String(route.params.id)
      const [detail, hotCommentData, commentData] = await Promise.all([
        fetchPostDetail(routePostId),
        fetchHotComments(routePostId),
        fetchComments(routePostId)
      ])

      remotePost.value = detail
      postInteractionsStore.primeInteraction(detail.id, {
        liked: resolveLikedState(detail),
        likeCountLabel: detail.likeCountLabel
      })
      hasRemoteComments.value = true
      remoteComments.value = hotCommentData.pinned
        ? [hotCommentData.pinned, ...commentData.comments]
        : commentData.comments
    } finally {
      isLoading.value = false
    }
  }

  await syncFocusedCommentThread()
})

watch(
  () => resolvedPost.value.id,
  async (postId) => {
    await loadSimilarPosts(postId)
  },
  { immediate: true }
)

watch(
  () => props.post,
  (post) => {
    if (!post) {
      return
    }

    postInteractionsStore.primeInteraction(post.id, {
      liked: resolveLikedState(post),
      likeCountLabel: post.likeCountLabel
    })
  },
  { immediate: true, deep: true }
)

watch(
  () => [comments.value.length, route.query.focus, route.query.rootCommentId, route.query.commentId],
  async () => {
    await syncFocusedCommentThread()
  }
)
</script>

<template>
  <section class="space-y-5">
    <LoadingSkeleton v-if="isLoading" />
    <PostDetailCard :post="resolvedPost" @toggle-like="handleToggleLike" />

    <section
      class="rounded-[var(--nx-radius-card)] border border-nx-border bg-nx-surface p-6 shadow-[var(--nx-shadow-card)]"
    >
      <div class="mb-4">
        <p class="text-sm font-medium uppercase tracking-[0.18em] text-nx-text-muted">Similar</p>
        <div class="mt-2 flex items-center justify-between gap-4">
          <h2 class="font-headline text-2xl font-semibold text-nx-text">类似内容推荐</h2>
          <button
            v-if="similarErrorMessage"
            data-test="retry-similar-posts"
            type="button"
            class="min-h-10 rounded-full border border-nx-border px-4 text-sm font-medium text-nx-text transition hover:border-nx-primary hover:text-nx-primary"
            @click="loadSimilarPosts(resolvedPost.id)"
          >
            重试
          </button>
        </div>
        <p class="mt-2 text-sm leading-6 text-nx-text-muted">
          基于当前内容的语义与互动信号，为你补充相近的可继续阅读内容。
        </p>
      </div>

      <div
        v-if="isSimilarLoading"
        data-test="similar-posts-loading"
        class="grid gap-3 md:grid-cols-2"
      >
        <div
          v-for="index in 2"
          :key="index"
          class="h-36 animate-pulse rounded-[var(--nx-radius-card)] border border-nx-border bg-nx-surface-muted"
        />
      </div>

      <template v-else-if="similarErrorMessage">
        <StatusMessage :message="similarErrorMessage" tone="error" />
      </template>

      <TimelineList
        v-else-if="similarPosts.length"
        data-test="similar-posts-list"
        :items="similarPosts"
      />

      <EmptyState
        v-else
        title="暂时没有相似内容"
        description="当前这篇内容还没有可展示的相似推荐，等更多互动信号积累后会出现在这里。"
      />
    </section>

    <section
      ref="commentsSectionRef"
      class="rounded-[var(--nx-radius-card)] border border-nx-border bg-nx-surface p-6 shadow-[var(--nx-shadow-card)]"
    >
      <header class="mb-4 flex items-center justify-between gap-4">
        <div>
          <p class="text-sm font-medium uppercase tracking-[0.18em] text-nx-text-muted">Conversation</p>
          <h2 class="mt-2 font-headline text-2xl font-semibold text-nx-text">评论</h2>
        </div>

        <button
          data-test="open-comment-composer"
          type="button"
          class="min-h-11 rounded-full border border-nx-border px-4 text-sm font-medium text-nx-text"
          @click="openComposer"
        >
          写评论
        </button>
      </header>

      <CommentComposer
        v-if="isComposerOpen"
        class="mb-4"
        :placeholder="composerPlaceholder"
        :submit-label="composerSubmitLabel"
        :reply-to-name="replyTarget?.authorName"
        :initial-value="composerInitialValue"
        :is-submitting="isCommentSubmitting"
        @submit="handleCommentSubmit"
        @cancel-reply="handleCancelReply"
      />
      <StatusMessage
        v-if="commentErrorMessage"
        class="mb-4"
        :message="commentErrorMessage"
        tone="error"
      />
      <CommentList
        v-if="comments.length"
        :comments="comments"
        :focused-comment-id="focusedCommentId"
        @reply-comment="handleReplyComment"
        @reply-reply="handleReplyReply"
        @view-replies="handleViewReplies"
        @pin-comment="handlePinComment"
        @delete-comment="handleDeleteComment"
        @delete-reply="handleDeleteReply"
      />

      <EmptyState
        v-else-if="!isLoading"
        title="还没有评论"
        description="内容已经准备好，下一步是把真实评论列表与回复展开接进来。"
      />
    </section>
  </section>
</template>
