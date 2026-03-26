<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/store/auth'
import { fetchContentDetail, type ContentDetailViewModel } from '@/api/content'
import {
  fetchComments,
  fetchCommentReplies,
  postComment,
  postReaction,
  type CommentDisplayItem,
  type RootCommentDisplayItem
} from '@/api/interact'
import CommentItem from '@/components/CommentItem.vue'

interface ReplyThreadState {
  expanded: boolean
  loading: boolean
  loaded: boolean
  items: CommentDisplayItem[]
  nextCursor: string | null
  hasMore: boolean
}

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()

const detail = ref<ContentDetailViewModel | null>(null)
const pinnedComment = ref<RootCommentDisplayItem | null>(null)
const comments = ref<RootCommentDisplayItem[]>([])
const nextCursor = ref<string | null>(null)
const hasMoreComments = ref(false)
const replyThreads = ref<Record<string, ReplyThreadState>>({})
const loading = ref(true)
const commentLoading = ref(false)
const sending = ref(false)
const errorMsg = ref('')
const commentContent = ref('')
const liked = ref(false)
const likeCount = ref(0)

const postId = computed(() => String(route.params.postId || ''))

const heroImage = computed(() => {
  return detail.value?.mediaUrls[0] || 'https://via.placeholder.com/1200x900?text=Nexus'
})

const mergeRootComments = (
  currentItems: RootCommentDisplayItem[],
  incomingItems: RootCommentDisplayItem[]
) => {
  const seen = new Set(currentItems.map((item) => item.commentId))
  const nextItems = incomingItems.filter((item) => {
    if (seen.has(item.commentId)) {
      return false
    }
    seen.add(item.commentId)
    return true
  })
  return [...currentItems, ...nextItems]
}

const mergeReplies = (currentItems: CommentDisplayItem[], incomingItems: CommentDisplayItem[]) => {
  const seen = new Set(currentItems.map((item) => item.commentId))
  const nextItems = incomingItems.filter((item) => {
    if (seen.has(item.commentId)) {
      return false
    }
    seen.add(item.commentId)
    return true
  })
  return [...currentItems, ...nextItems]
}

const ensureReplyThread = (root: RootCommentDisplayItem): ReplyThreadState => {
  if (!replyThreads.value[root.commentId]) {
    replyThreads.value[root.commentId] = {
      expanded: false,
      loading: false,
      loaded: false,
      items: [...root.repliesPreview],
      nextCursor: null,
      hasMore: root.replyCount > root.repliesPreview.length
    }
  }
  return replyThreads.value[root.commentId]
}

const loadDetail = async () => {
  if (!postId.value) {
    errorMsg.value = '缺少内容标识，无法加载内容'
    detail.value = null
    loading.value = false
    return
  }

  loading.value = true
  errorMsg.value = ''
  try {
    const res = await fetchContentDetail(postId.value, authStore.userId || undefined)
    detail.value = res
    likeCount.value = res.likeCount
    liked.value = false
  } catch (err: unknown) {
    errorMsg.value = err instanceof Error ? err.message : '内容加载失败'
    detail.value = null
  } finally {
    loading.value = false
  }
}

const loadComments = async (reset = true) => {
  if (!postId.value) return

  commentLoading.value = true
  try {
    const res = await fetchComments({
      postId: postId.value,
      cursor: reset ? undefined : nextCursor.value || undefined,
      limit: 20,
      preloadReplyLimit: 2
    })

    pinnedComment.value = res.pinned
    comments.value = reset ? res.items : mergeRootComments(comments.value, res.items)
    nextCursor.value = res.page.nextCursor
    hasMoreComments.value = res.page.hasMore

    if (reset) {
      replyThreads.value = {}
    }
  } catch (err) {
    console.error('Fetch comments failed', err)
    if (reset) {
      pinnedComment.value = null
      comments.value = []
      nextCursor.value = null
      hasMoreComments.value = false
      replyThreads.value = {}
    }
  } finally {
    commentLoading.value = false
  }
}

const loadReplies = async (root: RootCommentDisplayItem, append = false) => {
  const thread = ensureReplyThread(root)
  if (thread.loading) return
  if (append && !thread.hasMore) return

  thread.loading = true
  try {
    const res = await fetchCommentReplies({
      rootId: root.commentId,
      cursor: append ? thread.nextCursor || undefined : undefined,
      limit: 20
    })

    thread.items = append
      ? mergeReplies(thread.items, res.items)
      : mergeReplies(root.repliesPreview, res.items)
    thread.nextCursor = res.page.nextCursor
    thread.hasMore = res.page.hasMore
    thread.loaded = true
  } catch (err) {
    console.error('Fetch replies failed', err)
  } finally {
    thread.loading = false
  }
}

const handleLike = async () => {
  const previousLiked = liked.value
  const previousCount = likeCount.value

  liked.value = !liked.value
  likeCount.value = liked.value ? likeCount.value + 1 : Math.max(0, likeCount.value - 1)

  try {
    await postReaction({
      requestId: `detail_like_${Date.now()}`,
      targetId: postId.value,
      targetType: 'POST',
      type: 'LIKE',
      action: liked.value ? 'ADD' : 'REMOVE'
    })
  } catch (err) {
    liked.value = previousLiked
    likeCount.value = previousCount
    console.error('Like failed', err)
  }
}

const toggleReplies = async (root: RootCommentDisplayItem) => {
  const thread = ensureReplyThread(root)
  thread.expanded = !thread.expanded
  if (thread.expanded && !thread.loaded) {
    await loadReplies(root)
  }
}

const getReplyThread = (root: RootCommentDisplayItem) => {
  return ensureReplyThread(root)
}

const handlePostComment = async () => {
  const content = commentContent.value.trim()
  if (!content) return

  sending.value = true
  const optimisticCommentId = `local-${Date.now()}`
  const optimisticComment: RootCommentDisplayItem = {
    commentId: optimisticCommentId,
    postId: postId.value,
    userId: '',
    authorName: '我',
    authorAvatar: '',
    rootId: optimisticCommentId,
    parentId: '',
    replyToId: '',
    content,
    status: 0,
    likeCount: 0,
    replyCount: 0,
    createTime: Date.now(),
    repliesPreview: []
  }

  comments.value = [optimisticComment, ...comments.value]
  commentContent.value = ''

  try {
    await postComment({
      postId: postId.value,
      content
    })
    await loadComments(true)
  } catch (err) {
    console.error('Post comment failed', err)
    comments.value = comments.value.filter((item) => item.commentId !== optimisticCommentId)
    commentContent.value = content
  } finally {
    sending.value = false
  }
}

const loadAll = async () => {
  await Promise.all([loadDetail(), loadComments(true)])
}

onMounted(loadAll)

watch(() => route.params.postId, loadAll)
</script>

<template>
  <div class="detail-page">
    <div class="top-bar">
      <button class="back-btn" @click="router.back()">返回</button>
      <button class="like-btn" @click="handleLike">{{ liked ? '已赞' : '点赞' }} {{ likeCount }}</button>
    </div>

    <div v-if="loading" class="state-block">内容加载中...</div>
    <div v-else-if="errorMsg" class="state-block error-text">{{ errorMsg }}</div>
    <div v-else-if="detail" class="detail-content">
      <div class="hero-card">
        <img :src="heroImage" class="hero-image" />
      </div>

      <div class="content-card">
        <div class="author-row">
          <img :src="detail.authorAvatar || 'https://via.placeholder.com/80'" class="author-avatar" />
          <div>
            <p class="author-name">{{ detail.authorName }}</p>
            <p class="text-secondary">帖子 ID: {{ detail.postId }}</p>
          </div>
        </div>

        <h1 class="text-large-title">{{ detail.title }}</h1>
        <p class="text-body content-text">{{ detail.content || '暂无正文' }}</p>

        <div class="comment-section">
          <h2 class="text-headline">评论</h2>
          <div class="comment-editor">
            <input
              v-model="commentContent"
              class="comment-input"
              placeholder="写下你的评论"
              @keyup.enter="handlePostComment"
            />
            <button class="send-btn" :disabled="sending || !commentContent.trim()" @click="handlePostComment">
              {{ sending ? '发送中' : '发送' }}
            </button>
          </div>

          <div v-if="commentLoading" class="state-block">评论加载中...</div>
          <div v-else-if="!pinnedComment && comments.length === 0" class="state-block">还没有评论</div>
          <div v-else class="comment-list">
            <div v-if="pinnedComment" class="pinned-section">
              <p class="section-label">置顶评论</p>
              <CommentItem :comment="pinnedComment" />
            </div>

            <div
              v-for="item in comments"
              :key="item.commentId"
              class="root-comment-block"
            >
              <CommentItem :comment="item" />
              <button
                v-if="item.replyCount > 0"
                class="reply-toggle-btn"
                @click="toggleReplies(item)"
              >
                {{ getReplyThread(item).expanded ? '收起回复' : `查看回复（${item.replyCount}）` }}
              </button>

              <div v-if="getReplyThread(item).expanded" class="reply-thread">
                <CommentItem
                  v-for="reply in getReplyThread(item).items"
                  :key="reply.commentId"
                  :comment="reply"
                  compact
                />
                <button
                  v-if="getReplyThread(item).hasMore"
                  class="load-more-btn"
                  :disabled="getReplyThread(item).loading"
                  @click="loadReplies(item, true)"
                >
                  {{ getReplyThread(item).loading ? '加载中...' : '加载更多回复' }}
                </button>
              </div>
            </div>

            <button
              v-if="hasMoreComments"
              class="load-more-btn"
              :disabled="commentLoading"
              @click="loadComments(false)"
            >
              {{ commentLoading ? '加载中...' : '加载更多评论' }}
            </button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.detail-page {
  min-height: 100vh;
  background: var(--apple-bg);
  padding: 20px 16px 40px;
}

.top-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}

.back-btn,
.like-btn,
.send-btn {
  border: none;
  border-radius: 999px;
  cursor: pointer;
  font-size: 14px;
  font-weight: 600;
}

.back-btn,
.send-btn {
  padding: 10px 16px;
  background: #f5f5f7;
  color: var(--apple-text);
}

.like-btn {
  padding: 10px 18px;
  background: var(--apple-accent);
  color: white;
}

.hero-card,
.content-card {
  max-width: 840px;
  margin: 0 auto;
}

.hero-card {
  border-radius: 28px;
  overflow: hidden;
  background: var(--apple-card-bg);
  margin-bottom: 20px;
}

.hero-image {
  width: 100%;
  max-height: 420px;
  object-fit: cover;
  display: block;
}

.content-card {
  background: white;
  border-radius: 28px;
  padding: 24px;
  box-shadow: 0 12px 40px rgba(0, 0, 0, 0.06);
}

.author-row {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 20px;
}

.author-avatar {
  width: 48px;
  height: 48px;
  border-radius: 50%;
  object-fit: cover;
}

.author-name {
  font-size: 16px;
  font-weight: 600;
}

.content-text {
  margin-top: 16px;
  white-space: pre-wrap;
  line-height: 1.7;
}

.comment-section {
  margin-top: 28px;
  padding-top: 24px;
  border-top: 1px solid #ececf0;
}

.comment-editor {
  display: flex;
  gap: 12px;
  margin: 16px 0 20px;
}

.comment-input {
  flex: 1;
  height: 44px;
  border: 1px solid #d2d2d7;
  border-radius: 22px;
  padding: 0 16px;
  font-size: 15px;
  outline: none;
}

.comment-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.pinned-section,
.root-comment-block {
  display: flex;
  flex-direction: column;
}

.section-label {
  font-size: 13px;
  font-weight: 600;
  color: var(--apple-text-secondary);
  margin-bottom: 8px;
}

.reply-thread {
  margin-left: 24px;
  padding-left: 12px;
  border-left: 2px solid #ececf0;
}

.reply-toggle-btn,
.load-more-btn {
  border: none;
  background: none;
  color: var(--apple-accent);
  font-size: 14px;
  font-weight: 500;
  text-align: left;
  padding: 4px 0 8px 52px;
}

.state-block {
  max-width: 840px;
  margin: 40px auto;
  padding: 24px;
  border-radius: 20px;
  background: #f5f5f7;
  color: var(--apple-text-secondary);
  text-align: center;
}

.error-text {
  color: #ff3b30;
}

@media (max-width: 640px) {
  .detail-page {
    padding: 16px 12px 32px;
  }

  .content-card {
    padding: 18px;
  }

  .comment-editor {
    flex-direction: column;
  }
}
</style>
