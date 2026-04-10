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
    type="button"
    class="inline-flex min-h-[42px] min-w-[104px] items-center justify-center rounded-full px-4 text-sm font-semibold transition disabled:cursor-not-allowed disabled:opacity-60"
    :class="state === 'FOLLOWING'
      ? 'border border-outline-variant/20 bg-white/80 text-on-surface'
      : state === 'UNKNOWN'
        ? 'bg-tertiary/15 text-on-surface-variant'
        : 'zen-gradient-cta'"
    :disabled="disabled"
    @click="onToggle"
  >
    <span v-if="loading">处理中...</span>
    <span v-else>{{ text }}</span>
  </button>
</template>
