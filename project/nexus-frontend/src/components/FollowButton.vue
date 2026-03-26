<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { followUser, unfollowUser } from '@/api/relation'
import { useAuthStore } from '@/store/auth'
import type { RelationState } from '@/api/types'

const props = defineProps<{
  userId: string;
  relationState?: RelationState;
}>();

const authStore = useAuthStore();
const currentState = ref<RelationState>(props.relationState ?? 'UNKNOWN');
const loading = ref(false);
const isDisabled = computed(() => {
  return loading.value || currentState.value === 'UNKNOWN' || !authStore.userId;
});
const buttonText = computed(() => {
  if (currentState.value === 'UNKNOWN') {
    return '状态未知';
  }
  return currentState.value === 'FOLLOWING' ? '已关注' : '关注';
});

watch(
  () => props.relationState,
  (value) => {
    currentState.value = value ?? 'UNKNOWN';
  }
);

const toggleFollow = async () => {
  if (isDisabled.value || !authStore.userId) return;
  
  loading.value = true;
  try {
    const payload = {
      sourceId: authStore.userId,
      targetId: props.userId
    };

    if (currentState.value === 'FOLLOWING') {
      await unfollowUser(payload);
      currentState.value = 'NOT_FOLLOWING';
    } else {
      await followUser(payload);
      currentState.value = 'FOLLOWING';
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
    :class="{ 'following': currentState === 'FOLLOWING', 'disabled': currentState === 'UNKNOWN' }"
    :disabled="isDisabled"
    @click="toggleFollow"
  >
    <span v-if="loading" class="spinner-small"></span>
    <span v-else>{{ buttonText }}</span>
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

.follow-btn:disabled {
  cursor: not-allowed;
  opacity: 0.6;
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
