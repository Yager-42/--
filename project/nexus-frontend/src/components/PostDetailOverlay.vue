<script setup lang="ts">
import { ref, onMounted, watch } from 'vue'
import { Motion } from '@motionone/vue'
import { fetchComments, postComment } from '@/api/interact'
import CommentItem from './CommentItem.vue'

const props = defineProps<{
  post: {
    id: string;
    title: string;
    body: string;
    author: string;
    image: string;
  } | null;
  isOpen: boolean;
}>();

const emit = defineEmits(['close']);

const comments = ref<any[]>([]);
const commentContent = ref('');
const loading = ref(false);
const sending = ref(false);

const springTransition = {
  duration: 0.6,
  easing: [0.32, 0.72, 0, 1]
};

const loadComments = async () => {
  if (!props.post?.id) return;
  loading.value = true;
  try {
    const res: any = await fetchComments(props.post.id);
    comments.value = res.items || [];
  } catch (err) {
    console.error('Fetch comments failed', err);
  } finally {
    loading.value = false;
  }
}

const handlePostComment = async () => {
  if (!commentContent.value.trim() || !props.post?.id) return;
  
  sending.value = true;
  try {
    const newComment = {
      authorName: '我',
      content: commentContent.value,
      createTime: Date.now()
    };
    
    await postComment({
      postId: props.post.id,
      content: commentContent.value
    });
    
    // Optimistic Update
    comments.value = [newComment, ...comments.value];
    commentContent.value = '';
  } catch (err) {
    console.error('Post comment failed', err);
  } finally {
    sending.value = false;
  }
}

watch(() => props.isOpen, (newVal) => {
  if (newVal) loadComments();
});
</script>

<template>
  <div v-if="isOpen && post" class="detail-overlay">
    <Motion
      class="detail-card"
      :initial="{ y: 100, opacity: 0, scale: 0.9 }"
      :animate="{ y: 0, opacity: 1, scale: 1 }"
      :exit="{ y: 100, opacity: 0, scale: 0.9 }"
      :transition="springTransition"
    >
      <div class="close-btn" @click="emit('close')">✕</div>
      <div class="detail-image-wrapper">
        <img :src="post.image" class="detail-image" />
      </div>
      <div class="detail-content">
        <h1 class="text-large-title">{{ post.title }}</h1>
        <p class="author-tag">By {{ post.author }}</p>
        <div class="rich-text">
          <p class="text-body">{{ post.body }}</p>
        </div>
        
        <div class="comment-section">
          <h3 class="text-headline">评论</h3>
          
          <div class="comment-input-wrapper">
            <input 
              v-model="commentContent" 
              placeholder="添加评论..." 
              class="apple-input"
              @keyup.enter="handlePostComment"
            />
            <button 
              class="post-btn" 
              :disabled="!commentContent.trim() || sending" 
              @click="handlePostComment"
            >
              {{ sending ? '发布中' : '发布' }}
            </button>
          </div>

          <div v-if="loading" class="loading-comments">
            加载评论中...
          </div>
          <div v-else-if="comments.length === 0" class="empty-comments">
            暂无评论，来发表第一条见解。
          </div>
          <div v-else class="comment-list">
            <CommentItem 
              v-for="(c, i) in comments" 
              :key="i" 
              :comment="c" 
            />
          </div>
        </div>
      </div>
    </Motion>
  </div>
</template>

<style scoped>
.detail-overlay {
  position: fixed;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  z-index: 2000;
  background: rgba(0, 0, 0, 0.2);
  backdrop-filter: blur(10px);
  display: flex;
  justify-content: center;
  align-items: flex-end;
}

.detail-card {
  width: 100%;
  height: 94vh;
  background: white;
  border-radius: 32px 32px 0 0;
  overflow-y: auto;
  position: relative;
}

.close-btn {
  position: absolute;
  top: 20px;
  right: 20px;
  width: 32px;
  height: 32px;
  background: rgba(0,0,0,0.5);
  color: white;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 10;
  font-size: 14px;
}

.detail-image-wrapper {
  width: 100%;
  height: 45vh;
}

.detail-image {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.detail-content {
  padding: 32px 24px;
}

.comment-section {
  border-top: 0.5px solid #eee;
  padding-top: 24px;
  margin-top: 24px;
}

.comment-input-wrapper {
  margin: 16px 0 24px;
  display: flex;
  gap: 12px;
}

.apple-input {
  flex: 1;
  height: 44px;
  background: #f5f5f7;
  border: none;
  border-radius: 22px;
  padding: 0 20px;
  font-size: 15px;
  outline: none;
}

.post-btn {
  background: none;
  border: none;
  color: var(--apple-accent);
  font-weight: 600;
  font-size: 15px;
  cursor: pointer;
}

.post-btn:disabled {
  opacity: 0.4;
}

.comment-list {
  display: flex;
  flex-direction: column;
}

.loading-comments, .empty-comments {
  text-align: center;
  padding: 40px 0;
  color: var(--apple-text-secondary);
  font-size: 15px;
}
</style>
