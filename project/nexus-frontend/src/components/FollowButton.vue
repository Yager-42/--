<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { followUser, unfollowUser } from '@/api/relation'
import { useAuthStore } from '@/store/auth'
import type { RelationState } from '@/api/types'

const props = defineProps<{
  userId: string
  relationState?: RelationState
}>()

const authStore = useAuthStore()
const state = ref<RelationState>(props.relationState ?? 'UNKNOWN')
const loading = ref(false)

watch(
  () => props.relationState,
  (value) => {
    state.value = value ?? 'UNKNOWN'
  }
)

const disabled = computed(() => !authStore.userId || loading.value || state.value === 'UNKNOWN')
const text = computed(() => {
  if (state.value === 'UNKNOWN') return '状态未知'
  return state.value === 'FOLLOWING' ? '已关注' : '关注'
})

const onToggle = async () => {
  if (disabled.value || !authStore.userId) return

  loading.value = true
  try {
    const payload = { sourceId: authStore.userId, targetId: props.userId }
    if (state.value === 'FOLLOWING') {
      await unfollowUser(payload)
      state.value = 'NOT_FOLLOWING'
    } else {
      await followUser(payload)
      state.value = 'FOLLOWING'
    }
  } catch (error) {
    console.error('follow action failed', error)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <button
    class="follow-btn"
    :class="{ following: state === 'FOLLOWING', unknown: state === 'UNKNOWN' }"
    type="button"
    :disabled="disabled"
    @click="onToggle"
  >
    <span v-if="loading">处理中...</span>
    <span v-else>{{ text }}</span>
  </button>
</template>

<style scoped>
.follow-btn {
  min-width: 92px;
  min-height: 36px;
  border-radius: 999px;
  padding: 0 12px;
  background: var(--brand-primary);
  color: #fff;
  font-weight: 700;
  font-size: 0.84rem;
}

.follow-btn.following {
  background: var(--bg-elevated);
  color: var(--text-primary);
  border: 1px solid var(--border-soft);
}

.follow-btn.unknown {
  background: #e6e6e6;
  color: #666;
}

.follow-btn:disabled {
  cursor: not-allowed;
  opacity: 0.7;
}
</style>
