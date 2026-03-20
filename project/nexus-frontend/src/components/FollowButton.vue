<script setup lang="ts">
import { ref } from 'vue'
import { followUser, unfollowUser } from '@/api/relation'

const props = defineProps<{
  userId: string;
  initialState?: boolean;
}>();

const isFollowing = ref(props.initialState || false);
const loading = ref(false);

const toggleFollow = async () => {
  if (loading.value) return;
  
  loading.value = true;
  try {
    if (isFollowing.value) {
      await unfollowUser(props.userId);
      isFollowing.value = false;
    } else {
      await followUser(props.userId);
      isFollowing.value = true;
    }
  } catch (err) {
    console.error('Follow operation failed', err);
  } finally {
    loading.value = false;
  }
}
</script>

<template>
  <button 
    class="follow-btn" 
    :class="{ 'following': isFollowing }"
    :disabled="loading"
    @click="toggleFollow"
  >
    <span v-if="loading" class="spinner-small"></span>
    <span v-else>{{ isFollowing ? '已关注' : '关注' }}</span>
  </button>
</template>

<style scoped>
.follow-btn {
  width: 120px;
  height: 36px;
  border: none;
  border-radius: 18px;
  font-size: 15px;
  font-weight: 600;
  display: flex;
  justify-content: center;
  align-items: center;
  cursor: pointer;
  transition: all 0.2s var(--spring-easing);
  background-color: var(--apple-accent);
  color: white;
}

.follow-btn:active {
  transform: scale(0.96);
}

.follow-btn.following {
  background-color: #e5e5ea;
  color: var(--apple-text);
}

.spinner-small {
  width: 14px;
  height: 14px;
  border: 1.5px solid rgba(255,255,255,0.3);
  border-top-color: white;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

.follow-btn.following .spinner-small {
  border-color: rgba(0,0,0,0.1);
  border-top-color: var(--apple-text);
}

@keyframes spin {
  to { transform: rotate(360deg); }
}
</style>
