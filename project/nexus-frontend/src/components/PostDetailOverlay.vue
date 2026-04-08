<script setup lang="ts">
import { ref, watch } from 'vue'
import { Motion } from '@motionone/vue'
import { fetchComments, postComment, type RootCommentDisplayItem } from '@/api/interact'
import CommentItem from './CommentItem.vue'

const props = defineProps<{
  post: {
    id: string
    title: string
    body: string
    author: string
    image: string
  } | null
  isOpen: boolean
}>()

const emit = defineEmits<{
  (event: 'close'): void
}>()

const comments = ref<RootCommentDisplayItem[]>([])
const commentContent = ref('')
const loading = ref(false)
const sending = ref(false)

const loadComments = async () => {
  if (!props.post?.id) return

  loading.value = true
  try {
    const res = await fetchComments({
      postId: props.post.id,
      limit: 20,
      preloadReplyLimit: 2
    })
    comments.value = res.pinned ? [res.pinned, ...res.items] : res.items
  } catch (error) {
    console.error('fetch comments failed', error)
  } finally {
    loading.value = false
  }
}

const handlePostComment = async () => {
  const content = commentContent.value.trim()
  if (!content || !props.post?.id) return

  sending.value = true
  const optimisticCommentId = `overlay-${Date.now()}`
  const optimisticComment: RootCommentDisplayItem = {
    commentId: optimisticCommentId,
    postId: props.post.id,
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
    await postComment({ postId: props.post.id, content })
    await loadComments()
  } catch (error) {
    console.error('post comment failed', error)
    comments.value = comments.value.filter((item) => item.commentId !== optimisticCommentId)
    commentContent.value = content
  } finally {
    sending.value = false
  }
}

watch(
  () => props.isOpen,
  (open) => {
    if (open) {
      void loadComments()
    }
  }
)
</script>

<template>
  <div v-if="isOpen && post" class="detail-overlay">
    <Motion
      class="detail-card"
      :initial="{ y: 100, opacity: 0, scale: 0.9 }"
      :animate="{ y: 0, opacity: 1, scale: 1 }"
      :exit="{ y: 100, opacity: 0, scale: 0.9 }"
    >
      <button class="close-btn" type="button" @click="emit('close')">×</button>
      <div class="detail-image-wrapper">
        <img :src="post.image" class="detail-image" alt="cover">
      </div>
      <div class="detail-content">
        <h1 class="text-large-title">{{ post.title }}</h1>
        <p class="author-tag text-secondary">作者：{{ post.author }}</p>
        <div class="rich-text">
          <p class="text-body">{{ post.body }}</p>
        </div>

        <div class="comment-section">
          <h3 class="text-headline">评论</h3>

          <div class="comment-input-wrapper">
            <input
              v-model="commentContent"
              placeholder="添加评论..."
              class="comment-input"
              @keyup.enter="handlePostComment"
            >
            <button
              class="post-btn"
              type="button"
              :disabled="!commentContent.trim() || sending"
              @click="handlePostComment"
            >
              {{ sending ? '发送中...' : '发送' }}
            </button>
          </div>

          <div v-if="loading" class="loading-comments">评论加载中...</div>
          <div v-else-if="comments.length === 0" class="empty-comments">还没有评论，来发布第一条吧。</div>
          <div v-else class="comment-list">
            <CommentItem v-for="item in comments" :key="item.commentId" :comment="item" />
          </div>
        </div>
      </div>
    </Motion>
  </div>
</template>

<style scoped>
.detail-overlay {
  position: fixed;
  inset: 0;
  z-index: 2000;
  background: rgba(20, 10, 18, 0.32);
  backdrop-filter: blur(10px);
  display: flex;
  justify-content: center;
  align-items: flex-end;
}

.detail-card {
  width: min(940px, 100%);
  height: 92dvh;
  background: #fff;
  border-radius: 28px 28px 0 0;
  overflow-y: auto;
  position: relative;
}

.close-btn {
  position: absolute;
  right: 16px;
  top: 16px;
  width: 34px;
  height: 34px;
  border-radius: 50%;
  background: rgba(0, 0, 0, 0.52);
  color: #fff;
  font-size: 20px;
  z-index: 10;
}

.detail-image-wrapper {
  width: 100%;
  height: 40dvh;
}

.detail-image {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.detail-content {
  padding: 24px 20px;
}

.author-tag {
  margin-top: 6px;
}

.comment-section {
  border-top: 1px solid #f8e3ea;
  padding-top: 18px;
  margin-top: 18px;
}

.comment-input-wrapper {
  margin: 14px 0 18px;
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 8px;
}

.comment-input {
  min-height: 42px;
  border: 1px solid var(--border-soft);
  border-radius: 999px;
  padding: 0 12px;
}

.post-btn {
  min-width: 88px;
  border-radius: 999px;
  background: var(--brand-primary);
  color: #fff;
  font-weight: 700;
}

.post-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.comment-list {
  display: grid;
  gap: 8px;
}

.loading-comments,
.empty-comments {
  text-align: center;
  padding: 34px 0;
  color: var(--text-secondary);
}

@media (max-width: 700px) {
  .comment-input-wrapper {
    grid-template-columns: 1fr;
  }

  .post-btn {
    min-height: 42px;
  }
}
</style>
