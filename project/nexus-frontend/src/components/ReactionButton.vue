<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  isLiked: boolean
  count: number
}>()

const emit = defineEmits<{
  (event: 'toggle'): void
}>()

const label = computed(() => (props.isLiked ? '取消点赞' : '点赞'))

const onToggle = (e: Event) => {
  e.stopPropagation()
  emit('toggle')
}
</script>

<template>
  <button class="reaction-btn" :class="{ active: isLiked }" type="button" :aria-label="label" @click="onToggle">
    <svg viewBox="0 0 24 24" class="heart-icon" :fill="isLiked ? 'currentColor' : 'none'" stroke="currentColor" stroke-width="2">
      <path d="M12 21.35 10.55 20C5.4 15.36 2 12.28 2 8.5A5.5 5.5 0 0 1 7.5 3c1.74 0 3.41.81 4.5 2.09A6 6 0 0 1 16.5 3 5.5 5.5 0 0 1 22 8.5c0 3.78-3.4 6.86-8.55 11.54L12 21.35Z" />
    </svg>
    <span>{{ count }}</span>
  </button>
</template>

<style scoped>
.reaction-btn {
  min-height: 40px;
  border-radius: 999px;
  padding: 0 12px;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  background: rgba(255, 255, 255, 0.92);
  border: 1px solid rgba(255, 255, 255, 0.8);
  color: var(--text-primary);
  font-weight: 700;
  box-shadow: 0 6px 16px rgba(45, 16, 32, 0.12);
}

.reaction-btn.active {
  color: var(--brand-primary);
}

.heart-icon {
  width: 18px;
  height: 18px;
}
</style>
