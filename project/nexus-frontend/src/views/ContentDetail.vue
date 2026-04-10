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
import ContentDetailContinuation from '@/components/content/ContentDetailContinuation.vue'
import ContentDetailHeader from '@/components/content/ContentDetailHeader.vue'
import TheDock from '@/components/TheDock.vue'
import TheNavBar from '@/components/TheNavBar.vue'
import FormMessage from '@/components/system/FormMessage.vue'
import StatePanel from '@/components/system/StatePanel.vue'

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
const commentError = ref('')
const commentContent = ref('')
const liked = ref(false)
const likeCount = ref(0)

const postId = computed(() => String(route.params.postId || ''))

const continuationItems = computed(() => {
  if (!detail.value) return []
  return [
    {
      id: detail.value.postId,
      title: detail.value.title,
      subtitle: detail.value.summary || detail.value.authorName
    }
  ]
})

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
  commentError.value = ''

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
    commentError.value = error instanceof Error ? error.message : '评论发送失败'
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

watch(
  () => route.params.postId,
  () => {
    void loadAll()
  }
)
</script>

<template>
  <div class="page-wrap">
    <TheNavBar />

    <main class="page-main page-main--dock">
      <section class="grid gap-6">
        <header class="detail-actions">
          <button class="secondary-btn" type="button" @click="router.back()">返回</button>
          <button class="primary-btn" type="button" @click="handleLike">
            {{ liked ? '已赞' : '点赞' }} {{ likeCount }}
          </button>
        </header>

        <StatePanel
          v-if="loading"
          variant="loading"
          title="正在准备内容详情"
          body="正文、评论与相关内容正在加载中。"
        />

        <StatePanel
          v-else-if="errorMsg"
          variant="request-failure"
          :body="errorMsg"
          primary-label="返回首页"
          @primary="router.push('/')"
        />

        <template v-else-if="detail">
          <ContentDetailHeader :detail="detail" />

          <section class="paper-panel p-6 md:p-8">
            <div class="detail-body">
              <p class="text-body">{{ detail.content || '暂无正文。' }}</p>
            </div>
          </section>

          <ContentDetailContinuation
            :items="continuationItems"
            title="继续浏览同一主题"
            @select="router.push(`/content/${$event}`)"
          />

          <section class="paper-panel grid gap-5 p-6 md:p-8">
            <div class="comment-head">
              <h2 class="text-headline">评论</h2>
            </div>

            <div class="comment-editor">
              <textarea
                v-model="commentContent"
                class="min-h-[120px] rounded-3xl border border-outline-variant/10 bg-surface-container-low px-5 py-4 text-sm text-on-surface outline-none transition focus:border-primary/20 focus:bg-surface-container-lowest"
                placeholder="写下你的评论"
              />
              <button
                class="primary-btn"
                type="button"
                :disabled="sending || !commentContent.trim()"
                @click="handlePostComment"
              >
                {{ sending ? '发送中...' : '发送评论' }}
              </button>
            </div>

            <FormMessage
              v-if="commentError"
              tone="error"
              :message="commentError"
            />

            <StatePanel
              v-if="commentLoading && !pinnedComment && comments.length === 0"
              variant="loading"
              title="正在整理评论"
              body="讨论区内容正在同步中。"
            />

            <StatePanel
              v-else-if="!pinnedComment && comments.length === 0"
              variant="empty"
              title="还没有评论"
              body="成为第一个留下想法的人。"
            />

            <div v-else class="comment-list">
              <div v-if="pinnedComment" class="comment-list__pin">
                <p class="comment-list__eyebrow">置顶评论</p>
                <CommentItem :comment="pinnedComment" />
              </div>

              <div v-for="item in comments" :key="item.commentId" class="comment-list__group">
                <CommentItem :comment="item" />

                <button
                  v-if="item.replyCount > 0"
                  class="comment-list__toggle"
                  type="button"
                  @click="toggleReplies(item)"
                >
                  {{ getReplyThread(item).expanded ? '收起回复' : `查看回复（${item.replyCount}）` }}
                </button>

                <div v-if="getReplyThread(item).expanded" class="comment-list__thread">
                  <CommentItem
                    v-for="reply in getReplyThread(item).items"
                    :key="reply.commentId"
                    :comment="reply"
                    compact
                  />

                  <button
                    v-if="getReplyThread(item).hasMore"
                    type="button"
                    class="comment-list__toggle"
                    :disabled="getReplyThread(item).loading"
                    @click="loadReplies(item, true)"
                  >
                    {{ getReplyThread(item).loading ? '加载中...' : '加载更多回复' }}
                  </button>
                </div>
              </div>

              <button
                v-if="hasMoreComments"
                type="button"
                class="secondary-btn comment-list__more"
                :disabled="commentLoading"
                @click="loadComments(false)"
              >
                {{ commentLoading ? '加载中...' : '加载更多评论' }}
              </button>
            </div>
          </section>
        </template>
      </section>
    </main>

    <TheDock />
  </div>
</template>

<style scoped>
.detail-actions {
  display: flex;
  justify-content: space-between;
  gap: 1rem;
}

.detail-body {
  max-width: 48rem;
}

.comment-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.comment-editor {
  display: grid;
  gap: 0.9rem;
}

.comment-editor button {
  justify-self: end;
}

.comment-list {
  display: grid;
  gap: 1rem;
}

.comment-list__pin {
  display: grid;
  gap: 0.6rem;
}

.comment-list__eyebrow {
  color: var(--text-muted);
  font-size: 0.76rem;
  letter-spacing: 0.16em;
  text-transform: uppercase;
}

.comment-list__group {
  display: grid;
  gap: 0.55rem;
}

.comment-list__toggle {
  justify-self: start;
  color: var(--brand-primary-solid);
  font-weight: 600;
}

.comment-list__thread {
  margin-left: 1.5rem;
  display: grid;
  gap: 0.5rem;
  padding-left: 1rem;
  border-left: 1px solid var(--border-soft);
}

.comment-list__more {
  justify-self: center;
}

@media (max-width: 720px) {
  .detail-actions {
    flex-direction: column;
  }

  .comment-editor button {
    justify-self: stretch;
  }
}
</style>
