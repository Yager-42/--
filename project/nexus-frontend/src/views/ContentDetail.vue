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
const heroImage = computed(() => detail.value?.mediaUrls[0] || 'https://via.placeholder.com/1200x900?text=Nexus')

const mergeRootComments = (currentItems: RootCommentDisplayItem[], incomingItems: RootCommentDisplayItem[]) => {
  const seen = new Set(currentItems.map((item) => item.commentId))
  const nextItems = incomingItems.filter((item) => {
    if (seen.has(item.commentId)) return false
    seen.add(item.commentId)
    return true
  })
  return [...currentItems, ...nextItems]
}

const mergeReplies = (currentItems: CommentDisplayItem[], incomingItems: CommentDisplayItem[]) => {
  const seen = new Set(currentItems.map((item) => item.commentId))
  const nextItems = incomingItems.filter((item) => {
    if (seen.has(item.commentId)) return false
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
    errorMsg.value = '缺少内容标识，无法加载详情'
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
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : '内容加载失败'
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
  } catch (error) {
    console.error('fetch comments failed', error)
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
  if (thread.loading || (append && !thread.hasMore)) return

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
  } catch (error) {
    console.error('fetch replies failed', error)
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
  } catch (error) {
    liked.value = previousLiked
    likeCount.value = previousCount
    console.error('like failed', error)
  }
}

const toggleReplies = async (root: RootCommentDisplayItem) => {
  const thread = ensureReplyThread(root)
  thread.expanded = !thread.expanded
  if (thread.expanded && !thread.loaded) {
    await loadReplies(root)
  }
}

const getReplyThread = (root: RootCommentDisplayItem) => ensureReplyThread(root)

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
    await postComment({ postId: postId.value, content })
    await loadComments(true)
  } catch (error) {
    console.error('post comment failed', error)
    comments.value = comments.value.filter((item) => item.commentId !== optimisticCommentId)
    commentContent.value = content
  } finally {
    sending.value = false
  }
}

const loadAll = async () => {
  await Promise.all([loadDetail(), loadComments(true)])
}

onMounted(() => {
  void loadAll()
})

watch(() => route.params.postId, () => {
  void loadAll()
})
</script>

<template>
  <div class="page-shell with-top-nav detail-page">
    <main class="page-content detail-content">
      <header class="bar surface-card">
        <button class="secondary-btn" type="button" @click="router.back()">返回</button>
        <button class="primary-btn" type="button" @click="handleLike">
          {{ liked ? '已赞' : '点赞' }} {{ likeCount }}
        </button>
      </header>

      <section v-if="loading" class="state-card">
        <div class="spinner"></div>
        正在加载内容...
      </section>

      <section v-else-if="errorMsg" class="state-card error">
        {{ errorMsg }}
      </section>

      <section v-else-if="detail" class="surface-card article">
        <img :src="heroImage" class="hero-image" alt="cover">

        <div class="main-block">
          <p class="text-secondary">作者：{{ detail.authorName }}</p>
          <h1 class="text-large-title">{{ detail.title }}</h1>
          <p class="text-body body">{{ detail.content || '暂无正文' }}</p>
        </div>

        <div class="comment-block">
          <h2 class="text-headline">评论</h2>

          <div class="editor">
            <input
              v-model="commentContent"
              class="input"
              placeholder="写下你的评论"
              @keyup.enter="handlePostComment"
            >
            <button class="primary-btn send-btn" type="button" :disabled="sending || !commentContent.trim()" @click="handlePostComment">
              {{ sending ? '发送中...' : '发送' }}
            </button>
          </div>

          <div v-if="commentLoading" class="state-card small">
            正在加载评论...
          </div>

          <div v-else-if="!pinnedComment && comments.length === 0" class="state-card small">
            还没有评论
          </div>

          <div v-else class="comment-list">
            <div v-if="pinnedComment" class="pin-block">
              <p class="text-secondary pin-title">置顶评论</p>
              <CommentItem :comment="pinnedComment" />
            </div>

            <div v-for="item in comments" :key="item.commentId" class="root-row">
              <CommentItem :comment="item" />

              <button
                v-if="item.replyCount > 0"
                class="reply-btn"
                type="button"
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
                  class="reply-btn"
                  type="button"
                  :disabled="getReplyThread(item).loading"
                  @click="loadReplies(item, true)"
                >
                  {{ getReplyThread(item).loading ? '加载中...' : '加载更多回复' }}
                </button>
              </div>
            </div>

            <button
              v-if="hasMoreComments"
              class="reply-btn"
              type="button"
              :disabled="commentLoading"
              @click="loadComments(false)"
            >
              {{ commentLoading ? '加载中...' : '加载更多评论' }}
            </button>
          </div>
        </div>
      </section>
    </main>
  </div>
</template>

<style scoped>
.detail-content {
  display: grid;
  gap: 12px;
}

.bar {
  padding: 8px;
  display: flex;
  justify-content: space-between;
}

.bar .secondary-btn,
.bar .primary-btn {
  min-width: 88px;
  padding: 0 14px;
}

.article {
  overflow: hidden;
}

.hero-image {
  width: 100%;
  max-height: 320px;
  object-fit: cover;
}

.main-block,
.comment-block {
  padding: 14px;
}

.body {
  margin-top: 10px;
  white-space: pre-wrap;
}

.comment-block {
  border-top: 1px solid #f8e3ea;
  display: grid;
  gap: 10px;
}

.editor {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 8px;
}

.input {
  min-height: 42px;
  border-radius: 999px;
  border: 1px solid var(--border-soft);
  padding: 0 12px;
  outline: none;
}

.send-btn {
  min-width: 88px;
  padding: 0 12px;
}

.comment-list {
  display: grid;
  gap: 8px;
}

.pin-title {
  margin-bottom: 6px;
  font-size: 0.84rem;
}

.reply-btn {
  color: var(--brand-primary);
  font-weight: 700;
  font-size: 0.84rem;
  text-align: left;
  padding: 2px 4px;
}

.reply-thread {
  margin-left: 22px;
  border-left: 2px solid #f9dde6;
  padding-left: 8px;
  display: grid;
  gap: 6px;
}

.state-card {
  min-height: 120px;
  border: 1px solid var(--border-soft);
  border-radius: var(--radius-lg);
  background: var(--bg-surface);
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  color: var(--text-secondary);
}

.state-card.small {
  min-height: 80px;
}

.error {
  color: var(--brand-danger);
}

@media (max-width: 700px) {
  .editor {
    grid-template-columns: 1fr;
  }

  .send-btn {
    width: 100%;
  }
}
</style>


